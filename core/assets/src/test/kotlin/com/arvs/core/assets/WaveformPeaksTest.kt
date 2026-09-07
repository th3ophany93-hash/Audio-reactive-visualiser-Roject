package com.arvs.core.assets

import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.PcmSource
import com.arvs.core.time.TimeSpan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A PcmSource over an in-memory mono signal, counting how often it is read. */
internal class FakePcmSource(
    private val samples: FloatArray,
    sampleRateHz: Int = 48_000,
) : PcmSource {
    var readCount: Int = 0
        private set

    override val format = AudioFormatInfo(
        sampleRateHz = sampleRateHz,
        channelCount = 1,
        duration = TimeSpan(samples.size.toLong() * 1_000_000L / sampleRateHz),
    )

    override val totalFrames: Long = samples.size.toLong()

    override fun readMono(startFrame: Long, frameCount: Int): FloatArray {
        readCount++
        if (startFrame >= totalFrames || frameCount <= 0) return FloatArray(0)
        val available = minOf(frameCount.toLong(), totalFrames - startFrame).toInt()
        return samples.copyOfRange(startFrame.toInt(), startFrame.toInt() + available)
    }
}

/** Pins the §82.1 tier-2 pyramid and the §16 zoom behaviour it exists to serve. */
class WaveformPeaksTest {

    private fun sine(frames: Int, hz: Double = 440.0, amplitude: Float = 0.8f) =
        FloatArray(frames) { n -> (amplitude * sin(2.0 * PI * hz * n / 48_000)).toFloat() }

    @Test
    fun `the base level reduces exactly framesPerBucket frames per bucket`() = runTest {
        val builder = PeakBuilder(baseFramesPerBucket = 100, additionalLevels = 0, readChunkFrames = 1_000)
        val peaks = builder.build(FakePcmSource(FloatArray(1_000)))

        assertEquals(100, peaks.baseLevel.framesPerBucket)
        assertEquals(10, peaks.baseLevel.bucketCount)
    }

    @Test
    fun `a partial final bucket is kept, not dropped`() = runTest {
        // Dropping it would truncate the waveform short of the audio, so the trim out-point
        // could not reach the end of the track.
        val builder = PeakBuilder(baseFramesPerBucket = 100, additionalLevels = 0, readChunkFrames = 1_000)
        val peaks = builder.build(FakePcmSource(FloatArray(1_050)))

        assertEquals(11, peaks.baseLevel.bucketCount)
    }

    @Test
    fun `buckets record both the minimum and the maximum`() = runTest {
        // A waveform drawn from abs alone is mirror-symmetric by construction, which erases
        // the asymmetry of real percussive material: plausible-looking and wrong.
        val asymmetric = FloatArray(200) { if (it < 100) 0.9f else -0.2f }
        val peaks = PeakBuilder(baseFramesPerBucket = 100, additionalLevels = 0, readChunkFrames = 200)
            .build(FakePcmSource(asymmetric))

        assertEquals(0.9f, peaks.baseLevel.maximumAt(0))
        assertEquals(0.9f, peaks.baseLevel.minimumAt(0))
        assertEquals(-0.2f, peaks.baseLevel.maximumAt(1))
        assertEquals(-0.2f, peaks.baseLevel.minimumAt(1))
    }

    @Test
    fun `coarser levels are exact folds of the level below`() = runTest {
        // min/max is associative, so folding loses nothing a second pass would have found.
        val peaks = PeakBuilder(baseFramesPerBucket = 10, additionalLevels = 3, readChunkFrames = 1_000)
            .build(FakePcmSource(sine(1_000)))

        val base = peaks.levels[0]
        val second = peaks.levels[1]
        assertEquals(20, second.framesPerBucket)
        for (index in 0 until second.bucketCount) {
            val left = index * 2
            val right = left + 1
            val expectedMax = if (right < base.bucketCount) maxOf(base.maximumAt(left), base.maximumAt(right))
            else base.maximumAt(left)
            assertEquals(expectedMax, second.maximumAt(index))
        }
    }

    @Test
    fun `the pyramid coarsens by powers of two`() = runTest {
        val peaks = PeakBuilder(baseFramesPerBucket = 256, additionalLevels = 4, readChunkFrames = 65_536)
            .build(FakePcmSource(FloatArray(100_000)))

        assertEquals(listOf(256, 512, 1_024, 2_048, 4_096), peaks.levels.map { it.framesPerBucket })
    }

    @Test
    fun `the source is read once, not once per level`() = runTest {
        // Re-reading per level would multiply the cost of §17.1's near-instant stage 1 for no
        // gain, since a min/max fold is exact under composition.
        val source = FakePcmSource(FloatArray(65_536))
        PeakBuilder(baseFramesPerBucket = 256, additionalLevels = 9, readChunkFrames = 65_536).build(source)

        assertEquals(1, source.readCount)
    }

    // --- §16 zoom behaviour ---------------------------------------------------------------

    @Test
    fun `zooming out picks a coarser level, so redraw cost stays flat`() = runTest {
        val peaks = PeakBuilder(baseFramesPerBucket = 256, additionalLevels = 9, readChunkFrames = 65_536)
            .build(FakePcmSource(FloatArray(14_400_000))) // five minutes at 48 kHz

        val zoomedIn = peaks.levelFor(frameCount = 48_000, minimumBuckets = 500)      // one second
        val zoomedOut = peaks.levelFor(frameCount = 14_400_000, minimumBuckets = 500) // whole track

        assertTrue(zoomedOut.framesPerBucket > zoomedIn.framesPerBucket)
        assertEquals(256, zoomedIn.framesPerBucket)
    }

    @Test
    fun `a redraw returns no more buckets than the display has columns`() = runTest {
        val peaks = PeakBuilder(baseFramesPerBucket = 256, additionalLevels = 9, readChunkFrames = 65_536)
            .build(FakePcmSource(FloatArray(14_400_000)))

        listOf(100, 480, 1_080).forEach { columns ->
            val buckets = peaks.bucketsFor(0, peaks.totalFrames, maxBuckets = columns)
            assertTrue("got ${buckets.size} for $columns columns", buckets.size <= columns)
            assertTrue(buckets.isNotEmpty())
        }
    }

    @Test
    fun `merging to fit preserves transients instead of sampling past them`() = runTest {
        // Taking every Nth bucket would drop the loudest column, which is the one a waveform
        // most needs to show.
        val samples = FloatArray(10_000)
        samples[7_777] = 1.0f
        val peaks = PeakBuilder(baseFramesPerBucket = 10, additionalLevels = 0, readChunkFrames = 10_000)
            .build(FakePcmSource(samples))

        val buckets = peaks.bucketsFor(0, 10_000, maxBuckets = 20)
        assertEquals(1.0f, buckets.maxOf { it.maximum })
    }

    @Test
    fun `a sub-range returns only that range`() = runTest {
        val samples = FloatArray(10_000)
        for (index in 5_000 until 6_000) samples[index] = 1.0f
        val peaks = PeakBuilder(baseFramesPerBucket = 100, additionalLevels = 0, readChunkFrames = 10_000)
            .build(FakePcmSource(samples))

        val quiet = peaks.bucketsFor(0, 4_000, maxBuckets = 100)
        val loud = peaks.bucketsFor(5_000, 6_000, maxBuckets = 100)

        assertTrue(quiet.all { abs(it.magnitude) < 1e-6f })
        assertTrue(loud.all { it.maximum == 1.0f })
    }

    @Test
    fun `out-of-range and empty requests are clamped rather than throwing`() = runTest {
        val peaks = PeakBuilder(baseFramesPerBucket = 100, additionalLevels = 0, readChunkFrames = 1_000)
            .build(FakePcmSource(FloatArray(1_000)))

        assertTrue(peaks.bucketsFor(-500, 500, 50).isNotEmpty())
        assertTrue(peaks.bucketsFor(0, 999_999, 50).isNotEmpty())
        assertTrue(peaks.bucketsFor(500, 500, 50).isEmpty())
        assertTrue(peaks.bucketsFor(2_000, 3_000, 50).isEmpty())
    }

    @Test
    fun `an empty source produces an empty but valid pyramid`() = runTest {
        val peaks = PeakBuilder(baseFramesPerBucket = 100, additionalLevels = 2, readChunkFrames = 1_000)
            .build(FakePcmSource(FloatArray(0)))

        assertEquals(0L, peaks.totalFrames)
        assertEquals(0, peaks.baseLevel.bucketCount)
        assertTrue(peaks.bucketsFor(0, 0, 10).isEmpty())
    }

    @Test
    fun `levels must coarsen strictly`() {
        assertThrows(IllegalArgumentException::class.java) {
            WaveformPeaks(48_000, 100, listOf(PeakLevel(10, FloatArray(1), FloatArray(1)), PeakLevel(10, FloatArray(1), FloatArray(1))))
        }
    }

    @Test
    fun `a read chunk smaller than a bucket is rejected at construction`() {
        // A bucket spanning two reads would silently split, halving the effective resolution.
        assertThrows(IllegalArgumentException::class.java) {
            PeakBuilder(baseFramesPerBucket = 256, readChunkFrames = 128)
        }
    }
}
