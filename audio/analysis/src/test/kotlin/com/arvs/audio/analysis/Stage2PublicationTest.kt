package com.arvs.audio.analysis

import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.time.TimeSpan
import com.arvs.testing.audio.Fixtures
import com.arvs.testing.audio.SignalGenerators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Pins §17.6 [T-7] / [T-8] and §17.7's two-phase publication model.
 */
class Stage2PublicationTest {

    private fun framesOf(samples: FloatArray) = AnalysisFrames(
        CanonicalSignal.ofCanonicalMono(
            samples,
            AudioFormatInfo(48_000, 1, TimeSpan(samples.size.toLong() * 1_000_000L / 48_000)),
        ),
    )

    // --- §17.7 clause 1: the phase split ------------------------------------------------

    @Test
    fun `phase 2a produces no derived channel`() {
        // §17.7 forbids publishing anything before the reference is final. The phase-2a result
        // type simply lacks the derived channel, so there is no partially-populated envelope
        // that could be written by mistake — the enforcement is structural.
        val measurement = Stage2.measure(framesOf(SignalGenerators.sine(48_000, 440.0, 48_000)))

        assertEquals(100, measurement.frameCount)
        // The reference is already final at the end of phase 2a — that is the whole point of the
        // phase existing.
        assertTrue(measurement.trackPeakEnergy > 0.0f)

        // That Stage2Measurement carries no derived channel is enforced at *compile* time: the
        // type simply has no `normalizedEnergy` member, so `measurement.normalizedEnergy` does
        // not compile and a partially-populated envelope cannot be constructed. Asserting it
        // here by reflection would be weaker than the guarantee it is checking, so what is
        // asserted instead is the consequence — the derived channel exists only after finalise.
        val published = Stage2.finalise(measurement)
        assertEquals(measurement.frameCount, published.normalizedEnergy.size)
    }

    @Test
    fun `phase 2b derives normalized energy without touching audio`() {
        val frames = framesOf(SignalGenerators.sine(48_000, 440.0, 48_000))
        val measurement = Stage2.measure(frames)
        val published = Stage2.finalise(measurement)

        assertEquals(measurement.trackPeakEnergy, published.trackPeakEnergy)
        // The raw channels are carried through unchanged, not recomputed.
        assertTrue(measurement.rms.contentEquals(published.rms))
        assertTrue(measurement.energy.contentEquals(published.energy))
        assertTrue(measurement.loudnessDb.contentEquals(published.loudnessDb))
    }

    // --- §17.6 [T-7] Normalized Energy ----------------------------------------------------

    @Test
    fun `normalized energy is energy over the track maximum`() {
        val envelope = Stage2.finalise(Stage2.measure(framesOf(Fixtures.drums(96_000).samples)))
        val reference = envelope.trackPeakEnergy

        (0 until envelope.frameCount).forEach { frame ->
            assertEquals(
                "frame $frame",
                (envelope.energy[frame] / reference).toDouble(),
                envelope.normalizedEnergy[frame].toDouble(),
                1e-6,
            )
        }
    }

    @Test
    fun `normalized energy reaches exactly one and never exceeds it`() {
        val envelope = Stage2.finalise(Stage2.measure(framesOf(Fixtures.drums(96_000).samples)))

        assertEquals(1.0f, envelope.normalizedEnergy.max(), 1e-6f)
        assertTrue(envelope.normalizedEnergy.all { it in 0.0f..1.0f })
    }

    @Test
    fun `normalized energy is invariant to source gain`() {
        // The property that makes it a distinct feature rather than a unit change on RMS
        // squared, and the reason §17.6 [T-7] chose a track-relative reference.
        val quiet = Fixtures.drums(96_000).samples
        val loud = FloatArray(quiet.size) { quiet[it] * 0.25f }

        val a = Stage2.finalise(Stage2.measure(framesOf(quiet)))
        val b = Stage2.finalise(Stage2.measure(framesOf(loud)))

        assertNotEquals(a.trackPeakEnergy, b.trackPeakEnergy)   // the reference scaled...
        (0 until a.frameCount).forEach { frame ->
            assertEquals(                                        // ...but the ratio did not
                "frame $frame",
                a.normalizedEnergy[frame].toDouble(),
                b.normalizedEnergy[frame].toDouble(),
                1e-5,
            )
        }
    }

    @Test
    fun `a silent track normalizes to zeros rather than NaN`() {
        // max Energy is 0, so the ratio is 0/0. Defined as 0: a silent track has no relative
        // energy anywhere, and NaN would propagate into every downstream mapping.
        val envelope = Stage2.finalise(Stage2.measure(framesOf(Fixtures.silence(48_000).samples)))

        assertEquals(0.0f, envelope.trackPeakEnergy)
        assertTrue(envelope.normalizedEnergy.all { it == 0.0f })
        assertTrue(envelope.normalizedEnergy.none { it.isNaN() })
    }

    @Test
    fun `the reference is carried so raw energy is recoverable from the ratio`() {
        val envelope = Stage2.finalise(Stage2.measure(framesOf(Fixtures.drums(48_000).samples)))

        (0 until envelope.frameCount).forEach { frame ->
            val recovered = envelope.normalizedEnergy[frame] * envelope.trackPeakEnergy
            assertEquals(envelope.energy[frame].toDouble(), recovered.toDouble(), envelope.energy[frame] * 1e-5 + 1e-9)
        }
    }

    // --- §17.6 [T-8] Loudness Approximation -------------------------------------------------

    @Test
    fun `silence sits exactly on the ratified floor`() {
        val envelope = Stage2.finalise(Stage2.measure(framesOf(Fixtures.silence(48_000).samples)))
        assertTrue(envelope.loudnessDb.all { it == -70.0f })
    }

    @Test
    fun `loudness is floored, never minus infinity`() {
        Fixtures.all(frames = 9_600).forEach { fixture ->
            val canonical = CanonicalSignal.from(fixture.toPcmBuffer(), fixture.format)
            val envelope = Stage2.finalise(Stage2.measure(AnalysisFrames(canonical)))
            assertTrue(
                "${fixture.name} produced a non-finite loudness",
                envelope.loudnessDb.none { it.isNaN() || it.isInfinite() },
            )
            assertTrue("${fixture.name} fell below the floor", envelope.loudnessDb.all { it >= -70.0f })
        }
    }

    @Test
    fun `loudness rises with level, by the decibel amount expected`() {
        // Halving amplitude is −6.02 dB. A pure scale must move the measurement by exactly that,
        // since the filter is linear.
        val loud = SignalGenerators.sine(48_000, 1_000.0, 48_000, amplitude = 0.5f)
        val quiet = FloatArray(loud.size) { loud[it] * 0.5f }

        val a = Stage2.finalise(Stage2.measure(framesOf(loud)))
        val b = Stage2.finalise(Stage2.measure(framesOf(quiet)))

        (10 until 80).forEach { frame ->
            assertEquals("frame $frame", -6.0206, (b.loudnessDb[frame] - a.loudnessDb[frame]).toDouble(), 0.01)
        }
    }

    @Test
    fun `K-weighting matches the published BS 1770 gain curve`() {
        // Checked against the standard's own frequency response rather than against hand-picked
        // "louder than / quieter than" thresholds. For a sine of amplitude A the measured
        // loudness exceeds the unweighted baseline by exactly the filter's gain at that
        // frequency, because the filter is linear:
        //
        //   loudness(f) − (−0.691 + 20·log₁₀(A/√2))  =  20·log₁₀ |H(f)|
        //
        // The expected gains are evaluated from the published 48 kHz coefficients: the RLB
        // high-pass cuts the low end and the shelf lifts the presence region, which is the whole
        // purpose of K-weighting and something a plain dBFS RMS could not show.
        val amplitude = 0.5
        val unweightedBaseline = -0.691 + 20.0 * log10(amplitude / sqrt(2.0))

        listOf(
            50.0 to -3.93,
            200.0 to -0.26,
            1_000.0 to 0.70,
            6_000.0 to 4.03,
            12_000.0 to 4.04,
        ).forEach { (frequency, expectedGainDb) ->
            val envelope = Stage2.finalise(
                Stage2.measure(framesOf(SignalGenerators.sine(48_000, frequency, 48_000, amplitude.toFloat()))),
            )
            val measuredGain = envelope.loudnessDb[50].toDouble() - unweightedBaseline
            assertEquals("gain at $frequency Hz", expectedGainDb, measuredGain, 0.3)
        }
    }

    @Test
    fun `a 1 kHz tone measures close to its unweighted level`() {
        // K-weighting is near unity around 1 kHz, so the measurement should sit close to the
        // plain dBFS figure. This anchors the absolute scale, not just relative behaviour.
        val amplitude = 0.5
        val envelope = Stage2.finalise(
            Stage2.measure(framesOf(SignalGenerators.sine(48_000, 1_000.0, 48_000, amplitude.toFloat()))),
        )
        val unweightedDb = 20.0 * log10(amplitude / sqrt(2.0))

        assertEquals(unweightedDb, envelope.loudnessDb[50].toDouble(), 1.5)
    }

    @Test
    fun `the filter streams continuously rather than restarting per frame`() {
        // Windows overlap by 76.5625% (§17.5). Filtering each window independently would run the
        // recursive filter over the same samples repeatedly and produce a state that never
        // corresponds to the real signal. A continuous filter's output on a steady tone settles
        // and stays settled; a per-window restart would show the transient again every frame.
        val envelope = Stage2.finalise(
            Stage2.measure(framesOf(SignalGenerators.sine(48_000, 1_000.0, 48_000, 0.5f))),
        )
        val settled = (20 until 90).map { envelope.loudnessDb[it] }
        settled.zipWithNext { a, b -> assertTrue("loudness should be steady, moved ${b - a}", abs(b - a) < 0.05f) }
    }

    // --- determinism ------------------------------------------------------------------------

    @Test
    fun `stage 2 is bit-identical across repeated runs`() {
        val samples = Fixtures.whiteNoise(48_000).samples
        val first = Stage2.finalise(Stage2.measure(framesOf(samples)))
        val second = Stage2.finalise(Stage2.measure(framesOf(samples)))

        assertTrue(first.rms.contentEquals(second.rms))
        assertTrue(first.peak.contentEquals(second.peak))
        assertTrue(first.energy.contentEquals(second.energy))
        assertTrue(first.normalizedEnergy.contentEquals(second.normalizedEnergy))
        assertTrue(first.loudnessDb.contentEquals(second.loudnessDb))
        assertEquals(first.trackPeakEnergy, second.trackPeakEnergy)
    }

    @Test
    fun `the K-weighting coefficients are the published BS 1770 values`() {
        // Pinned so a "cleanup" cannot silently redesign the filter — which would change every
        // cached loudness value and require an algorithmVersion bump (§18.2 item 11).
        assertEquals(48_000, KWeighting.COEFFICIENT_SAMPLE_RATE_HZ)
        assertEquals(-0.691, KWeighting.BS1770_OFFSET_DB, 0.0)
        assertEquals(-70.0, KWeighting.SILENCE_FLOOR_DB, 0.0)

        // A DC input exercises both biquads' gain at 0 Hz. The RLB high-pass rejects DC
        // completely, so a settled DC response must decay to zero — an independent check on the
        // coefficients rather than a restatement of them.
        val response = KWeighting().processAll(FloatArray(48_000) { 1.0f })
        assertTrue("DC must be rejected, settled at ${response.last()}", abs(response.last()) < 1e-3)
    }
}
