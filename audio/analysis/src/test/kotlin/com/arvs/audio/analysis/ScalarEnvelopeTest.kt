package com.arvs.audio.analysis

import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.TimeSpan
import com.arvs.testing.audio.Fixtures
import com.arvs.testing.audio.SignalGenerators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pins §17.1 stage 2 against **analytic truth**, per the plan's first test suite.
 *
 * Every expectation here is stated independently of the implementation — a sine's RMS is
 * `A/√2` because that is what a sine's RMS is, not because that is what the code returned.
 */
class ScalarEnvelopeTest {

    private fun envelopeOf(samples: FloatArray): ScalarEnvelope {
        val signal = CanonicalSignal.ofCanonicalMono(
            samples,
            AudioFormatInfo(48_000, 1, TimeSpan(samples.size.toLong() * 1_000_000L / 48_000)),
        )
        return ScalarEnvelope.compute(AnalysisFrames(signal))
    }

    // --- analytic truth ------------------------------------------------------------------

    @Test
    fun `a sine has RMS equal to amplitude over root two`() {
        val amplitude = 0.5f
        val envelope = envelopeOf(SignalGenerators.sine(48_000, 440.0, 48_000, amplitude))

        // Interior frames only: the tail frames are zero-padded by §17.5 and so read lower,
        // correctly.
        (0 until 90).forEach { frame ->
            assertEquals("frame $frame", (amplitude / sqrt(2.0)), envelope.rms[frame].toDouble(), 2e-3)
        }
    }

    @Test
    fun `a sine peaks at its amplitude`() {
        val envelope = envelopeOf(SignalGenerators.sine(48_000, 440.0, 48_000, amplitude = 0.25f))
        (0 until 90).forEach { frame ->
            assertEquals(0.25, envelope.peak[frame].toDouble(), 1e-3)
        }
    }

    @Test
    fun `energy is the sum of squares over the analysis window`() {
        // Stated convention: energy = sum of x squared over the 2048-sample window, so
        // energy = rms squared times the window length.
        val envelope = envelopeOf(SignalGenerators.sine(48_000, 1_000.0, 48_000, 0.5f))
        (0 until 90).forEach { frame ->
            val fromRms = envelope.rms[frame].toDouble() * envelope.rms[frame] * 2_048
            assertEquals(fromRms, envelope.energy[frame].toDouble(), fromRms * 1e-4)
        }
    }

    @Test
    fun `silence yields zeros with no NaN or Inf`() {
        val envelope = envelopeOf(Fixtures.silence(48_000).samples)

        assertTrue(envelope.rms.all { it == 0.0f })
        assertTrue(envelope.peak.all { it == 0.0f })
        assertTrue(envelope.energy.all { it == 0.0f })
        assertTrue(envelope.rms.none { it.isNaN() || it.isInfinite() })
    }

    @Test
    fun `the clipping fixture peaks at exactly full scale`() {
        val envelope = envelopeOf(Fixtures.clipping(48_000).samples)
        assertEquals(1.0f, envelope.globalPeak)
    }

    @Test
    fun `the very quiet fixture does not collapse to zero`() {
        // §119's designated denormal test. Summing 2048 squares in Float rather than Double
        // loses these contributions entirely.
        val envelope = envelopeOf(Fixtures.veryQuiet(48_000).samples)

        val expected = 1.0e-6 / sqrt(2.0)
        (0 until 90).forEach { frame ->
            assertTrue("frame $frame collapsed to ${envelope.rms[frame]}", envelope.rms[frame] > 0.0f)
            assertEquals(expected, envelope.rms[frame].toDouble(), expected * 0.05)
        }
    }

    @Test
    fun `an impulse appears in exactly the frames whose window covers it`() {
        // §17.5 anchors a frame at its window *start*: frame n covers [n*480, n*480+2048).
        //
        // An impulse at sample 0 is therefore in frame 0 alone. The centre-anchored intuition
        // — that several earlier frames would also "reach" it — is precisely the anchoring
        // §17.5 rejects, because it would place frames off the 10 ms grid. This test is the
        // difference between the two made visible.
        val atZero = envelopeOf(SignalGenerators.impulse(48_000, position = 0))
        assertEquals(1.0f, atZero.peak[0])
        (1 until atZero.frameCount).forEach { frame ->
            assertEquals("frame $frame should not see an impulse at sample 0", 0.0f, atZero.peak[frame])
        }

        // Mid-signal, the covering set is a run whose extent is fixed by window and hop
        // together: sample 2000 falls in frames 0..4, since 4*480 = 1920 <= 2000 < 3968 and
        // 5*480 = 2400 > 2000.
        val midway = envelopeOf(SignalGenerators.impulse(48_000, position = 2_000))
        (0..4).forEach { frame -> assertEquals("frame $frame", 1.0f, midway.peak[frame]) }
        (5 until midway.frameCount).forEach { frame ->
            assertEquals("frame $frame", 0.0f, midway.peak[frame])
        }
    }

    @Test
    fun `peak reports magnitude, so a negative excursion is not missed`() {
        // Every §119 fixture's largest excursion happens to be positive, so a signed max and
        // an absolute max agree on all of them — a gap the mutation pass exposed. A
        // negative-only impulse separates the two: signed max would report 0, magnitude 1.
        val negative = FloatArray(48_000)
        negative[2_000] = -1.0f
        val envelope = envelopeOf(negative)

        assertEquals(1.0f, envelope.peak[0])
        assertEquals(1.0f, envelope.globalPeak)
    }

    @Test
    fun `an asymmetric signal peaks on whichever side is larger`() {
        val asymmetric = FloatArray(48_000) { if (it % 2 == 0) 0.2f else -0.9f }
        val envelope = envelopeOf(asymmetric)
        assertEquals(0.9f, envelope.peak[0], 1e-6f)
    }

    @Test
    fun `the sum of squares is accumulated at double precision`() {
        // One loud sample among two thousand quiet ones. In a Float accumulator every quiet
        // term is more than 1e-7 below the running total and is discarded outright, so the
        // energy reads as exactly 1.0 — the quiet detail is absent, not merely imprecise.
        // The quiet level is chosen so both halves of the claim hold at once: each term
        // (1e-8) is below half a Float ULP at a running total of 1.0, so a Float accumulator
        // discards every one of them and lands on exactly 1.0 — while their *sum* (2.047e-5)
        // is ~170 ULPs, comfortably resolvable once stored as a Float. A smaller level would
        // make the correct answer indistinguishable from 1.0 after storage, and the test
        // would be asserting on rounding rather than on accumulation.
        val frame = FloatArray(48_000) { 1.0e-4f }
        frame[0] = 1.0f
        val envelope = envelopeOf(frame)

        val quietTerms = 2_047 * 1.0e-4.let { it * it }
        assertEquals(1.0 + quietTerms, envelope.energy[0].toDouble(), 1.0e-6)
        assertTrue(
            "a Float accumulator would discard every quiet term and land on exactly 1.0",
            envelope.energy[0].toDouble() > 1.0 + quietTerms / 2,
        )
    }

    @Test
    fun `the envelope tracks a fade`() {
        val ramp = FloatArray(48_000) { it / 48_000.0f }
        val envelope = envelopeOf(ramp)

        envelope.rms.take(90).zipWithNext { earlier, later ->
            assertTrue("envelope should rise monotonically", later >= earlier)
        }
    }

    // --- §17.2 timeline and interpolation ---------------------------------------------------

    @Test
    fun `the envelope lands on the one hundred hertz timeline`() {
        assertEquals(100, envelopeOf(FloatArray(48_000)).frameCount)
        assertEquals(200, envelopeOf(FloatArray(96_000)).frameCount)
    }

    @Test
    fun `reads at arbitrary times interpolate linearly between adjacent samples`() {
        // §17.2's rule for reading between stored frames — unrelated to §17.5's prohibition on
        // interpolating spectra to manufacture stored frames.
        val ramp = FloatArray(48_000) { it / 48_000.0f }
        val envelope = envelopeOf(ramp)

        val atFrame3 = envelope.rms[3]
        val atFrame4 = envelope.rms[4]
        val halfway = envelope.rmsAt(AudioSourceTime(35_000)) // 3.5 frames in

        assertEquals(((atFrame3 + atFrame4) / 2.0).toDouble(), halfway.toDouble(), 1e-6)
    }

    @Test
    fun `reads exactly on a frame boundary return that frame's value`() {
        val envelope = envelopeOf(SignalGenerators.sine(48_000, 440.0, 48_000))
        (0 until 50).forEach { frame ->
            assertEquals(envelope.rms[frame], envelope.rmsAt(AudioSourceTime(frame * 10_000L)))
        }
    }

    @Test
    fun `reads past the end clamp instead of throwing`() {
        val envelope = envelopeOf(FloatArray(4_800) { 0.5f })
        assertEquals(envelope.rms.last(), envelope.rmsAt(AudioSourceTime(999_000_000)))
        assertEquals(envelope.rms.first(), envelope.rmsAt(AudioSourceTime(0)))
    }

    // --- §9.1 determinism -------------------------------------------------------------------

    @Test
    fun `analysing the same signal twice gives bit-identical results`() {
        val samples = Fixtures.whiteNoise(48_000).samples
        val first = envelopeOf(samples)
        val second = envelopeOf(samples)

        assertTrue(first.rms.contentEquals(second.rms))
        assertTrue(first.peak.contentEquals(second.peak))
        assertTrue(first.energy.contentEquals(second.energy))
    }

    @Test
    fun `results do not depend on the order frames are read in`() {
        // §9.1: never a function of playback order or invocation count.
        val envelope = envelopeOf(Fixtures.drums(48_000).samples)
        val shuffled = (0 until envelope.frameCount).shuffled(java.util.Random(42).let {
            kotlin.random.Random(42)
        })

        shuffled.forEach { frame ->
            assertEquals(envelope.rms[frame], envelope.rmsAt(AudioSourceTime(frame * 10_000L)))
        }
    }

    @Test
    fun `the window function is not applied to amplitude features`() {
        // A Hann window would scale a full-scale sine's RMS to about 0.61 of its true level,
        // and the analytic truth above would simply not hold. This states the property
        // directly rather than leaving it implied.
        val envelope = envelopeOf(SignalGenerators.sine(48_000, 440.0, 48_000, amplitude = 1.0f))
        val hannScaledRms = 1.0 / sqrt(2.0) * sqrt(3.0 / 8.0) // what Hann would give

        assertEquals(1.0 / sqrt(2.0), envelope.rms[10].toDouble(), 5e-3)
        assertTrue(
            "must not match the Hann-windowed value",
            abs(envelope.rms[10] - hannScaledRms) > 0.1,
        )
    }

    @Test
    fun `every section 119 fixture analyses without producing NaN or Inf`() {
        Fixtures.all(frames = 4_800).forEach { fixture ->
            val canonical = CanonicalSignal.from(fixture.toPcmBuffer(), fixture.format)
            val envelope = ScalarEnvelope.compute(AnalysisFrames(canonical))
            listOf(envelope.rms, envelope.peak, envelope.energy).forEach { values ->
                assertTrue(
                    "${fixture.name} produced a non-finite value",
                    values.none { it.isNaN() || it.isInfinite() },
                )
            }
        }
    }

    @Test
    fun `rms never exceeds peak`() {
        // A structural invariant that holds for any real signal and catches a whole class of
        // indexing and accumulation errors.
        Fixtures.all(frames = 9_600).forEach { fixture ->
            val canonical = CanonicalSignal.from(fixture.toPcmBuffer(), fixture.format)
            val envelope = ScalarEnvelope.compute(AnalysisFrames(canonical))
            (0 until envelope.frameCount).forEach { frame ->
                assertTrue(
                    "${fixture.name} frame $frame: rms ${envelope.rms[frame]} > peak ${envelope.peak[frame]}",
                    envelope.rms[frame] <= envelope.peak[frame] + 1e-6f,
                )
            }
        }
    }
}
