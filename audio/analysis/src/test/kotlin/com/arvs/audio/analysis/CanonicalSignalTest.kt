package com.arvs.audio.analysis

import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.PcmBuffer
import com.arvs.core.time.TimeSpan
import com.arvs.testing.audio.Fixtures
import com.arvs.testing.audio.SignalGenerators
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pins §17.3's canonical analysis signal: mono, 48 kHz, and never a substitute for the source. */
class CanonicalSignalTest {

    private fun format(rate: Int, channels: Int, frames: Int) =
        AudioFormatInfo(rate, channels, TimeSpan(frames.toLong() * 1_000_000L / rate))

    // --- §17.3 pass-through -----------------------------------------------------------------

    @Test
    fun `a source already at forty-eight kilohertz passes through bit-exact`() {
        // §17.3: "A source already at 48 kHz is passed through bit-exact, with no resampling
        // stage applied." Not "approximately equal" — identical.
        val samples = Fixtures.whiteNoise(4_800).samples
        val buffer = PcmBuffer(samples.copyOf(), 1, 48_000, 0)

        val canonical = CanonicalSignal.from(buffer, format(48_000, 1, 4_800))

        assertArrayEquals(samples, canonical.samples, 0.0f)
        assertFalse(canonical.wasResampled)
    }

    @Test
    fun `resampling is skipped entirely at the canonical rate`() {
        // The array itself is returned, so not a single sample passes through the filter.
        val samples = FloatArray(1_000) { it * 0.001f }
        assertTrue(samples === Resampler.resample(samples, 48_000, 48_000))
    }

    // --- §17.3 mono downmix ------------------------------------------------------------------

    @Test
    fun `stereo is downmixed to half left plus half right`() {
        val stereo = Fixtures.stereo(4_800)
        val canonical = CanonicalSignal.from(stereo.toPcmBuffer(), stereo.format)

        assertEquals(4_800, canonical.frameCount)
        (0 until 4_800).forEach { frame ->
            val expected = 0.5f * stereo.samples[frame * 2] + 0.5f * stereo.samples[frame * 2 + 1]
            assertEquals("frame $frame", expected, canonical.samples[frame], 1e-7f)
        }
    }

    @Test
    fun `the source format is preserved, not overwritten by the canonical one`() {
        // §17.3: "The original channel count, channel layout, and sample rate are preserved."
        val stereo44k = PcmBuffer(FloatArray(8_820), 2, 44_100, 0)
        val canonical = CanonicalSignal.from(stereo44k, format(44_100, 2, 4_410))

        assertEquals(44_100, canonical.sourceFormat.sampleRateHz)
        assertEquals(2, canonical.sourceFormat.channelCount)
        assertEquals(48_000, canonical.sampleRateHz)
        assertTrue(canonical.wasResampled)
    }

    // --- resampling correctness ---------------------------------------------------------------

    @Test
    fun `resampling produces the expected number of samples`() {
        assertEquals(48_000, Resampler.resample(FloatArray(44_100), 44_100, 48_000).size)
        assertEquals(44_100, Resampler.resample(FloatArray(48_000), 48_000, 44_100).size)
        assertEquals(96_000, Resampler.resample(FloatArray(48_000), 24_000, 48_000).size)
    }

    @Test
    fun `resampling preserves a tone's frequency`() {
        // The property that matters: a 1 kHz tone at 44.1 kHz must still be 1 kHz at 48 kHz.
        val source = SignalGenerators.sine(44_100, 1_000.0, 44_100, amplitude = 0.5f)
        val resampled = Resampler.resample(source, 44_100, 48_000)

        assertEquals(1_000.0, dominantFrequency(resampled, 48_000, 900.0, 1_100.0), 3.0)
    }

    @Test
    fun `resampling preserves amplitude`() {
        val source = SignalGenerators.sine(44_100, 1_000.0, 44_100, amplitude = 0.5f)
        val resampled = Resampler.resample(source, 44_100, 48_000)

        // Interior only: the very edges are shaped by the partial kernel.
        val interior = resampled.copyOfRange(1_000, resampled.size - 1_000)
        val rms = sqrt(interior.sumOf { it.toDouble() * it } / interior.size)
        assertEquals(0.5 / sqrt(2.0), rms, 5e-3)
    }

    @Test
    fun `a constant signal stays constant, including at the edges`() {
        // Without normalising by the realised tap weight, every asset would begin and end with
        // a fade the source does not contain.
        val resampled = Resampler.resample(FloatArray(4_410) { 0.75f }, 44_100, 48_000)
        resampled.forEachIndexed { index, value ->
            assertEquals("sample $index", 0.75, value.toDouble(), 1e-3)
        }
    }

    @Test
    fun `silence resamples to silence`() {
        assertTrue(Resampler.resample(FloatArray(4_410), 44_100, 48_000).all { abs(it) < 1e-9f })
    }

    @Test
    fun `downsampling band-limits rather than aliasing`() {
        // A 20 kHz tone downsampled to 32 kHz (Nyquist 16 kHz) must be attenuated, not folded
        // back to 12 kHz where the band and centroid features would later measure it.
        val source = SignalGenerators.sine(48_000, 20_000.0, 48_000, amplitude = 0.8f)
        val down = Resampler.resample(source, 48_000, 32_000)

        val interior = down.copyOfRange(500, down.size - 500)
        val aliasEnergy = magnitudeAt(interior, 12_000.0, 32_000.0)
        assertTrue("20 kHz folded back to 12 kHz with magnitude $aliasEnergy", aliasEnergy < 0.05)
    }

    @Test
    fun `resampling is deterministic`() {
        val source = Fixtures.whiteNoise(4_410).samples
        val first = Resampler.resample(source, 44_100, 48_000)
        repeat(5) { assertArrayEquals(first, Resampler.resample(source, 44_100, 48_000), 0.0f) }
    }

    @Test
    fun `degenerate rates and empty input are handled`() {
        assertThrows(IllegalArgumentException::class.java) { Resampler.resample(FloatArray(10), 0, 48_000) }
        assertThrows(IllegalArgumentException::class.java) { Resampler.resample(FloatArray(10), 44_100, 0) }
        assertEquals(0, Resampler.resample(FloatArray(0), 44_100, 48_000).size)
    }

    @Test
    fun `ofCanonicalMono rejects a source at the wrong rate`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalSignal.ofCanonicalMono(FloatArray(100), format(44_100, 1, 100))
        }
    }

    // --- helpers: a Goertzel-style probe, independent of any FFT we have not built yet ---

    private fun magnitudeAt(samples: FloatArray, frequencyHz: Double, sampleRateHz: Double): Double {
        val omega = 2.0 * PI * frequencyHz / sampleRateHz
        var real = 0.0
        var imaginary = 0.0
        for (n in samples.indices) {
            real += samples[n] * cos(omega * n)
            imaginary -= samples[n] * sin(omega * n)
        }
        return sqrt(real * real + imaginary * imaginary) / samples.size * 2.0
    }

    private fun dominantFrequency(
        samples: FloatArray,
        sampleRateHz: Int,
        from: Double,
        to: Double,
        stepHz: Double = 0.5,
    ): Double {
        var best = from
        var bestMagnitude = -1.0
        var frequency = from
        while (frequency <= to) {
            val magnitude = magnitudeAt(samples, frequency, sampleRateHz.toDouble())
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                best = frequency
            }
            frequency += stepHz
        }
        return best
    }
}
