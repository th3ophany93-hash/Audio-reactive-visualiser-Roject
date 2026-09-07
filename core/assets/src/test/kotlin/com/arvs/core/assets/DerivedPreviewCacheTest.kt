package com.arvs.core.assets

import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.LoggingPolicy
import com.arvs.core.diagnostics.ManualDiagnosticsClock
import com.arvs.core.diagnostics.RecordingLogSink
import com.arvs.core.model.AssetHash
import com.arvs.core.model.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pins §82.1's tier-2 cache, including the §16 rule it exists to satisfy.
 */
class DerivedPreviewCacheTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val sink = RecordingLogSink(capacity = 256)
    private val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }

    private val hashA = AssetHash("a".repeat(64))
    private val hashB = AssetHash("b".repeat(64))

    private fun cache(builder: PeakBuilder = PeakBuilder(256, 4, 65_536)) =
        DerivedPreviewCache(temporaryFolder.root, logger, builder)

    private fun signal(frames: Int) =
        FloatArray(frames) { n -> (0.8 * sin(2.0 * PI * 440.0 * n / 48_000)).toFloat() }

    // --- §16's binding requirement --------------------------------------------------------

    @Test
    fun `the redraw path reads the cache and cannot reach the source asset`() = runTest {
        // §16: "immediate visual feedback" comes from the derived preview cache, never by
        // re-opening the asset per redraw or scrub frame — an implementation that re-decodes
        // on every zoom "does not meet this requirement".
        //
        // Enforced by construction: cachedPeaks() holds no AssetStorage and no PcmSource, so
        // it has nothing to re-open. This asserts the observable consequence — a source that
        // would record any read is never touched.
        val source = FakePcmSource(signal(200_000))
        val cache = cache()
        cache.peaksFor(hashA, source)
        val readsAfterImport = source.readCount

        repeat(200) { frame ->
            val peaks = cache.cachedPeaks(hashA)!!
            peaks.bucketsFor(frame * 100L, frame * 100L + 48_000, maxBuckets = 480)
        }

        assertEquals("no scrub frame may re-read the source", readsAfterImport, source.readCount)
    }

    @Test
    fun `cachedPeaks returns null for an unknown asset rather than computing`() {
        // Computing here would put a decode on a scrub frame.
        assertNull(cache().cachedPeaks(hashA))
    }

    // --- round-trip and identity ----------------------------------------------------------

    @Test
    fun `peaks round-trip through disk unchanged`() = runTest {
        val cache = cache()
        val built = (cache.peaksFor(hashA, FakePcmSource(signal(100_000))) as Outcome.Success).value

        val loaded = cache.cachedPeaks(hashA)!!

        assertEquals(built.sampleRateHz, loaded.sampleRateHz)
        assertEquals(built.totalFrames, loaded.totalFrames)
        assertEquals(built.levels.size, loaded.levels.size)
        built.levels.indices.forEach { levelIndex ->
            val original = built.levels[levelIndex]
            val restored = loaded.levels[levelIndex]
            assertEquals(original.framesPerBucket, restored.framesPerBucket)
            assertEquals(original.bucketCount, restored.bucketCount)
            for (bucket in 0 until original.bucketCount) {
                assertEquals(original.minimumAt(bucket), restored.minimumAt(bucket))
                assertEquals(original.maximumAt(bucket), restored.maximumAt(bucket))
            }
        }
    }

    @Test
    fun `a second request is served from disk without rebuilding`() = runTest {
        val source = FakePcmSource(signal(100_000))
        val cache = cache()
        cache.peaksFor(hashA, source)
        val readsAfterFirst = source.readCount

        cache.peaksFor(hashA, source)

        assertEquals(readsAfterFirst, source.readCount)
    }

    @Test
    fun `different assets get different cache entries`() = runTest {
        val cache = cache()
        cache.peaksFor(hashA, FakePcmSource(signal(50_000)))
        cache.peaksFor(hashB, FakePcmSource(FloatArray(50_000)))

        val a = cache.cachedPeaks(hashA)!!
        val b = cache.cachedPeaks(hashB)!!
        assertNotEquals(a.baseLevel.maximumAt(0), b.baseLevel.maximumAt(0))
    }

    @Test
    fun `the cache path is keyed by asset hash and peak format version`() {
        // §82.1's tier-2 invalidation rule: new asset or new peak format produces a new key.
        val file = cache().fileFor(hashA)
        assertTrue(file.path.contains(hashA.hex))
        assertTrue(file.name.startsWith("${DerivedPreviewCache.PEAK_FORMAT_VERSION}."))
    }

    // --- §18.3's format discipline, applied to tier 2 -------------------------------------

    @Test
    fun `an unknown format version is rejected, deleted and regenerated`() = runTest {
        // §18.3: reject outright, delete, regenerate — never a partial or best-effort parse.
        // A misparsed pyramid draws a confidently wrong waveform.
        val cache = cache()
        cache.peaksFor(hashA, FakePcmSource(signal(50_000)))
        val file = cache.fileFor(hashA)

        writeHeaderOnly(file, magic = PeakCacheFormat.MAGIC, version = 999)

        assertNull(cache.cachedPeaks(hashA))
        assertTrue("the rejected file must be deleted", !file.exists())
        assertTrue(sink.snapshot().any { it.key == "peaks-format-rejected" })

        // ...and the next import regenerates it.
        assertTrue(cache.peaksFor(hashA, FakePcmSource(signal(50_000))) is Outcome.Success)
        assertTrue(cache.cachedPeaks(hashA) != null)
    }

    @Test
    fun `a foreign file is rejected on its magic, not parsed`() = runTest {
        val cache = cache()
        cache.peaksFor(hashA, FakePcmSource(signal(10_000)))
        val file = cache.fileFor(hashA)
        writeHeaderOnly(file, magic = 0x0123456789ABCDEFL, version = PeakCacheFormat.PEAK_FORMAT_VERSION)

        assertNull(cache.cachedPeaks(hashA))
        assertTrue(!file.exists())
    }

    @Test
    fun `a truncated file is discarded rather than half-read`() = runTest {
        val cache = cache()
        cache.peaksFor(hashA, FakePcmSource(signal(50_000)))
        val file = cache.fileFor(hashA)
        val full = file.readBytes()
        file.writeBytes(full.copyOf(full.size / 3))

        assertNull(cache.cachedPeaks(hashA))
        assertTrue(!file.exists())
    }

    @Test
    fun `an implausible level count is rejected without allocating`() = runTest {
        val cache = cache()
        cache.peaksFor(hashA, FakePcmSource(signal(10_000)))
        val file = cache.fileFor(hashA)
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeLong(PeakCacheFormat.MAGIC)
            out.writeInt(PeakCacheFormat.PEAK_FORMAT_VERSION)
            out.writeInt(48_000)
            out.writeLong(10_000)
            out.writeInt(Int.MAX_VALUE) // level count
        }

        assertNull(cache.cachedPeaks(hashA))
    }

    // --- atomicity -------------------------------------------------------------------------

    @Test
    fun `no temporary file survives a completed write`() = runTest {
        val cache = cache()
        cache.peaksFor(hashA, FakePcmSource(signal(50_000)))

        val leftovers = temporaryFolder.root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertTrue("temporary files left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `a reader never observes a half-written file while a write is in flight`() {
        // This is what atomicity actually means here, and it is the assertion the previous
        // version of this suite was missing: checking only that no .tmp file is left behind
        // says nothing, because a rename moves the temporary away on the success path too.
        //
        // Writing straight to the target would leave a partially serialised pyramid readable
        // for the duration of every write. The reader below would then parse a truncated file
        // and log a rejection — so the absence of any rejection is the evidence.
        val cache = cache(PeakBuilder(64, 6, 65_536))
        val peaks = kotlinx.coroutines.runBlocking {
            PeakBuilder(64, 6, 65_536).build(FakePcmSource(signal(500_000)))
        }
        cache.store(hashA, peaks)
        sink.clear()

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val readsCompleted = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = Thread {
            while (!stop.get()) {
                cache.cachedPeaks(hashA)
                readsCompleted.incrementAndGet()
            }
        }
        reader.start()
        try {
            repeat(40) { cache.store(hashA, peaks) }
        } finally {
            stop.set(true)
            reader.join(10_000)
        }

        assertTrue("the reader should have run", readsCompleted.get() > 0)
        val corruptions = sink.snapshot().filter {
            it.key == "peaks-format-rejected" || it.key == "peaks-read-failed"
        }
        assertTrue("a partial file became readable: $corruptions", corruptions.isEmpty())
        assertTrue("the entry must survive the write storm", cache.cachedPeaks(hashA) != null)
    }

    @Test
    fun `replacing an existing entry leaves exactly one readable file`() = runTest {
        val cache = cache()
        cache.peaksFor(hashA, FakePcmSource(signal(50_000)))
        cache.store(hashA, PeakBuilder(256, 4, 65_536).build(FakePcmSource(FloatArray(50_000))))

        val files = File(temporaryFolder.root, hashA.hex).listFiles()!!.filter { it.isFile }
        assertEquals(1, files.size)
        // The replacement won: a silent all-zero signal.
        assertEquals(0.0f, cache.cachedPeaks(hashA)!!.baseLevel.maximumAt(0))
    }

    // --- lifecycle --------------------------------------------------------------------------

    @Test
    fun `invalidate drops the entry and the next request rebuilds it`() = runTest {
        val source = FakePcmSource(signal(50_000))
        val cache = cache()
        cache.peaksFor(hashA, source)
        val readsAfterFirst = source.readCount

        cache.invalidate(hashA)
        assertNull(cache.cachedPeaks(hashA))

        cache.peaksFor(hashA, source)
        assertTrue(source.readCount > readsAfterFirst)
    }

    @Test
    fun `size on disk is reported for the diagnostic report`() = runTest {
        val cache = cache()
        assertEquals(0L, cache.sizeOnDiskBytes())
        cache.peaksFor(hashA, FakePcmSource(signal(200_000)))
        assertTrue(cache.sizeOnDiskBytes() > 0)
    }

    private fun writeHeaderOnly(file: File, magic: Long, version: Int) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeLong(magic)
            out.writeInt(version)
            out.writeInt(48_000)
            out.writeLong(1_000)
            out.writeInt(1)
        }
    }
}
