package com.arvs.audio.beat

import com.arvs.core.model.BeatConfig
import com.arvs.core.time.AnalysisFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §21.1's beat-detection definition clause by clause. */
class BeatDetectorTest {

    private val framing = AnalysisFraming.CANONICAL          // 100 Hz, so 1 frame = 10 ms
    private val config = BeatConfig()

    /** A flat baseline with impulses at the given frames — the simplest signal with known beats. */
    private fun fluxWithOnsetsAt(size: Int, baseline: Float, peak: Float, vararg at: Int) =
        FloatArray(size) { baseline }.also { for (index in at) it[index] = peak }

    // --- the normative constants ---------------------------------------------------------------

    @Test
    fun `the ratified BeatConfig values are exactly those in the decision`() {
        // §21.1's table. Pinned here because all five are in analysisConfigHash: a silent change
        // would change every cached beat and every cache key without anyone noticing.
        val defaults = BeatConfig()
        assertEquals(40.0, defaults.minTempoBpm, 0.0)
        assertEquals(240.0, defaults.maxTempoBpm, 0.0)
        assertEquals(1.0, defaults.thresholdWindowSeconds, 0.0)
        assertEquals(1.5, defaults.madMultiplier, 0.0)
        assertEquals(100.0, defaults.refractoryMs, 0.0)
    }

    // --- clause 1: flux above an adaptive threshold ---------------------------------------------

    @Test
    fun `the threshold is the window median plus the multiplier times its MAD`() {
        // Analytic: values 1,2,3,4,100 -> median 3; deviations 2,1,0,1,97 -> MAD 1;
        // threshold = 3 + 1.5 x 1 = 4.5. Chosen so the outlier moves the mean but not the median,
        // which is the whole reason §21.1 specifies median/MAD.
        val window = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 100.0)
        assertEquals(4.5, BeatDetector.adaptiveThreshold(window, 5, 1.5), 1e-12)
    }

    @Test
    fun `the median takes the mean of the two central values for an even window`() {
        assertEquals(2.5, BeatDetector.medianOf(doubleArrayOf(1.0, 2.0, 3.0, 4.0), 4), 1e-12)
        assertEquals(2.0, BeatDetector.medianOf(doubleArrayOf(3.0, 1.0, 2.0), 3), 1e-12)
    }

    @Test
    fun `a constant flux series produces no beats`() {
        // median = value, MAD = 0, so threshold = value and `flux > threshold` is false
        // everywhere. Silence and steady tone must never generate beats.
        assertTrue(BeatDetector.detect(FloatArray(500) { 0.4f }, framing, config).isEmpty())
        assertTrue(BeatDetector.detect(FloatArray(500), framing, config).isEmpty())
    }

    @Test
    fun `an isolated onset above the threshold is detected on its own frame`() {
        val flux = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 150)
        val beats = BeatDetector.detect(flux, framing, config)
        assertEquals(1, beats.size)
        assertEquals(150L, beats[0].frameIndex)
    }

    // --- clause 2: local maximum ---------------------------------------------------------------

    @Test
    fun `a rising ramp fires only at its crest, not on every frame above threshold`() {
        // Every frame of the ramp clears the threshold, so without the local-maximum condition
        // this reports a run of beats. Only the crest is a real onset.
        val flux = FloatArray(300) { 0.1f }
        for (offset in 0 until 20) flux[150 + offset] = 1.0f + offset
        val beats = BeatDetector.detect(flux, framing, config)
        assertEquals(1, beats.size)
        assertEquals(169L, beats[0].frameIndex)          // the last, highest frame of the ramp
    }

    @Test
    fun `a plateau does not fire twice`() {
        val flux = FloatArray(300) { 0.1f }
        flux[150] = 5.0f
        flux[151] = 5.0f                                  // exactly equal: strict on both sides
        assertTrue(BeatDetector.detect(flux, framing, config).isEmpty())
    }

    @Test
    fun `frame zero has no history to be an excursion from, so it is never a beat`() {
        // A structural consequence of §21.1's *history* window, not an edge case that was
        // overlooked. At frame 0 the trailing window holds only frame 0, so median = flux[0] and
        // MAD = 0, making threshold = flux[0] — and `flux > threshold` cannot hold against
        // itself. An onset needs something to be an onset relative to.
        val atZero = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 0)
        assertTrue(BeatDetector.detect(atZero, framing, config).isEmpty())

        // One frame of history is already enough, so the exclusion is exactly frame 0 and does
        // not swallow the opening of a track more broadly.
        val atThree = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 3)
        assertEquals(listOf(3L), BeatDetector.detect(atThree, framing, config).map { it.frameIndex })
    }

    @Test
    fun `an onset on the final frame is still detected`() {
        // The other boundary, where the missing neighbour genuinely would lose a real beat: the
        // last frame has no successor, and treating that as disqualifying would drop it.
        val flux = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 299)
        assertEquals(listOf(299L), BeatDetector.detect(flux, framing, config).map { it.frameIndex })
    }

    // --- clause 3: refractory period -----------------------------------------------------------

    @Test
    fun `two onsets closer than the refractory period yield only the first`() {
        // 100 ms at 100 Hz is 10 frames. Onsets 5 frames (50 ms) apart: the second is suppressed.
        val flux = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 150, 155)
        val beats = BeatDetector.detect(flux, framing, config)
        assertEquals(1, beats.size)
        assertEquals(150L, beats[0].frameIndex)
    }

    @Test
    fun `the refractory boundary is inclusive at exactly 100 ms`() {
        // "at least 100 ms have elapsed" — 10 frames apart is exactly 100 ms and must pass,
        // 9 frames is 90 ms and must not. This pins the boundary a `>` would move.
        val exactly = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150, 160)
        assertEquals(2, BeatDetector.detect(exactly, framing, config).size)

        val justUnder = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150, 159)
        assertEquals(1, BeatDetector.detect(justUnder, framing, config).size)
    }

    @Test
    fun `the refractory period is measured from the last confirmed beat, not the last candidate`() {
        // Onsets at 150, 155, 160. If the suppressed candidate at 155 reset the timer, 160 would
        // also be suppressed (only 50 ms later). Measured from the confirmed beat at 150, the
        // one at 160 is a full 100 ms away and must fire.
        val flux = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150, 155, 160)
        val beats = BeatDetector.detect(flux, framing, config)
        assertEquals(listOf(150L, 160L), beats.map { it.frameIndex })
    }

    // --- confidence and strength ---------------------------------------------------------------

    @Test
    fun `confidence is the threshold-relative excess, clamped to zero one`() {
        // Analytic: a long flat baseline of 1.0 gives median 1.0 and MAD 0, so threshold = 1.0.
        // A peak of 1.5 gives (1.5 - 1.0)/1.0 = 0.5 exactly.
        val flux = FloatArray(300) { 1.0f }
        flux[150] = 1.5f
        val beats = BeatDetector.detect(flux, framing, config)
        assertEquals(1, beats.size)
        assertEquals(0.5, beats[0].confidence.toDouble(), 1e-6)
    }

    @Test
    fun `confidence saturates at one rather than exceeding it`() {
        val flux = FloatArray(300) { 0.01f }
        flux[150] = 1_000.0f
        val beats = BeatDetector.detect(flux, framing, config)
        assertEquals(1.0f, beats[0].confidence)
    }

    @Test
    fun `a stronger onset over the same baseline has at least as much confidence`() {
        fun confidenceFor(peak: Float): Float {
            val flux = FloatArray(300) { 0.5f }
            flux[150] = peak
            return BeatDetector.detect(flux, framing, config).single().confidence
        }
        assertTrue(confidenceFor(0.6f) < confidenceFor(0.8f))
    }

    @Test
    fun `strength is the raw onset value at the beat`() {
        val flux = fluxWithOnsetsAt(300, 0.1f, 7.25f, 150)
        assertEquals(7.25f, BeatDetector.detect(flux, framing, config).single().strength)
    }

    @Test
    fun `the timestamp is the frame start in the audio-source domain`() {
        val flux = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150)
        val beat = BeatDetector.detect(flux, framing, config).single()
        assertEquals(framing.frameStartTime(150L), beat.timestamp)
    }

    // --- configuration participation ------------------------------------------------------------

    @Test
    fun `disabling beat analysis produces no beats`() {
        val flux = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150)
        assertTrue(BeatDetector.detect(flux, framing, config.copy(enabled = false)).isEmpty())
    }

    @Test
    fun `each detector parameter genuinely changes the result`() {
        // If a parameter is in analysisConfigHash it must change a stored number; a parameter
        // that changed nothing would be a hash member that only forces needless re-analysis.
        //
        // The baseline must vary. Against a flat baseline the window's MAD is 0, so
        // madMultiplier multiplies zero and correctly changes nothing — which says something
        // about the signal, not about the parameter.
        // A bimodal baseline: half the frames at 0.4, half at 0.6, so median = 0.5 and MAD = 0.1
        // exactly, giving a threshold of 0.65 with real margin above the 0.6 crest. A sawtooth
        // baseline is the wrong choice here — median + 1.5·MAD lands *on* its crest, so whether
        // the baseline itself fires turns on float rounding rather than on the parameter.
        val flux = FloatArray(600) { if (it % 2 == 0) 0.4f else 0.6f }
        for (frame in intArrayOf(100, 150, 200, 260, 320, 400)) flux[frame] = 1.0f
        val baseline = BeatDetector.detect(flux, framing, config)
        assertEquals(6, baseline.size)

        // A larger multiplier raises the threshold, so it can only ever remove beats — a
        // monotone, analytic consequence rather than a recorded number.
        val strict = BeatDetector.detect(flux, framing, config.copy(madMultiplier = 8.0))
        assertTrue("madMultiplier removed nothing", strict.size < baseline.size)

        // A refractory period longer than the 500 ms spacing must merge neighbouring beats.
        val sluggish = BeatDetector.detect(flux, framing, config.copy(refractoryMs = 1_000.0))
        assertTrue("refractoryMs merged nothing", sluggish.size < baseline.size)

        // A much shorter history window sees a different median and MAD, so a different
        // threshold and therefore different confidences.
        val shortWindow = BeatDetector.detect(flux, framing, config.copy(thresholdWindowSeconds = 0.05))
        assertNotEquals(
            "thresholdWindowSeconds",
            baseline.map { it.confidence },
            shortWindow.map { it.confidence },
        )
    }

    @Test
    fun `detection is deterministic and free of wall-clock or ordering effects`() {
        // §21.1 forbids randomisation and wall-clock dependence; §9.1 requires bit-identical
        // output for identical input. Repeated runs must agree exactly, not approximately.
        val flux = FloatArray(1_000) { (it % 37) * 0.01f }
        for (frame in intArrayOf(50, 130, 260, 500, 640, 900)) flux[frame] = 4.0f
        val first = BeatDetector.detect(flux, framing, config)
        repeat(5) { assertEquals(first, BeatDetector.detect(flux, framing, config)) }
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun `beats come back in ascending frame order`() {
        val flux = FloatArray(800) { 0.2f }
        for (frame in intArrayOf(700, 100, 400, 250)) flux[frame] = 6.0f
        val frames = BeatDetector.detect(flux, framing, config).map { it.frameIndex }
        assertEquals(frames.sorted(), frames)
    }

    @Test
    fun `an empty series is handled without error`() {
        assertTrue(BeatDetector.detect(FloatArray(0), framing, config).isEmpty())
        assertTrue(BeatDetector.detect(FloatArray(1) { 1.0f }, framing, config).isEmpty())
    }

    @Test
    fun `a steady pulse train is detected at its true period`() {
        // 120 BPM is a beat every 0.5 s, i.e. every 50 frames at 100 Hz. Every pulse must be
        // found and none invented: an end-to-end check on the three conditions together.
        val flux = FloatArray(1_000) { 0.1f }
        val expected = (50 until 1_000 step 50).toList()
        for (frame in expected) flux[frame] = 4.0f
        val beats = BeatDetector.detect(flux, framing, config)
        assertEquals(expected.map { it.toLong() }, beats.map { it.frameIndex })
    }
}
