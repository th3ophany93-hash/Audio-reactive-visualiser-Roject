package com.arvs.audio.analysis

import com.arvs.core.model.FrequencyBand
import com.arvs.core.model.WindowFunction
import com.arvs.testing.audio.Fixtures
import com.arvs.testing.audio.SignalGenerators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §17.4's retained representation and §20's default band set. */
class SpectrumStageTest {

    private val stage = SpectrumStage()
    private val binWidth = 48_000.0 / 2_048

    private fun spectrumOf(samples: FloatArray, at: Int = 0): FloatArray {
        val frame = FloatArray(2_048)
        samples.copyInto(frame, 0, at, minOf(at + 2_048, samples.size))
        return stage.newSpectrumBuffer().also { stage.spectrumOf(frame, stage.newScratch(), it) }
    }

    // --- §17.4's retained set --------------------------------------------------------------

    @Test
    fun `exactly 1024 bins are retained and the DC bin is discarded`() {
        // §17.4: "a real FFT of a 2048-sample window yields 1025 unique bins (DC through
        // Nyquist). The retained set is bins 1…1024; the DC bin (0 Hz) is discarded."
        assertEquals(1_024, stage.retainedBinCount)

        // The discard is asserted against the transform's own output: retained index i must be
        // FFT bin i+1, so the DC bin's value appears nowhere in the retained array.
        //
        // Note what cannot be asserted: that a DC signal produces an empty retained spectrum. Any
        // window has non-zero response at ±1 bin, so a Hann-windowed DC necessarily appears in
        // bin 1 at exactly half the DC bin's magnitude. That is the window's transform, not a
        // leak — an earlier version of this test asserted the impossible.
        val dc = FloatArray(2_048) { 0.8f }
        val window = WindowFunctions.coefficients(WindowFunction.HANN, 2_048)
        val windowed = FloatArray(2_048) { dc[it] * window[it] }
        val fft = Fft(2_048)
        val raw = fft.newSpectrumBuffer().also { fft.magnitudeSpectrum(windowed, it) }
        val reference = 2_048 / 2.0 * WindowFunctions.coherentGain(WindowFunction.HANN, 2_048)

        val retained = spectrumOf(dc)
        assertEquals(1_024, retained.size)
        assertEquals("retained[0] must be FFT bin 1", raw[1] / reference, retained[0].toDouble(), 1e-6)
        assertEquals("retained[1] must be FFT bin 2", raw[2] / reference, retained[1].toDouble(), 1e-6)
        val dcValue = raw[0] / reference
        assertTrue("the DC bin's value ($dcValue) must not appear in the retained set",
            retained.none { kotlin.math.abs(it - dcValue) < 1e-6 })
    }

    @Test
    fun `retained coverage runs from one bin width to Nyquist`() {
        // §17.4: "Retained coverage is 23.4375 Hz … 24,000 Hz."
        assertEquals(23.4375, stage.frequencyOf(0), 1e-9)
        assertEquals(24_000.0, stage.frequencyOf(1_023), 1e-9)
    }

    // --- magnitude scaling -------------------------------------------------------------------

    @Test
    fun `a full-scale sine on a bin centre reads one`() {
        // §17.4's "normalized against the canonical signal's full-scale reference", made concrete.
        val tone = SignalGenerators.sine(2_048, 40 * binWidth, 48_000, amplitude = 1.0f)
        val spectrum = spectrumOf(tone)

        assertEquals(1.0, spectrum[39].toDouble(), 0.02)   // retained index 39 == FFT bin 40
    }

    @Test
    fun `the reading is independent of the configured window function`() {
        // Without dividing by the coherent gain, switching Hann to Blackman-Harris would scale
        // every cached magnitude by 0.72 — a systematic shift that looks like a quieter mix.
        val tone = SignalGenerators.sine(2_048, 40 * binWidth, 48_000, amplitude = 1.0f)

        WindowFunction.entries.forEach { function ->
            val windowed = SpectrumStage(windowFunction = function)
            val spectrum = windowed.newSpectrumBuffer()
                .also { windowed.spectrumOf(tone, windowed.newScratch(), it) }
            assertEquals("$function", 1.0, spectrum[39].toDouble(), 0.03)
        }
    }

    @Test
    fun `amplitude scales the reading linearly`() {
        listOf(1.0f, 0.5f, 0.25f).forEach { amplitude ->
            val tone = SignalGenerators.sine(2_048, 40 * binWidth, 48_000, amplitude = amplitude)
            assertEquals(amplitude.toDouble(), spectrumOf(tone)[39].toDouble(), 0.03)
        }
    }

    @Test
    fun `silence gives an all-zero spectrum with no NaN`() {
        val spectrum = spectrumOf(FloatArray(2_048))
        assertTrue(spectrum.all { it == 0.0f })
    }

    @Test
    fun `a tone lands in the bin matching its frequency`() {
        listOf(100.0, 440.0, 1_000.0, 5_000.0, 12_000.0).forEach { frequency ->
            val spectrum = spectrumOf(SignalGenerators.sine(2_048, frequency, 48_000, amplitude = 0.8f))
            val peakIndex = spectrum.indices.maxBy { spectrum[it] }
            assertEquals("at $frequency Hz", frequency, stage.frequencyOf(peakIndex), binWidth)
        }
    }

    // --- §20 bands ------------------------------------------------------------------------------

    @Test
    fun `the section 20 default band set is nine bands with the ratified edges`() {
        assertEquals(9, FrequencyBand.DEFAULTS.size)
        assertEquals(
            listOf(
                20.0 to 60.0, 60.0 to 120.0, 120.0 to 250.0, 250.0 to 500.0, 500.0 to 1_000.0,
                1_000.0 to 2_000.0, 2_000.0 to 4_000.0, 4_000.0 to 8_000.0, 8_000.0 to 16_000.0,
            ),
            FrequencyBand.DEFAULTS.map { it.lowHz to it.highHz },
        )
    }

    @Test
    fun `a tone deposits its energy in the band containing it`() {
        val expectations = listOf(
            40.0 to 0, 90.0 to 1, 200.0 to 2, 400.0 to 3, 700.0 to 4,
            1_500.0 to 5, 3_000.0 to 6, 6_000.0 to 7, 12_000.0 to 8,
        )
        expectations.forEach { (frequency, bandIndex) ->
            val spectrum = spectrumOf(SignalGenerators.sine(2_048, frequency, 48_000, amplitude = 0.8f))
            val bands = FloatArray(9)
            stage.bandEnergies(spectrum, FrequencyBand.DEFAULTS, bands)

            val loudest = bands.indices.maxBy { bands[it] }
            assertEquals("$frequency Hz", bandIndex, loudest)
        }
    }

    @Test
    fun `band ranges are half-open so no bin is double counted`() {
        // Adjacent §20 bands share an edge (…250, 250…). Counting a bin in both would inflate
        // total band energy above the spectrum's own.
        val spectrum = spectrumOf(Fixtures.whiteNoise(2_048).samples)
        val bands = FloatArray(9)
        stage.bandEnergies(spectrum, FrequencyBand.DEFAULTS, bands)

        var spectrumPower = 0.0
        spectrum.indices.forEach { index ->
            val frequency = stage.frequencyOf(index)
            if (frequency >= 20.0 && frequency < 16_000.0) {
                spectrumPower += spectrum[index].toDouble() * spectrum[index]
            }
        }
        assertEquals(spectrumPower, bands.sum().toDouble(), spectrumPower * 1e-4)
    }

    @Test
    fun `a custom band edge landing exactly on a bin centre is half-open`() {
        // §20 supports user-defined bands, and a custom edge can sit exactly on a bin centre —
        // unlike any of the nine defaults, none of whose edges coincides with the §17.5 bin grid
        // (375 Hz is bin 16; 20/60/120/250/500/1k/2k/4k/8k/16k are all between bins). So the
        // half-open rule is *unobservable* with the default set and only bites here, which is
        // exactly the case a mutation to `<=` slipped through until this test existed.
        val binWidth = 48_000.0 / 2_048
        val edge = 16 * binWidth                       // 375.0 Hz, exactly a bin centre
        assertEquals(375.0, edge, 1e-12)
        assertEquals(edge, stage.frequencyOf(15), 1e-12)

        val custom = listOf(
            FrequencyBand("lower", 200.0, edge),
            FrequencyBand("upper", edge, 600.0),
        )
        // A tone placed exactly on that bin, so its energy is unambiguous.
        val spectrum = spectrumOf(SignalGenerators.sine(2_048, edge, 48_000, amplitude = 0.8f))
        val bands = FloatArray(2)
        stage.bandEnergies(spectrum, custom, bands)

        assertTrue("the boundary bin belongs to the upper band", bands[1] > bands[0])

        // And it is counted once, not twice: the two bands together cannot exceed the spectrum's
        // own power over their combined support.
        var support = 0.0
        spectrum.indices.forEach { index ->
            val frequency = stage.frequencyOf(index)
            if (frequency >= 200.0 && frequency < 600.0) support += spectrum[index].toDouble() * spectrum[index]
        }
        assertEquals(support, bands.sum().toDouble(), support * 1e-5)
    }

    @Test
    fun `band energy uses the ratified sum-of-squares convention`() {
        // §17.6 [T-9] fixed Energy as Σx²; band energy is the same quantity over a narrower
        // support, not a different one.
        val spectrum = spectrumOf(SignalGenerators.sine(2_048, 1_500.0, 48_000, amplitude = 0.8f))
        val bands = FloatArray(9)
        stage.bandEnergies(spectrum, FrequencyBand.DEFAULTS, bands)

        var expected = 0.0
        spectrum.indices.forEach { index ->
            val frequency = stage.frequencyOf(index)
            if (frequency >= 1_000.0 && frequency < 2_000.0) {
                expected += spectrum[index].toDouble() * spectrum[index]
            }
        }
        assertEquals(expected, bands[5].toDouble(), expected * 1e-5)
    }

    @Test
    fun `silence gives zero band energy`() {
        val bands = FloatArray(9)
        stage.bandEnergies(spectrumOf(FloatArray(2_048)), FrequencyBand.DEFAULTS, bands)
        assertTrue(bands.all { it == 0.0f })
    }

    // --- log spectrum (derived at read time, §17.4) -------------------------------------------

    @Test
    fun `the log spectrum is a re-binning, not new information`() {
        val spectrum = spectrumOf(SignalGenerators.sine(2_048, 1_000.0, 48_000, amplitude = 0.8f))
        val log = SpectrumStage.logSpectrum(spectrum, binWidth, binCount = 64)

        assertEquals(64, log.size)
        // The peak must land in the log bin covering 1 kHz.
        val peakBin = log.indices.maxBy { log[it] }
        val logLow = kotlin.math.ln(20.0)
        val logHigh = kotlin.math.ln(20_000.0)
        val expectedBin = ((kotlin.math.ln(1_000.0) - logLow) / (logHigh - logLow) * 64).toInt()
        assertEquals(expectedBin, peakBin)
    }

    @Test
    fun `log bins that receive no linear bin carry the previous value, not a gap`() {
        // At the low end a log bin can be narrower than 23.4 Hz. A zero there would draw a notch
        // the signal does not have.
        val spectrum = spectrumOf(Fixtures.whiteNoise(2_048).samples)
        val log = SpectrumStage.logSpectrum(spectrum, binWidth, binCount = 256)
        assertTrue("no bin should be an artificial zero", log.drop(1).all { it > 0.0f })
    }

    @Test
    fun `degenerate log spectrum parameters are rejected`() {
        val spectrum = FloatArray(1_024)
        assertThrows(IllegalArgumentException::class.java) {
            SpectrumStage.logSpectrum(spectrum, binWidth, binCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpectrumStage.logSpectrum(spectrum, binWidth, binCount = 32, lowHz = 0.0)
        }
    }

    @Test
    fun `mismatched buffers are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            stage.spectrumOf(FloatArray(1_024), stage.newScratch(), stage.newSpectrumBuffer())
        }
        assertThrows(IllegalArgumentException::class.java) {
            stage.spectrumOf(FloatArray(2_048), stage.newScratch(), FloatArray(512))
        }
        assertThrows(IllegalArgumentException::class.java) {
            stage.bandEnergies(FloatArray(1_024), FrequencyBand.DEFAULTS, FloatArray(3))
        }
    }
}
