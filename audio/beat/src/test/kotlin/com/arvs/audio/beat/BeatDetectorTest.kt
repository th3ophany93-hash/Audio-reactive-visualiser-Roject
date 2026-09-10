package com.arvs.audio.beat

import com.arvs.core.model.BeatConfig
import com.arvs.core.time.AnalysisFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §21.1's beat-detection definition clause by clause. */
class BeatDetectorTest {

    private val framing = AnalysisFraming.CANONICAL          // 100 Hz, so 1 frame = 10 ms
    private val config = BeatConfig()

    /**
     * Runs detection with a source long enough that every frame of [flux] is eligible, so a test
     * about thresholds or refractory behaviour is not silently also a test about §21.1 [D-10]'s
     * tail rule. The tail rule has its own tests below.
     */
    private fun detectAll(
        flux: FloatArray,
        framing: AnalysisFraming = this.framing,
        config: BeatConfig = this.config,
    ) = BeatDetector.detect(flux, samplesCovering(flux.size, framing), framing, config)

    /** The smallest source length for which frame `count - 1` is still fully backed. */
    private fun samplesCovering(count: Int, framing: AnalysisFraming = this.framing): Long =
        (count - 1).toLong() * framing.hopSamples + framing.windowSamples

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
        assertTrue(detectAll(FloatArray(500) { 0.4f }, framing, config).isEmpty())
        assertTrue(detectAll(FloatArray(500), framing, config).isEmpty())
    }

    @Test
    fun `an isolated onset above the threshold is detected on its own frame`() {
        val flux = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 150)
        val beats = detectAll(flux, framing, config)
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
        val beats = detectAll(flux, framing, config)
        assertEquals(1, beats.size)
        assertEquals(169L, beats[0].frameIndex)          // the last, highest frame of the ramp
    }

    @Test
    fun `a plateau does not fire twice`() {
        val flux = FloatArray(300) { 0.1f }
        flux[150] = 5.0f
        flux[151] = 5.0f                                  // exactly equal: strict on both sides
        assertTrue(detectAll(flux, framing, config).isEmpty())
    }

    @Test
    fun `frame zero has no history to be an excursion from, so it is never a beat`() {
        // A structural consequence of §21.1's *history* window, not an edge case that was
        // overlooked. At frame 0 the trailing window holds only frame 0, so median = flux[0] and
        // MAD = 0, making threshold = flux[0] — and `flux > threshold` cannot hold against
        // itself. An onset needs something to be an onset relative to.
        val atZero = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 0)
        assertTrue(detectAll(atZero, framing, config).isEmpty())

        // One frame of history is already enough, so the exclusion is exactly frame 0 and does
        // not swallow the opening of a track more broadly.
        val atThree = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 3)
        assertEquals(listOf(3L), detectAll(atThree, framing, config).map { it.frameIndex })
    }

    @Test
    fun `an onset on the final frame is still detected`() {
        // The other boundary, where the missing neighbour genuinely would lose a real beat: the
        // last frame has no successor, and treating that as disqualifying would drop it.
        val flux = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 299)
        assertEquals(listOf(299L), detectAll(flux, framing, config).map { it.frameIndex })
    }

    // --- clause 3: refractory period -----------------------------------------------------------

    @Test
    fun `two onsets closer than the refractory period yield only the first`() {
        // 100 ms at 100 Hz is 10 frames. Onsets 5 frames (50 ms) apart: the second is suppressed.
        val flux = fluxWithOnsetsAt(300, baseline = 0.1f, peak = 5.0f, 150, 155)
        val beats = detectAll(flux, framing, config)
        assertEquals(1, beats.size)
        assertEquals(150L, beats[0].frameIndex)
    }

    @Test
    fun `the refractory boundary is inclusive at exactly 100 ms`() {
        // "at least 100 ms have elapsed" — 10 frames apart is exactly 100 ms and must pass,
        // 9 frames is 90 ms and must not. This pins the boundary a `>` would move.
        val exactly = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150, 160)
        assertEquals(2, detectAll(exactly, framing, config).size)

        val justUnder = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150, 159)
        assertEquals(1, detectAll(justUnder, framing, config).size)
    }

    @Test
    fun `the refractory period is measured from the last confirmed beat, not the last candidate`() {
        // Onsets at 150, 155, 160. If the suppressed candidate at 155 reset the timer, 160 would
        // also be suppressed (only 50 ms later). Measured from the confirmed beat at 150, the
        // one at 160 is a full 100 ms away and must fire.
        val flux = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150, 155, 160)
        val beats = detectAll(flux, framing, config)
        assertEquals(listOf(150L, 160L), beats.map { it.frameIndex })
    }

    // --- confidence and strength ---------------------------------------------------------------

    @Test
    fun `confidence is the threshold-relative excess, clamped to zero one`() {
        // Analytic: a long flat baseline of 1.0 gives median 1.0 and MAD 0, so threshold = 1.0.
        // A peak of 1.5 gives (1.5 - 1.0)/1.0 = 0.5 exactly.
        val flux = FloatArray(300) { 1.0f }
        flux[150] = 1.5f
        val beats = detectAll(flux, framing, config)
        assertEquals(1, beats.size)
        assertEquals(0.5, beats[0].confidence.toDouble(), 1e-6)
    }

    @Test
    fun `confidence saturates at one rather than exceeding it`() {
        val flux = FloatArray(300) { 0.01f }
        flux[150] = 1_000.0f
        val beats = detectAll(flux, framing, config)
        assertEquals(1.0f, beats[0].confidence)
    }

    @Test
    fun `a stronger onset over the same baseline has at least as much confidence`() {
        fun confidenceFor(peak: Float): Float {
            val flux = FloatArray(300) { 0.5f }
            flux[150] = peak
            return detectAll(flux, framing, config).single().confidence
        }
        assertTrue(confidenceFor(0.6f) < confidenceFor(0.8f))
    }

    @Test
    fun `strength is the raw onset value at the beat`() {
        val flux = fluxWithOnsetsAt(300, 0.1f, 7.25f, 150)
        assertEquals(7.25f, detectAll(flux, framing, config).single().strength)
    }

    @Test
    fun `the timestamp is the frame start in the audio-source domain`() {
        val flux = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150)
        val beat = detectAll(flux, framing, config).single()
        assertEquals(framing.frameStartTime(150L), beat.timestamp)
    }

    // --- configuration participation ------------------------------------------------------------

    @Test
    fun `disabling beat analysis produces no beats`() {
        val flux = fluxWithOnsetsAt(300, 0.1f, 5.0f, 150)
        assertTrue(detectAll(flux, framing, config.copy(enabled = false)).isEmpty())
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
        val baseline = detectAll(flux, framing, config)
        assertEquals(6, baseline.size)

        // A larger multiplier raises the threshold, so it can only ever remove beats — a
        // monotone, analytic consequence rather than a recorded number.
        val strict = detectAll(flux, framing, config.copy(madMultiplier = 8.0))
        assertTrue("madMultiplier removed nothing", strict.size < baseline.size)

        // A refractory period longer than the 500 ms spacing must merge neighbouring beats.
        val sluggish = detectAll(flux, framing, config.copy(refractoryMs = 1_000.0))
        assertTrue("refractoryMs merged nothing", sluggish.size < baseline.size)

        // A much shorter history window sees a different median and MAD, so a different
        // threshold and therefore different confidences.
        val shortWindow = detectAll(flux, framing, config.copy(thresholdWindowSeconds = 0.05))
        assertNotEquals(
            "thresholdWindowSeconds",
            baseline.map { it.confidence },
            shortWindow.map { it.confidence },
        )
    }

    // --- §21.1 [D-10]: analysis-tail eligibility ------------------------------------------------

    @Test
    fun `the eligible range is exactly the frames whose window is fully backed by real audio`() {
        // Frame f covers [f·hop, f·hop + window). Analytic, and pinned at the boundary in both
        // directions: one sample short of covering a frame must exclude it.
        val window = framing.windowSamples.toLong()      // 2048
        val hop = framing.hopSamples.toLong()            // 480

        assertEquals(0L, BeatDetector.lastEligibleFrame(window, framing))
        assertEquals(0L, BeatDetector.lastEligibleFrame(window + hop - 1, framing))
        assertEquals(1L, BeatDetector.lastEligibleFrame(window + hop, framing))
        assertEquals(195L, BeatDetector.lastEligibleFrame(96_000, framing))

        // Shorter than a single window: nothing is eligible.
        assertEquals(BeatDetector.NO_ELIGIBLE_FRAME, BeatDetector.lastEligibleFrame(window - 1, framing))
        assertEquals(BeatDetector.NO_ELIGIBLE_FRAME, BeatDetector.lastEligibleFrame(0, framing))
    }

    @Test
    fun `a track ending immediately after a transient produces no phantom beat`() {
        // §21.1 [D-10] clause 5, and the measured case that motivated the decision. The last
        // frames carry a large flux purely because §17.5's zero-padding truncates the signal;
        // 0.653 against a 9.8e-05 baseline was measured on the §119 sine fixture. Those frames
        // must produce nothing at all.
        val frames = 200
        val flux = FloatArray(frames) { 9.8e-05f }
        flux[196] = 0.024f
        flux[197] = 0.653f                                // the padding artefact
        flux[198] = 0.375f

        // 96 000 samples: frame 195 is the last fully backed one, so 196…199 are the tail.
        val beats = BeatDetector.detect(flux, 96_000, framing, config)
        assertTrue("phantom beats: ${beats.map { it.frameIndex }}", beats.isEmpty())

        // The same flux, with the source long enough to back every frame, *does* fire there —
        // proving the suppression comes from the eligibility rule and not from the signal.
        val unguarded = BeatDetector.detect(flux, samplesCovering(frames), framing, config)
        assertTrue("the artefact should fire when the frames are real", unguarded.isNotEmpty())
    }

    @Test
    fun `real beats before the final incomplete window are preserved`() {
        // §21.1 [D-10] clause 6. The tail rule must remove the phantom and nothing else.
        val frames = 200
        val flux = fluxWithOnsetsAt(frames, baseline = 0.1f, peak = 5.0f, 50, 100, 150, 190)
        flux[197] = 8.0f                                   // padding artefact, larger than any beat

        val beats = BeatDetector.detect(flux, 96_000, framing, config)
        assertEquals(listOf(50L, 100L, 150L, 190L), beats.map { it.frameIndex })
    }

    @Test
    fun `a beat on the last eligible frame is kept, and not judged against the padded frame`() {
        // The subtle half of clause 6. Frame 195 is the last eligible frame; frame 196 is padded
        // and its flux is huge. If the local-maximum test compared against frame 196, the real
        // beat at 195 would be suppressed by an artefact — so ineligible frames take no part as
        // neighbours either.
        val flux = FloatArray(200) { 0.1f }
        flux[195] = 5.0f
        flux[196] = 40.0f                                  // padding artefact, far larger

        val beats = BeatDetector.detect(flux, 96_000, framing, config)
        assertEquals(listOf(195L), beats.map { it.frameIndex })
        assertEquals(5.0f, beats.single().strength)
    }

    @Test
    fun `a source shorter than one analysis window produces no beats`() {
        val flux = fluxWithOnsetsAt(50, baseline = 0.1f, peak = 5.0f, 10, 20)
        assertTrue(BeatDetector.detect(flux, 2_047, framing, config).isEmpty())
        assertTrue(BeatDetector.detect(flux, 0, framing, config).isEmpty())
    }

    @Test
    fun `eligibility is bounded by the flux series as well as by the source length`() {
        // A source claiming more audio than the flux series describes must not read off the end.
        val flux = fluxWithOnsetsAt(60, baseline = 0.1f, peak = 5.0f, 30)
        val beats = BeatDetector.detect(flux, 10_000_000, framing, config)
        assertEquals(listOf(30L), beats.map { it.frameIndex })
    }

    @Test
    fun `a negative source length is rejected rather than silently treated as empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            BeatDetector.lastEligibleFrame(-1, framing)
        }
    }

    // --- §21.1 [D-9]: strength is the raw flux value ---------------------------------------------

    @Test
    fun `strength is the raw flux value and is never normalized, clamped or transformed`() {
        // §21.1 [D-9]'s contract test. Three properties, each of which a transform would break.
        val peaks = floatArrayOf(0.75f, 7.25f, 250.0f, 4_000.0f)
        peaks.forEach { peak ->
            val flux = fluxWithOnsetsAt(300, baseline = 0.1f, peak = peak, 150)
            val beat = detectAll(flux).single()

            // 1. Exactly the flux value at the beat frame — bit-identical, not merely close.
            assertEquals("peak $peak", flux[150], beat.strength)

            // 2. Unbounded above: a clamp to [0,1] would flatten every peak past the first.
            assertEquals("peak $peak", peak, beat.strength)
        }

        // 3. Distinct from confidence. Two tracks differing only in level both saturate
        //    confidence at 1.0, and only strength tells them apart — which is why the two fields
        //    exist separately and why normalizing strength would collapse them.
        val quiet = detectAll(fluxWithOnsetsAt(300, 0.001f, 0.05f, 150)).single()
        val loud = detectAll(fluxWithOnsetsAt(300, 0.1f, 5_000.0f, 150)).single()
        assertEquals(1.0f, quiet.confidence)
        assertEquals(1.0f, loud.confidence)
        assertTrue("strength must separate what confidence cannot", loud.strength > quiet.strength)
        assertTrue("strength is not confidence", loud.strength != loud.confidence)
    }

    @Test
    fun `detection is deterministic and free of wall-clock or ordering effects`() {
        // §21.1 forbids randomisation and wall-clock dependence; §9.1 requires bit-identical
        // output for identical input. Repeated runs must agree exactly, not approximately.
        val flux = FloatArray(1_000) { (it % 37) * 0.01f }
        for (frame in intArrayOf(50, 130, 260, 500, 640, 900)) flux[frame] = 4.0f
        val first = detectAll(flux, framing, config)
        repeat(5) { assertEquals(first, detectAll(flux, framing, config)) }
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun `beats come back in ascending frame order`() {
        val flux = FloatArray(800) { 0.2f }
        for (frame in intArrayOf(700, 100, 400, 250)) flux[frame] = 6.0f
        val frames = detectAll(flux, framing, config).map { it.frameIndex }
        assertEquals(frames.sorted(), frames)
    }

    @Test
    fun `an empty series is handled without error`() {
        assertTrue(detectAll(FloatArray(0), framing, config).isEmpty())
        assertTrue(detectAll(FloatArray(1) { 1.0f }, framing, config).isEmpty())
    }

    @Test
    fun `a steady pulse train is detected at its true period`() {
        // 120 BPM is a beat every 0.5 s, i.e. every 50 frames at 100 Hz. Every pulse must be
        // found and none invented: an end-to-end check on the three conditions together.
        val flux = FloatArray(1_000) { 0.1f }
        val expected = (50 until 1_000 step 50).toList()
        for (frame in expected) flux[frame] = 4.0f
        val beats = detectAll(flux, framing, config)
        assertEquals(expected.map { it.toLong() }, beats.map { it.frameIndex })
    }
}
