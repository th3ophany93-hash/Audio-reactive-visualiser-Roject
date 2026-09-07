package com.arvs.core.assets

import com.arvs.core.diagnostics.ErrorReporter
import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.LoggingPolicy
import com.arvs.core.diagnostics.ManualDiagnosticsClock
import com.arvs.core.diagnostics.RecordingLogSink
import com.arvs.core.diagnostics.Subsystem
import com.arvs.core.model.AssetType
import com.arvs.core.model.ErrorCategory
import com.arvs.core.model.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins §82.1's missing-asset recovery: lazy validation, the recoverable outcome, and relink.
 *
 * §82.1's promise is that a missing asset is *"never a crash, and the project remains
 * otherwise editable"*. Every test here is a way of asking whether that promise holds.
 */
class MissingAssetRecoveryTest {

    private val storage = FakeAssetStorage()
    private val sink = RecordingLogSink(capacity = 512)
    private val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }
    private val errorReporter = ErrorReporter(logger)
    private val registry = DefaultAssetRegistry(
        storage = storage,
        logger = logger,
        errorReporter = errorReporter,
    )

    // --- Lazy validation (§82.1) --------------------------------------------------------

    @Test
    fun `restoring a project validates nothing until an asset is actually used`() = runTest {
        // §82.1: "On project load, assets are validated lazily (on first actual use, not
        // eagerly for the whole project)." This is a statement about calls that must NOT
        // happen, so it is asserted against the storage port's call log.
        val refs = (1..5).map { index ->
            (registry.import(storage.put("content://track$index", byteArrayOf(index.toByte()))) as Outcome.Success).value
        }
        val reloaded = DefaultAssetRegistry(storage = storage, logger = logger)
        refs.forEach(reloaded::register)

        storage.clearCalls()
        assertEquals(5, reloaded.all().size)          // the project is fully loaded...
        assertTrue(storage.calls.isEmpty())            // ...and storage has not been touched

        reloaded.validate(refs[2].id)                  // first actual use of exactly one asset

        assertTrue(storage.calls.all { it.contains("track3") })
    }

    @Test
    fun `a confirmed asset is not re-probed on every use`() = runTest {
        val ref = (registry.import(storage.put("content://a", byteArrayOf(1))) as Outcome.Success).value
        registry.invalidate(ref.id)

        registry.validate(ref.id)
        storage.clearCalls()
        repeat(20) { registry.validate(ref.id) }

        assertTrue(storage.calls.isEmpty())
    }

    @Test
    fun `a failed validation is re-probed, so recovery is automatic`() = runTest {
        // Caching the failure produces the worst kind of bug report: "I fixed it and it still
        // says missing."
        //
        // Asserted against the storage call log, not just against the returned value. An
        // earlier version of this test checked only that the second call reported Available —
        // which a wrongly *cached* result satisfies just as well as a genuine re-probe, so it
        // could not tell the two apart. Observing the port is what makes the distinction.
        val uri = storage.put("content://a", byteArrayOf(1))
        val ref = (registry.import(uri) as Outcome.Success).value
        registry.invalidate(ref.id)
        storage.deleteExternally(uri)

        assertTrue(registry.validate(ref.id) is AssetAvailability.MissingRelinkRequired)

        // Still missing: the failure must not have been cached, so storage is consulted again.
        storage.clearCalls()
        assertTrue(registry.validate(ref.id) is AssetAvailability.MissingRelinkRequired)
        assertTrue("a cached failure would never re-probe storage", storage.calls.isNotEmpty())

        // The user restores the file externally — picked up with no explicit invalidation.
        storage.document(uri).exists = true
        storage.clearCalls()
        assertTrue(registry.validate(ref.id) is AssetAvailability.Available)
        assertTrue(storage.calls.isNotEmpty())
    }

    // --- The two §82.1 failure causes, kept distinct -------------------------------------

    @Test
    fun `a deleted file yields FILE_NOT_FOUND`() = runTest {
        val uri = storage.put("content://a", byteArrayOf(1))
        val ref = (registry.import(uri) as Outcome.Success).value
        registry.invalidate(ref.id)
        storage.deleteExternally(uri)

        val availability = registry.validate(ref.id)

        assertEquals(
            MissingReason.FILE_NOT_FOUND,
            (availability as AssetAvailability.MissingRelinkRequired).reason,
        )
        assertEquals(ref, availability.ref) // the project still holds a usable reference
    }

    @Test
    fun `a revoked SAF grant yields PERMISSION_REVOKED, not FILE_NOT_FOUND`() = runTest {
        val uri = storage.put("content://a", byteArrayOf(1))
        val ref = (registry.import(uri) as Outcome.Success).value
        registry.invalidate(ref.id)
        storage.revokePermission(uri)

        val availability = registry.validate(ref.id) as AssetAvailability.MissingRelinkRequired

        assertEquals(MissingReason.PERMISSION_REVOKED, availability.reason)
        assertEquals(ErrorCategory.PERMISSION_ERROR, errorReporter.recentErrors().last().error.category)
    }

    @Test
    fun `a missing asset is a value, never an exception`() = runTest {
        // "Never a crash" as a property of the type rather than a discipline the caller has
        // to maintain.
        val uri = storage.put("content://a", byteArrayOf(1))
        val ref = (registry.import(uri) as Outcome.Success).value
        registry.invalidate(ref.id)
        storage.deleteExternally(uri)
        storage.revokePermission(uri)

        val availability = registry.validate(ref.id)

        assertFalse(availability.isAvailable)
        assertTrue(registry.resolve(ref.id) != null)   // project remains otherwise editable
        assertTrue(registry.all().isNotEmpty())
    }

    @Test
    fun `a missing asset is reported against the spec 97 taxonomy with a recovery hint`() = runTest {
        val uri = storage.put("content://set.flac", byteArrayOf(1), displayName = "set.flac")
        val ref = (registry.import(uri) as Outcome.Success).value
        registry.invalidate(ref.id)
        storage.deleteExternally(uri)

        registry.validate(ref.id)

        val reported = errorReporter.recentErrors().last()
        assertEquals(Subsystem.ASSETS, reported.subsystem)
        assertEquals(ErrorCategory.IMPORT_ERROR, reported.error.category)
        assertTrue(reported.error.message.contains("set.flac"))
        assertTrue(reported.error.recoveryHint!!.contains("Relink"))
    }

    // --- Relink (§82.1) -----------------------------------------------------------------

    @Test
    fun `relinking to the same content keeps the id and every cache entry valid`() = runTest {
        val bytes = "the same audio".toByteArray()
        val original = storage.put("content://old/path.flac", bytes)
        val ref = (registry.import(original) as Outcome.Success).value
        storage.deleteExternally(original)

        val moved = storage.put("content://new/path.flac", bytes)
        val result = (registry.relink(ref.id, moved) as Outcome.Success).value

        assertTrue(result is RelinkResult.ExactMatch)
        assertEquals(ref.id, result.ref.id)            // id preserved: a recovery, not a rewrite
        assertEquals(ref.hash, result.ref.hash)        // content identity unchanged
        assertEquals(moved, result.ref.uri)
        assertEquals(ref.hash, registry.contentHash(ref.id))
    }

    @Test
    fun `relinking to different content is surfaced, not silently accepted`() = runTest {
        // §82.1 specifies the relink mechanism but does not legislate whether different bytes
        // are permitted — re-encoding a track is legitimate; picking the wrong file from a
        // crowded folder is not. The registry reports the distinction and leaves the policy
        // to the caller.
        val ref = (registry.import(storage.put("content://a.flac", "original".toByteArray())) as Outcome.Success).value

        val result = (registry.relink(ref.id, storage.put("content://b.flac", "different".toByteArray())) as Outcome.Success).value

        assertTrue(result is RelinkResult.ContentChanged)
        assertEquals(ref.hash, (result as RelinkResult.ContentChanged).previousHash)
        assertNotEquals(ref.hash, result.ref.hash)
        assertEquals(ref.id, result.ref.id)
        assertTrue(sink.snapshot().any { it.key == "relink-content-changed" })
    }

    @Test
    fun `a changed hash re-keys the caches automatically`() = runTest {
        // Content-addressing does the invalidation: nothing has to be cleared by hand, which
        // is what makes §18.3's aggressive eviction safe.
        val ref = (registry.import(storage.put("content://a", "one".toByteArray())) as Outcome.Success).value
        val before = registry.contentHash(ref.id)

        registry.relink(ref.id, storage.put("content://b", "two".toByteArray()))

        assertNotEquals(before, registry.contentHash(ref.id))
    }

    @Test
    fun `relinking an unknown id fails without registering anything`() = runTest {
        val outcome = registry.relink(com.arvs.core.model.AssetId("never-imported"), storage.put("content://x", byteArrayOf(1)))

        assertEquals(ErrorCategory.IMPORT_ERROR, (outcome as Outcome.Failure).error.category)
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `relinking to an unreadable file fails and leaves the old reference intact`() = runTest {
        val ref = (registry.import(storage.put("content://a", byteArrayOf(1))) as Outcome.Success).value
        val broken = storage.put("content://broken", byteArrayOf(2))
        storage.deleteExternally(broken)

        val outcome = registry.relink(ref.id, broken)

        assertTrue(outcome is Outcome.Failure)
        assertEquals(ref.uri, registry.resolve(ref.id)!!.uri)  // unchanged
        assertEquals(ref.hash, registry.contentHash(ref.id))
    }

    @Test
    fun `an asset restored from a project is registered but not pre-validated`() = runTest {
        val ref = (registry.import(storage.put("content://a", byteArrayOf(1))) as Outcome.Success).value
        val reloaded = DefaultAssetRegistry(storage = storage, logger = logger)

        reloaded.register(ref.copy(type = AssetType.AUDIO))
        storage.clearCalls()

        assertEquals(ref, reloaded.resolve(ref.id))
        assertTrue(storage.calls.isEmpty())
        reloaded.validate(ref.id)
        assertTrue(storage.calls.isNotEmpty())  // validated only now, on first actual use
    }
}
