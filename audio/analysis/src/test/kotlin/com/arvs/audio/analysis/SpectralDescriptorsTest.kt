package com.arvs.audio.analysis

import com.arvs.testing.audio.Fixtures
import com.arvs.testing.audio.SignalGenerators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §17.1 stage 5 against analytic truth. */
class SpectralDescriptorsTest {

    private val stage = SpectrumStage()
    private val binWidth = 48_000.0 / 2_048

    private fun spectrumOf(samples: FloatArray): FloatArray {
        val frame = FloatArray(2_048)
        samples.copyInto(frame, 0, 0, minOf(2_048, samples.size))
        return stage.newSpectrumBuffer().also { stage.spectrumOf(frame, stage.newScratch(), it) }
    }

    // --- centroid ---------------------------------------------------------------------------

    @Test
    fun `the centroid of a single tone is that tone's frequency`() {
        listOf(500.0, 1_000.0, 4_000.0).forEach { frequency ->
            val centroid = SpectralDescriptors.centroid(
                spectrumOf(SignalGenerators.sine(2_048, frequency, 48_000, amplitude = 0.8f)),
                binWidth,
            )
            assertEquals("$frequency Hz", frequency, centroid.toDouble(), binWidth * 2)
        }
    }

    @Test
    fun `two equal tones give a centroid between them`() {
        // Magnitude-weighted mean: two equal-amplitude tones put the centroid at their midpoint.
        val low = SignalGenerators.sine(2_048, 1_000.0, 48_000, amplitude = 0.5f)
        val high = SignalGenerators.sine(2_048, 3_000.0, 48_000, amplitude = 0.5f)
        val mixed = FloatArray(2_048) { low[it] + high[it] }

        val centroid = SpectralDescriptors.centroid(spectrumOf(mixed), binWidth)
        assertEquals(2_000.0, centroid.toDouble(), 60.0)
    }

    @Test
    fun `a brighter signal has a higher centroid`() {
        val dark = SpectralDescriptors.centroid(
            spectrumOf(SignalGenerators.sine(2_048, 200.0, 48_000, 0.8f)), binWidth,
        )
        val bright = SpectralDescriptors.centroid(
            spectrumOf(SignalGenerators.sine(2_048, 8_000.0, 48_000, 0.8f)), binWidth,
        )
        assertTrue(bright > dark)
    }

    @Test
    fun `silence gives a zero centroid rather than NaN`() {
        val centroid = SpectralDescriptors.centroid(spectrumOf(FloatArray(2_048)), binWidth)
        assertEquals(0.0f, centroid)
        assertTrue(!centroid.isNaN())
    }

    // --- flux -------------------------------------------------------------------------------

    @Test
    fun `flux is zero for the first frame and for an unchanging signal`() {
        val spectrum = spectrumOf(SignalGenerators.sine(2_048, 1_000.0, 48_000, 0.5f))
        assertEquals(0.0f, SpectralDescriptors.flux(spectrum, null))
        assertEquals(0.0f, SpectralDescriptors.flux(spectrum, spectrum))
    }

    @Test
    fun `flux is positive when energy appears and zero when it decays`() {
        // Half-wave rectified: an attack registers, a release does not. That asymmetry is the
        // whole reason flux is used as an onset indicator rather than an L2 distance.
        val quiet = spectrumOf(SignalGenerators.sine(2_048, 1_000.0, 48_000, 0.1f))
        val loud = spectrumOf(SignalGenerators.sine(2_048, 1_000.0, 48_000, 0.9f))

        val onset = SpectralDescriptors.flux(loud, quiet)
        val decay = SpectralDescriptors.flux(quiet, loud)

        assertTrue("onset should register, got $onset", onset > 0.1f)
        // Not exactly zero: FFT rounding leaves a handful of bins with ~1e-10 of positive
        // difference even on a pure decay. What matters is that a decay is orders of magnitude
        // below an attack, which is the asymmetry the rectification exists to produce.
        assertTrue("decay should be negligible, got $decay", decay < 1e-6f)
        assertTrue(onset > decay * 1e6)
    }

    @Test
    fun `flux equals the summed positive differences exactly`() {
        val a = floatArrayOf(0.1f, 0.5f, 0.2f, 0.0f)
        val b = floatArrayOf(0.3f, 0.2f, 0.9f, 0.4f)
        // positives: 0.2, 0, 0.7, 0.4  -> 1.3
        assertEquals(1.3, SpectralDescriptors.flux(b, a).toDouble(), 1e-6)
    }

    @Test
    fun `mismatched spectra are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpectralDescriptors.flux(FloatArray(4), FloatArray(8))
        }
    }

    // --- rolloff -----------------------------------------------------------------------------

    @Test
    fun `rolloff sits just above a single tone`() {
        // Essentially all the magnitude is at the tone, so 85% of it is reached there.
        val rolloff = SpectralDescriptors.rolloff(
            spectrumOf(SignalGenerators.sine(2_048, 2_000.0, 48_000, 0.8f)), binWidth,
        )
        assertEquals(2_000.0, rolloff.toDouble(), binWidth * 3)
    }

    @Test
    fun `rolloff reports the crossing bin's own frequency, not its neighbour`() {
        // Rolloff's bin-to-frequency mapping cannot be pinned with a windowed tone. A Hann
        // window spreads a bin-centred tone across three bins with magnitudes 0.25 / 0.50 /
        // 0.25, so the cumulative sum through the peak bin is exactly 0.75 — below any
        // fraction above 0.75, whose crossing therefore lands one bin *above* the peak by
        // definition. That is correct behaviour, and it means a tone's frequency is not the
        // analytic truth for its rolloff. Verified numerically before this test was written.
        //
        // A directly constructed spectrum has an analytic crossing index instead, which pins
        // both the `index + 1` retained-set offset (§17.4 discards DC) and the `>=` boundary.
        // Fractions are exact binary values so the threshold comparison carries no rounding.

        // A delta: all magnitude in one bin, so every fraction crosses there.
        val delta = FloatArray(256).also { it[100] = 1.0f }
        listOf(0.25, 0.5, 1.0).forEach { fraction ->
            assertEquals(
                "delta, fraction $fraction",
                101 * binWidth,
                SpectralDescriptors.rolloff(delta, binWidth, fraction).toDouble(),
                0.0,
            )
        }

        // A flat spectrum of 100 bins: fraction p crosses at retained index (100p - 1), whose
        // reported frequency is 100p * binWidth.
        val flat = FloatArray(100) { 1.0f }
        assertEquals(50 * binWidth, SpectralDescriptors.rolloff(flat, binWidth, 0.5).toDouble(), 0.0)
        assertEquals(25 * binWidth, SpectralDescriptors.rolloff(flat, binWidth, 0.25).toDouble(), 0.0)
    }

    @Test
    fun `a higher fraction gives a higher rolloff frequency`() {
        val spectrum = spectrumOf(Fixtures.whiteNoise(2_048).samples)
        val at50 = SpectralDescriptors.rolloff(spectrum, binWidth, 0.5)
        val at85 = SpectralDescriptors.rolloff(spectrum, binWidth, 0.85)
        val at99 = SpectralDescriptors.rolloff(spectrum, binWidth, 0.99)

        assertTrue(at50 < at85)
        assertTrue(at85 < at99)
    }

    @Test
    fun `white noise rolls off near the corresponding fraction of the spectrum`() {
        // A flat spectrum's cumulative magnitude is linear in frequency, so the 50% rolloff sits
        // near the middle of the retained range (24 kHz) — a genuinely analytic expectation.
        val spectrum = spectrumOf(Fixtures.whiteNoise(2_048).samples)
        val at50 = SpectralDescriptors.rolloff(spectrum, binWidth, 0.5)
        assertEquals(12_000.0, at50.toDouble(), 2_500.0)
    }

    @Test
    fun `silence gives a zero rolloff, and the fraction is validated`() {
        assertEquals(0.0f, SpectralDescriptors.rolloff(spectrumOf(FloatArray(2_048)), binWidth))
        assertThrows(IllegalArgumentException::class.java) {
            SpectralDescriptors.rolloff(FloatArray(16), binWidth, 1.5)
        }
    }

    // --- flatness -----------------------------------------------------------------------------

    @Test
    fun `white noise is much flatter than a pure tone`() {
        // Wiener entropy: near 1 for a flat spectrum, near 0 for a single peak.
        val noise = SpectralDescriptors.flatness(spectrumOf(Fixtures.whiteNoise(2_048).samples))
        val tone = SpectralDescriptors.flatness(
            spectrumOf(SignalGenerators.sine(2_048, 1_000.0, 48_000, 0.8f)),
        )

        assertTrue("noise flatness was $noise", noise > 0.2f)
        assertTrue("tone flatness was $tone", tone < 0.01f)
        assertTrue(noise > tone * 20)
    }

    @Test
    fun `a perfectly flat spectrum has flatness one`() {
        // The analytic extreme: geometric mean equals arithmetic mean exactly.
        assertEquals(1.0, SpectralDescriptors.flatness(FloatArray(1_024) { 0.5f }).toDouble(), 1e-5)
    }

    @Test
    fun `flatness stays within zero and one and is finite`() {
        Fixtures.all(frames = 2_048).forEach { fixture ->
            val flatness = SpectralDescriptors.flatness(spectrumOf(fixture.toPcmBuffer().toCanonicalMono()))
            assertTrue("${fixture.name} gave $flatness", flatness in 0.0f..1.0f)
            assertTrue(!flatness.isNaN() && !flatness.isInfinite())
        }
    }

    @Test
    fun `silence is reported as zero flatness, not one`() {
        // Silence is absent, not flat. Reporting 1.0 would make every silent passage look like
        // white noise to a reactive mapping.
        assertEquals(0.0f, SpectralDescriptors.flatness(spectrumOf(FloatArray(2_048))))
    }

    // --- chroma --------------------------------------------------------------------------------

    @Test
    fun `a tone at A4 lands in the A pitch class`() {
        // A4 = 440 Hz = MIDI 69; pitch class (69 - 12) mod 12 = 9, and 9 semitones above C is A.
        val chroma = SpectralDescriptors.chroma(
            spectrumOf(SignalGenerators.sine(2_048, 440.0, 48_000, 0.8f)), binWidth,
        )
        assertEquals(9, chroma.indices.maxBy { chroma[it] })
        assertEquals(1.0f, chroma[9])
    }

    @Test
    fun `octaves of the same note fold onto one pitch class`() {
        // The defining property of chroma.
        listOf(261.63, 523.25, 1_046.5, 2_093.0).forEach { frequency ->   // C4, C5, C6, C7
            val chroma = SpectralDescriptors.chroma(
                spectrumOf(SignalGenerators.sine(2_048, frequency, 48_000, 0.8f)), binWidth,
            )
            assertEquals("C at $frequency Hz", 0, chroma.indices.maxBy { chroma[it] })
        }
    }

    @Test
    fun `the twelve semitones map to twelve distinct pitch classes`() {
        // Tested at C6, not C4. At C4 adjacent semitones are ~16 Hz apart — below the 23.4375 Hz
        // bin width of a 2048-point window at 48 kHz — so two of them land on the same bin and
        // the transform genuinely cannot tell them apart. That is a resolution limit of the
        // ratified §17.5 framing, not a defect in the mapping, and an earlier version of this
        // test asked the FFT for something it cannot deliver.
        val classes = (0 until 12).map { semitone ->
            val frequency = 1_046.5 * Math.pow(2.0, semitone / 12.0)
            val chroma = SpectralDescriptors.chroma(
                spectrumOf(SignalGenerators.sine(2_048, frequency, 48_000, 0.8f)), binWidth,
            )
            chroma.indices.maxBy { chroma[it] }
        }
        assertEquals((0 until 12).toList(), classes)
    }

    @Test
    fun `the tuning reference is 440 Hz and is genuinely honoured`() {
        // The stated decision, pinned — a changed default would silently re-label every cached
        // chroma frame. Also proves the parameter is live: shifting the reference by a whole
        // semitone must move the reported pitch class, which a 20-cent shift would not.
        assertEquals(440.0, SpectralDescriptors.DEFAULT_TUNING_A4_HZ, 0.0)

        val spectrum = spectrumOf(SignalGenerators.sine(2_048, 1_760.0, 48_000, 0.8f))  // A6
        val atStandard = SpectralDescriptors.chroma(spectrum, binWidth, 440.0)
        val atSemitoneUp = SpectralDescriptors.chroma(spectrum, binWidth, 440.0 * Math.pow(2.0, 1.0 / 12.0))

        assertEquals("A at standard tuning", 9, atStandard.indices.maxBy { atStandard[it] })
        assertEquals(
            "a semitone of tuning shift must move the class",
            8, atSemitoneUp.indices.maxBy { atSemitoneUp[it] },
        )
    }

    @Test
    fun `chroma is normalised to a peak of one, and silence gives zeros`() {
        val chroma = SpectralDescriptors.chroma(
            spectrumOf(SignalGenerators.sine(2_048, 440.0, 48_000, 0.2f)), binWidth,
        )
        assertEquals(1.0f, chroma.max())

        val silent = SpectralDescriptors.chroma(spectrumOf(FloatArray(2_048)), binWidth)
        assertTrue(silent.all { it == 0.0f })
    }

    @Test
    fun `a wrong buffer size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpectralDescriptors.chroma(FloatArray(1_024), binWidth, into = FloatArray(7))
        }
    }

    // --- cross-fixture sweep ---------------------------------------------------------------------

    @Test
    fun `every descriptor is finite across the section 119 fixture set`() {
        Fixtures.all(frames = 2_048).forEach { fixture ->
            val spectrum = spectrumOf(fixture.toPcmBuffer().toCanonicalMono())
            val values = listOf(
                SpectralDescriptors.centroid(spectrum, binWidth),
                SpectralDescriptors.flux(spectrum, FloatArray(spectrum.size)),
                SpectralDescriptors.rolloff(spectrum, binWidth),
                SpectralDescriptors.flatness(spectrum),
            ) + SpectralDescriptors.chroma(spectrum, binWidth).toList()

            values.forEach { value ->
                assertTrue("${fixture.name} produced $value", !value.isNaN() && !value.isInfinite())
            }
        }
    }
}
