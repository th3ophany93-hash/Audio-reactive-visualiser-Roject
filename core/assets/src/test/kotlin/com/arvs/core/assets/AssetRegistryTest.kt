package com.arvs.core.assets

import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.LoggingPolicy
import com.arvs.core.diagnostics.ManualDiagnosticsClock
import com.arvs.core.diagnostics.RecordingLogSink
import com.arvs.core.diagnostics.Subsystem
import com.arvs.core.model.AssetHash
import com.arvs.core.model.AssetId
import com.arvs.core.model.AssetType
import com.arvs.core.model.ErrorCategory
import com.arvs.core.model.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §82's Asset Registry and §27.2's mandatory id → hash indirection. */
class AssetRegistryTest {

    private val storage = FakeAssetStorage()
    private val sink = RecordingLogSink(capacity = 512)
    private val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }
    private val registry = DefaultAssetRegistry(storage = storage, logger = logger)

    @Test
    fun `import takes a persistable permission, hashes, and registers`() = runTest {
        val uri = storage.put("content://track.flac", "audio-bytes".toByteArray(), displayName = "track.flac")

        val ref = (registry.import(uri) as Outcome.Success).value

        assertEquals(uri, ref.uri)
        assertEquals(AssetType.AUDIO, ref.type)
        assertTrue(ref.persistedPermission)
        assertEquals("track.flac", ref.displayName)
        assertEquals(64, ref.hash.hex.length)
        assertEquals(ref, registry.resolve(ref.id))
        assertTrue("takePersistablePermission(${uri.value})" in storage.calls)
    }

    @Test
    fun `the content hash is reachable only through the registry`() = runTest {
        // §27.2 is binding: assetHash comes from resolving the assetRef through the registry
        // to the asset's hash field, never from the project-local id. AssetId and AssetHash
        // are separate types so the substitution cannot compile; this pins the indirection
        // that makes the correct value obtainable at all.
        val uri = storage.put("content://a.wav", byteArrayOf(1, 2, 3))
        val ref = (registry.import(uri) as Outcome.Success).value

        assertEquals(ref.hash, registry.contentHash(ref.id))
        assertNotEquals(ref.id.value, ref.hash.hex)
        assertNull(registry.contentHash(AssetId("never-imported")))
    }

    @Test
    fun `two identical files hash identically but keep distinct ids`() = runTest {
        // Content-addressing must collapse duplicates in the *cache*, while the project keeps
        // two distinct references. Conflating the two is precisely what §27.2 forbids.
        val bytes = "same".toByteArray()
        val first = (registry.import(storage.put("content://one.wav", bytes)) as Outcome.Success).value
        val second = (registry.import(storage.put("content://two.wav", bytes)) as Outcome.Success).value

        assertEquals(first.hash, second.hash)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `lastKnownHash equals hash at import`() = runTest {
        val ref = (registry.import(storage.put("content://a", byteArrayOf(7))) as Outcome.Success).value
        assertEquals(ref.hash, ref.lastKnownHash)
        assertTrue(!ref.contentChangedSinceLastValidation)
    }

    @Test
    fun `an unreadable file yields a categorised failure, not an exception`() = runTest {
        val uri = storage.put("content://gone.mp3", byteArrayOf(1))
        storage.deleteExternally(uri)

        val outcome = registry.import(uri)

        val error = (outcome as Outcome.Failure).error
        assertEquals(ErrorCategory.IMPORT_ERROR, error.category)
        assertTrue(error.message.contains("gone.mp3"))
        assertTrue(error.recoveryHint!!.isNotBlank()) // §97: never a bare failure
        assertNull(registry.resolve(AssetId("asset-1")))
    }

    @Test
    fun `a revoked permission is reported as PERMISSION_ERROR, not as a missing file`() = runTest {
        val uri = storage.put("content://locked.flac", byteArrayOf(1))
        storage.revokePermission(uri)
        storage.document(uri).permissionGrantable = false

        val error = (registry.import(uri) as Outcome.Failure).error

        // §97 keeps these apart because the remedies differ: telling someone their file is
        // missing sends them looking for a file that never moved.
        assertEquals(ErrorCategory.PERMISSION_ERROR, error.category)
    }

    @Test
    fun `an import that cannot persist its permission still succeeds but warns`() = runTest {
        // Some providers grant read access without a persistable claim. That works this
        // session and breaks after a reboot, so it is recorded at import rather than
        // diagnosed later from a confused bug report.
        val uri = storage.put("content://transient", byteArrayOf(1), permissionGrantable = false)

        val ref = (registry.import(uri) as Outcome.Success).value

        assertTrue(!ref.persistedPermission)
        assertTrue(sink.snapshot().any { it.key == "import-permission-not-persisted" })
    }

    @Test
    fun `import and hashing are instrumented with named spans (spec 100_1)`() = runTest {
        registry.import(storage.put("content://a", ByteArray(4_096)))

        val breakdown = registry.timingBreakdown()
        assertTrue(breakdown.span(Subsystem.ASSETS, "import") != null)
        assertTrue(breakdown.span(Subsystem.ASSETS, "hash") != null)
        assertEquals(setOf(Subsystem.ASSETS), breakdown.bySubsystem().keys)
    }

    @Test
    fun `all returns every registered reference in a stable order`() = runTest {
        repeat(3) { index -> registry.import(storage.put("content://$index", byteArrayOf(index.toByte()))) }

        val ids = registry.all().map { it.id.value }
        assertEquals(3, ids.size)
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `hashes are never fabricated for unknown ids`() = runTest {
        // A plausible-looking placeholder digest is exactly the value that ends up in a cache
        // key and quietly addresses the wrong content.
        assertNull(registry.contentHash(AssetId("nope")))
        assertEquals(
            AssetAvailability.Unknown(AssetId("nope")),
            registry.validate(AssetId("nope")),
        )
    }

    @Test
    fun `a registry entry is not a content hash`() {
        // Documents the type-level guard: AssetId and AssetHash cannot be interchanged, and
        // an id-shaped string is not a valid hash.
        assertTrue(runCatching { AssetHash("asset-1") }.isFailure)
    }
}
