package com.arvs.audio.analysis

import com.arvs.core.model.WindowFunction
import com.arvs.testing.audio.SignalGenerators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pins the FFT against a naive DFT and against analytic truth.
 *
 * The reference DFT below is the transform's textbook definition, written independently of the
 * fast implementation. Checking an FFT against itself would prove only that it is deterministic.
 */
class FftTest {

    /** O(N²) DFT magnitude — the definition, used only as a reference. */
    private fun referenceMagnitudes(samples: FloatArray): DoubleArray {
        val n = samples.size
        return DoubleArray(n / 2 + 1) { bin ->
            var re = 0.0
            var im = 0.0
            for (index in 0 until n) {
                val angle = 2.0 * PI * bin * index / n
                re += samples[index] * cos(angle)
                im -= samples[index] * sin(angle)
            }
            sqrt(re * re + im * im)
        }
    }

    private fun magnitudes(fft: Fft, samples: FloatArray): DoubleArray =
        fft.newSpectrumBuffer().also { fft.magnitudeSpectrum(samples, it) }

    @Test
    fun `matches a naive DFT on random input`() {
        listOf(16, 64, 256).forEach { size ->
            val fft = Fft(size)
            val samples = SignalGenerators.whiteNoise(size, seed = size.toLong())
            val fast = magnitudes(fft, samples)
            val reference = referenceMagnitudes(samples)

            reference.indices.forEach { bin ->
                assertEquals("size $size bin $bin", reference[bin], fast[bin], 1e-9 * size)
            }
        }
    }

    @Test
    fun `matches a naive DFT at the canonical 2048-point size`() {
        val fft = Fft(2_048)
        val samples = SignalGenerators.sine(2_048, 1_000.0, 48_000, amplitude = 0.7f)
        val fast = magnitudes(fft, samples)
        val reference = referenceMagnitudes(samples)

        reference.indices.forEach { bin ->
            assertEquals("bin $bin", reference[bin], fast[bin], 1e-6)
        }
    }

    @Test
    fun `a bin-centred sine concentrates in exactly one bin`() {
        // Bin width is 48000/2048 = 23.4375 Hz, so bin 40 is exactly 937.5 Hz. A tone placed on a
        // bin centre has no leakage, which is the cleanest analytic check there is.
        val fft = Fft(2_048)
        val binWidth = 48_000.0 / 2_048
        val samples = SignalGenerators.sine(2_048, 40 * binWidth, 48_000, amplitude = 1.0f)
        val spectrum = magnitudes(fft, samples)

        // An unwindowed full-scale sine gives |X[k]| = N·A/2 at its bin.
        assertEquals(1_024.0, spectrum[40], 1.0)
        spectrum.indices.filter { it != 40 }.forEach { bin ->
            assertTrue("bin $bin leaked ${spectrum[bin]}", spectrum[bin] < 1.0)
        }
    }

    @Test
    fun `an impulse has a flat spectrum`() {
        val fft = Fft(256)
        val spectrum = magnitudes(fft, SignalGenerators.impulse(256, position = 0))
        spectrum.forEach { assertEquals(1.0, it, 1e-9) }
    }

    @Test
    fun `DC appears in bin zero and nowhere else`() {
        val fft = Fft(256)
        val spectrum = magnitudes(fft, FloatArray(256) { 0.5f })

        assertEquals(128.0, spectrum[0], 1e-6)          // N * amplitude
        (1..128).forEach { assertEquals(0.0, spectrum[it], 1e-9) }
    }

    @Test
    fun `silence transforms to silence`() {
        val fft = Fft(256)
        magnitudes(fft, FloatArray(256)).forEach { assertEquals(0.0, it, 0.0) }
    }

    @Test
    fun `the transform is deterministic across repeated calls`() {
        val fft = Fft(2_048)
        val samples = SignalGenerators.whiteNoise(2_048)
        val first = magnitudes(fft, samples)
        repeat(5) { assertTrue(first.contentEquals(magnitudes(fft, samples))) }
    }

    @Test
    fun `reusing one instance across frames does not carry state`() {
        // The scratch buffers are instance state; a leak between frames would make a frame's
        // spectrum depend on the frame before it, which §9.1 forbids.
        val fft = Fft(256)
        val a = SignalGenerators.sine(256, 3_000.0, 48_000)
        val b = SignalGenerators.impulse(256)

        val aFirst = magnitudes(fft, a)
        magnitudes(fft, b)
        val aAgain = magnitudes(fft, a)

        assertTrue(aFirst.contentEquals(aAgain))
    }

    @Test
    fun `non-power-of-two sizes and mismatched buffers are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Fft(1_000) }
        assertThrows(IllegalArgumentException::class.java) { Fft(0) }
        val fft = Fft(256)
        assertThrows(IllegalArgumentException::class.java) { fft.magnitudeSpectrum(FloatArray(128), fft.newSpectrumBuffer()) }
        assertThrows(IllegalArgumentException::class.java) { fft.magnitudeSpectrum(FloatArray(256), DoubleArray(64)) }
    }

    // --- window functions ---------------------------------------------------------------

    @Test
    fun `window coefficients match their published definitions`() {
        // Evaluated from the standard formulas at the midpoint of a periodic window, where each
        // window takes its maximum.
        val size = 1_024
        val hann = WindowFunctions.coefficients(WindowFunction.HANN, size)
        val hamming = WindowFunctions.coefficients(WindowFunction.HAMMING, size)
        val blackman = WindowFunctions.coefficients(WindowFunction.BLACKMAN_HARRIS, size)

        assertEquals(1.0, hann[size / 2].toDouble(), 1e-6)
        assertEquals(1.0, hamming[size / 2].toDouble(), 1e-6)
        assertEquals(1.0, blackman[size / 2].toDouble(), 1e-5)

        // A periodic window starts at its minimum; Hann starts at exactly zero.
        // Tolerances are sized to Float, not to the analytic ideal: the coefficients are a
        // FloatArray, so 0.08 is only representable to ~6e-9 and a tighter bound would be
        // asserting on storage precision rather than on the window definition.
        assertEquals(0.0, hann[0].toDouble(), 1e-9)
        assertEquals(0.08, hamming[0].toDouble(), 1e-7)
    }

    @Test
    fun `coherent gain matches the analytic mean of each window`() {
        // Hann's mean is exactly 0.5 and Hamming's exactly 0.54 — the constant terms of their
        // definitions, since every cosine term averages to zero over a periodic window.
        // Summing 2048 Float coefficients accumulates ~1e-9 of error against the analytic value,
        // so the bound is sized to that rather than to the exact constant.
        assertEquals(0.5, WindowFunctions.coherentGain(WindowFunction.HANN, 2_048), 1e-8)
        assertEquals(0.54, WindowFunctions.coherentGain(WindowFunction.HAMMING, 2_048), 1e-8)
        assertEquals(0.35875, WindowFunctions.coherentGain(WindowFunction.BLACKMAN_HARRIS, 2_048), 1e-8)
    }

    @Test
    fun `windowing attenuates a sine by exactly the coherent gain`() {
        // The property that makes §17.4's normalization correct: dividing by the coherent gain
        // restores a full-scale sine to a full-scale reading whatever window is chosen.
        val fft = Fft(2_048)
        val binWidth = 48_000.0 / 2_048
        val tone = SignalGenerators.sine(2_048, 40 * binWidth, 48_000, amplitude = 1.0f)

        WindowFunction.entries.forEach { function ->
            val window = WindowFunctions.coefficients(function, 2_048)
            val windowed = FloatArray(2_048) { tone[it] * window[it] }
            val peak = magnitudes(fft, windowed).max()
            val gain = WindowFunctions.coherentGain(function, 2_048)

            assertEquals("$function", 1_024.0 * gain, peak, 1_024.0 * gain * 0.01)
        }
    }

    @Test
    fun `blackman-harris leaks less than hann, which leaks less than a rectangle`() {
        // The reason a window function is configurable at all (§18.2 item 4): the trade is
        // sidelobe suppression against main-lobe width. Measured on a tone deliberately placed
        // *between* bin centres, where leakage is worst.
        val fft = Fft(2_048)
        val binWidth = 48_000.0 / 2_048
        val offBin = SignalGenerators.sine(2_048, 40.5 * binWidth, 48_000, amplitude = 1.0f)

        fun farLeakage(function: WindowFunction?): Double {
            val samples = if (function == null) offBin else {
                val window = WindowFunctions.coefficients(function, 2_048)
                FloatArray(2_048) { offBin[it] * window[it] }
            }
            val spectrum = magnitudes(fft, samples)
            val gain = if (function == null) 1.0 else WindowFunctions.coherentGain(function, 2_048)
            // Energy well away from the tone, relative to the window's own scale.
            return (100..1_024).maxOf { spectrum[it] } / (1_024.0 * gain)
        }

        val rectangular = farLeakage(null)
        val hann = farLeakage(WindowFunction.HANN)
        val blackman = farLeakage(WindowFunction.BLACKMAN_HARRIS)

        assertTrue("hann ($hann) should leak less than rectangular ($rectangular)", hann < rectangular)
        assertTrue("blackman ($blackman) should leak less than hann ($hann)", blackman < hann)
    }
}
