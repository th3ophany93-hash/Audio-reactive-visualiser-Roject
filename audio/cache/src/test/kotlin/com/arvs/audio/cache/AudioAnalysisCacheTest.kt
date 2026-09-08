package com.arvs.audio.cache

import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.LoggingPolicy
import com.arvs.core.diagnostics.ManualDiagnosticsClock
import com.arvs.core.diagnostics.RecordingLogSink
import com.arvs.core.model.AnalysisCacheKey
import com.arvs.core.model.AnalysisConfigHash
import com.arvs.core.model.AnalysisStage
import com.arvs.core.model.AssetHash
import com.arvs.core.model.FeatureId
import com.arvs.core.time.AnalysisFraming
import com.arvs.core.time.AudioSourceTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File

/** Pins §18.1's concurrency contract, §18.3's format and budget rules, and §17.7's publication model. */
class AudioAnalysisCacheTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val sink = RecordingLogSink(capacity = 256)
    private val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }

    private fun key(asset: String = "a", config: String = "c") =
        AnalysisCacheKey(AssetHash(asset.repeat(64)), AnalysisConfigHash(config.repeat(64)))

    private fun cache(budget: CacheBudget = CacheBudget.DEFAULT, pinned: () -> Set<AssetHash> = { emptySet() }) =
        AudioAnalysisCache(temporaryFolder.root, logger, budget, pinned)

    private fun entry(
        key: AnalysisCacheKey = key(),
        frames: Long = 100,
        reference: Float = 4.0f,
    ) = AnalysisCacheEntry(
        key = key,
        framing = AnalysisFraming.CANONICAL,
        frameCount = frames,
        trackPeakEnergy = reference,
        sourceSampleRateHz = 44_100,
        sourceChannelCount = 2,
    )

    private fun stagedEntry(key: AnalysisCacheKey = key(), frames: Long = 100): AnalysisCacheEntry {
        val entry = entry(key, frames)
        entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.RMS, FloatArray(frames.toInt()) { it * 0.01f })
        entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.PEAK, FloatArray(frames.toInt()) { it * 0.02f })
        return entry
    }

    // --- §17.7 clause 3: nothing is visible before publication ---------------------------

    @Test
    fun `an unknown key reads NotAnalyzed, not Pending`() {
        // The distinction matters: Pending carries a nearest-sample fallback, and here there is
        // no sample to fall back to.
        assertEquals(
            FeatureRead.NotAnalyzed,
            cache().read(key(), FeatureId.RMS, AudioSourceTime.ofSeconds(0.5)),
        )
    }

    @Test
    fun `a staged but unpublished stage reads NotAnalyzed`() {
        val staged = stagedEntry()
        assertEquals(FeatureRead.NotAnalyzed, staged.read(FeatureId.RMS, AudioSourceTime(0)))

        staged.publishAtomic(AnalysisStage.SCALAR_ENVELOPE)
        assertTrue(staged.read(FeatureId.RMS, AudioSourceTime(0)) is FeatureRead.Available)
    }

    @Test
    fun `persisting an entry with no published stage is refused`() {
        // Otherwise a later run would load a partial file as authoritative — the durable form of
        // the partial visibility §17.7 clause 3 forbids.
        assertThrows(IllegalArgumentException::class.java) { cache().persist(stagedEntry()) }
        assertEquals(0, temporaryFolder.root.walkTopDown().count { it.isFile })
    }

    // --- §17.7 clause 5: the per-stage marker ---------------------------------------------

    @Test
    fun `an atomic stage's marker takes exactly two values`() {
        val published = stagedEntry(frames = 100)
        assertEquals(StageProgress.NOT_PUBLISHED, published.progressOf(AnalysisStage.SCALAR_ENVELOPE).highestCompleteIndex)

        published.publishAtomic(AnalysisStage.SCALAR_ENVELOPE)
        assertEquals(99L, published.progressOf(AnalysisStage.SCALAR_ENVELOPE).highestCompleteIndex)
        assertTrue(published.progressOf(AnalysisStage.SCALAR_ENVELOPE).isComplete)
    }

    @Test
    fun `an intermediate marker for an atomic stage is unrepresentable`() {
        // §17.7 clause 5 as a type invariant, not a convention.
        assertThrows(IllegalArgumentException::class.java) {
            StageProgress(AnalysisStage.SCALAR_ENVELOPE, highestCompleteIndex = 50, frameCount = 100)
        }
        StageProgress(AnalysisStage.SCALAR_ENVELOPE, StageProgress.NOT_PUBLISHED, 100)
        StageProgress(AnalysisStage.SCALAR_ENVELOPE, 99, 100)
    }

    @Test
    fun `an atomic stage cannot be advanced incrementally`() {
        assertThrows(IllegalArgumentException::class.java) {
            stagedEntry().advanceTo(AnalysisStage.SCALAR_ENVELOPE, 50)
        }
    }

    @Test
    fun `an incremental stage advances monotonically and never rewinds`() {
        val incremental = entry(frames = 100)
        incremental.stageFeature(AnalysisStage.SPECTRUM, FeatureId.SPECTRAL_CENTROID, FloatArray(100))

        incremental.advanceTo(AnalysisStage.SPECTRUM, 10)
        incremental.advanceTo(AnalysisStage.SPECTRUM, 40)
        assertEquals(40L, incremental.progressOf(AnalysisStage.SPECTRUM).highestCompleteIndex)

        assertThrows(IllegalArgumentException::class.java) { incremental.advanceTo(AnalysisStage.SPECTRUM, 20) }
    }

    @Test
    fun `an incremental stage reads Pending past its marker, with the nearest sample`() {
        // §18.1's fallback. Only reachable for a non-atomic stage — see the next test.
        val incremental = entry(frames = 100)
        incremental.stageFeature(AnalysisStage.SPECTRUM, FeatureId.SPECTRAL_CENTROID, FloatArray(100) { it.toFloat() })
        incremental.advanceTo(AnalysisStage.SPECTRUM, 40)

        val available = incremental.read(FeatureId.SPECTRAL_CENTROID, AudioSourceTime(200_000)) // frame 20
        assertEquals(20.0f, (available as FeatureRead.Available).value, 1e-4f)

        val pending = incremental.read(FeatureId.SPECTRAL_CENTROID, AudioSourceTime(800_000)) // frame 80
        assertEquals(40L, (pending as FeatureRead.Pending).nearestFrame)
        assertEquals(40.0f, pending.nearestValue, 1e-4f)
    }

    @Test
    fun `an atomic stage can never read Pending`() {
        // A consequence of §17.7: an atomic stage is either unpublished (NotAnalyzed) or wholly
        // published (Available). There is no window in which part of it exists.
        val published = stagedEntry(frames = 100).also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) }

        (0..200).forEach { tenth ->
            val read = published.read(FeatureId.RMS, AudioSourceTime(tenth * 10_000L))
            assertTrue("read at frame $tenth was $read", read !is FeatureRead.Pending)
        }
    }

    // --- §17.7 clause 7: no published scalar is revised ------------------------------------

    @Test
    fun `a published stage cannot be republished or restaged`() {
        val published = stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) }

        assertThrows(IllegalStateException::class.java) { published.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) }
        assertThrows(IllegalStateException::class.java) {
            published.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.RMS, FloatArray(100))
        }
    }

    // --- round-trip and the immutable reference ---------------------------------------------

    @Test
    fun `an entry round-trips through disk, reference included`() {
        val original = stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) }
        val store = cache()
        store.persist(original)

        val reloaded = AudioAnalysisCache(temporaryFolder.root, logger).entry(key())!!

        assertEquals(original.frameCount, reloaded.frameCount)
        assertEquals(original.trackPeakEnergy, reloaded.trackPeakEnergy)   // §17.7 immutable metadata
        assertEquals(44_100, reloaded.sourceSampleRateHz)                  // §17.3 source preserved
        assertEquals(2, reloaded.sourceChannelCount)
        assertEquals(
            original.progressOf(AnalysisStage.SCALAR_ENVELOPE).highestCompleteIndex,
            reloaded.progressOf(AnalysisStage.SCALAR_ENVELOPE).highestCompleteIndex,
        )
        (0 until 100).forEach { frame ->
            assertEquals(
                original.read(FeatureId.RMS, AudioSourceTime(frame * 10_000L)),
                reloaded.read(FeatureId.RMS, AudioSourceTime(frame * 10_000L)),
            )
        }
    }

    @Test
    fun `a cache hit equals a cache miss exactly`() {
        // The plan's cache suite: values read back from disk must equal the values as computed.
        val original = stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) }
        cache().persist(original)
        val reloaded = AudioAnalysisCache(temporaryFolder.root, logger).entry(key())!!

        (0 until 100).forEach { frame ->
            val time = AudioSourceTime(frame * 10_000L + 3_333)
            assertEquals(
                (original.read(FeatureId.RMS, time) as FeatureRead.Available).value,
                (reloaded.read(FeatureId.RMS, time) as FeatureRead.Available).value,
            )
        }
    }

    // --- §18.3 format discipline --------------------------------------------------------------

    @Test
    fun `an unknown format version is rejected, deleted and regenerated`() {
        val store = cache()
        store.persist(stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) })
        val file = store.fileFor(key())
        writeHeader(file, AnalysisCacheFormat.MAGIC, version = 99)

        val fresh = AudioAnalysisCache(temporaryFolder.root, logger)
        assertNull(fresh.entry(key()))
        assertTrue("the rejected file must be deleted", !file.exists())
        assertTrue(sink.snapshot().any { it.key == "cache-format-rejected" })
    }

    @Test
    fun `the format version is pinned at its ratified value`() {
        // Both sides of a round-trip use the same constant, so changing it is invisible to every
        // other test in this file — a mutation lowering it back to 1 for the version-2 layout
        // passed until this assertion existed. §18.3 makes the version the mechanism by which a
        // layout change invalidates existing entries; it has to be asserted directly.
        assertEquals(2, AnalysisCacheFormat.FORMAT_VERSION)
    }

    @Test
    fun `a foreign magic is rejected without parsing`() {
        val store = cache()
        store.persist(stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) })
        writeHeader(store.fileFor(key()), magic = 0x0123456789ABCDEFL, version = 1)

        assertNull(AudioAnalysisCache(temporaryFolder.root, logger).entry(key()))
    }

    @Test
    fun `a truncated file is discarded rather than half-read`() {
        val store = cache()
        store.persist(stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) })
        val file = store.fileFor(key())
        val full = file.readBytes()
        file.writeBytes(full.copyOf(full.size / 3))

        assertNull(AudioAnalysisCache(temporaryFolder.root, logger).entry(key()))
        assertTrue(!file.exists())
    }

    @Test
    fun `a file claiming an intermediate marker for an atomic stage is rejected`() {
        // Defence in depth: §17.7 clause 5 is enforced on read as well as on write, so a file
        // produced by a different writer cannot smuggle in a state the format forbids.
        val store = cache()
        val original = stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) }
        store.persist(original)
        val bytes = store.fileFor(key()).readBytes()
        // The marker is the long following the stage name; corrupt it to a mid-track value.
        val marker = bytes.indexOfLong(99L)
        assertTrue("marker not found in the file", marker >= 0)
        writeLongAt(bytes, marker, 50L)
        store.fileFor(key()).writeBytes(bytes)

        assertNull(AudioAnalysisCache(temporaryFolder.root, logger).entry(key()))
    }

    // --- atomicity -----------------------------------------------------------------------------

    @Test
    fun `no temporary file survives a completed publish`() {
        cache().persist(stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) })
        assertTrue(temporaryFolder.root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a reader never observes a half-written entry during a write storm`() {
        // §17.7 clause 6: publication is temp-file-plus-rename, so a concurrent reader sees the
        // previous complete entry or none — never a partial one.
        val store = cache()
        val published = stagedEntry(frames = 2_000).also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) }
        store.persist(published)
        sink.clear()

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = Thread {
            while (!stop.get()) {
                AudioAnalysisCache(temporaryFolder.root, logger).entry(key())
                reads.incrementAndGet()
            }
        }
        reader.start()
        try {
            repeat(30) { store.persist(published) }
        } finally {
            stop.set(true)
            reader.join(10_000)
        }

        assertTrue(reads.get() > 0)
        val corruptions = sink.snapshot().filter {
            it.key == "cache-format-rejected" || it.key == "cache-read-failed"
        }
        assertTrue("a partial entry became readable: $corruptions", corruptions.isEmpty())
        assertNotNull(AudioAnalysisCache(temporaryFolder.root, logger).entry(key()))
    }

    // --- §18.3 budget and eviction ---------------------------------------------------------------

    @Test
    fun `the budget defaults and bounds follow section 18_3`() {
        assertEquals(1024L * 1024 * 1024, CacheBudget.DEFAULT.bytes)
        assertThrows(IllegalArgumentException::class.java) { CacheBudget(255L * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) { CacheBudget(9L * 1024 * 1024 * 1024) }
        CacheBudget(CacheBudget.MINIMUM_BYTES)
        CacheBudget(CacheBudget.MAXIMUM_BYTES)
    }

    @Test
    fun `eviction removes whole entries, least recently used first`() {
        val store = cache()
        val keys = listOf("a", "b", "c").map { key(asset = it) }
        keys.forEachIndexed { index, entryKey ->
            store.persist(
                stagedEntry(entryKey, frames = 4_000).also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) },
            )
            store.fileFor(entryKey).setLastModified(1_000_000L + index * 10_000L)
        }
        val entrySize = store.fileFor(keys.first()).length()

        // Room for two of the three.
        val evicted = store.evictToFit(entrySize * 2 + 1)

        assertEquals(1, evicted.size)
        assertEquals(keys.first(), evicted.single())        // least recently accessed goes first
        assertTrue("evicted entries go whole", !store.fileFor(keys.first()).exists())
        assertTrue(store.fileFor(keys[1]).exists())
        assertTrue(store.fileFor(keys[2]).exists())
    }

    @Test
    fun `eviction never leaves a partial entry behind`() {
        // §18.3: "Partial eviction is forbidden: a half-present entry would violate §18.1's
        // immutability and completeness contract." Every survivor must still load whole.
        val store = cache()
        val keys = listOf("a", "b", "c", "d").map { key(asset = it) }
        keys.forEachIndexed { index, entryKey ->
            store.persist(
                stagedEntry(entryKey, frames = 3_000).also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) },
            )
            store.fileFor(entryKey).setLastModified(1_000_000L + index * 10_000L)
        }

        store.evictToFit(store.fileFor(keys.first()).length() * 2)

        val fresh = AudioAnalysisCache(temporaryFolder.root, logger)
        keys.filter { store.fileFor(it).exists() }.forEach { survivor ->
            val reloaded = fresh.entry(survivor)
            assertNotNull("survivor $survivor must load whole", reloaded)
            assertEquals(3_000L, reloaded!!.frameCount)
        }
    }

    @Test
    fun `an asset pinned by an open project is never evicted`() {
        // §18.3: "The entry for any audio asset referenced by a currently-open project is never
        // evicted while that project is open."
        val pinnedKey = key(asset = "a")
        val otherKey = key(asset = "b")
        val store = cache(pinned = { setOf(pinnedKey.assetHash) })
        listOf(pinnedKey, otherKey).forEach { entryKey ->
            store.persist(
                stagedEntry(entryKey, frames = 3_000).also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) },
            )
        }
        store.fileFor(pinnedKey).setLastModified(1_000L)        // by far the least recently used
        store.fileFor(otherKey).setLastModified(9_000_000L)

        val evicted = store.evictToFit(0)

        assertTrue("the pinned entry must survive", store.fileFor(pinnedKey).exists())
        assertTrue(evicted.none { it.assetHash == pinnedKey.assetHash })
        assertTrue("the unpinned entry should have gone", !store.fileFor(otherKey).exists())
    }

    @Test
    fun `nothing is evicted while within budget`() {
        val store = cache()
        store.persist(stagedEntry().also { it.publishAtomic(AnalysisStage.SCALAR_ENVELOPE) })
        assertTrue(store.evictIfOverBudget().isEmpty())
        assertTrue(store.fileFor(key()).exists())
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun writeHeader(file: File, magic: Long, version: Int) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeLong(magic)
            out.writeInt(version)
            out.writeUTF("a".repeat(64))
            out.writeUTF("c".repeat(64))
        }
    }

    private fun ByteArray.indexOfLong(value: Long): Int {
        for (index in 0..size - 8) {
            var candidate = 0L
            for (offset in 0 until 8) candidate = (candidate shl 8) or (this[index + offset].toLong() and 0xFF)
            if (candidate == value) return index
        }
        return -1
    }

    private fun writeLongAt(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) bytes[offset + index] = ((value shr (56 - index * 8)) and 0xFF).toByte()
    }
}
