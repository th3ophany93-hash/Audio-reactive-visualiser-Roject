package com.arvs.audio.beat

import com.arvs.core.model.BeatConfig
import com.arvs.core.time.AnalysisFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §21.1's tempo estimation, and its structural separation from beat triggering. */
class TempoEstimatorTest {

    private val framing = AnalysisFraming.CANONICAL          // 100 Hz
    private val config = BeatConfig()

    /** A pulse train at [bpm]: a beat every `60·frameRate/bpm` frames. */
    private fun pulseTrain(frames: Int, bpm: Double): FloatArray {
        val period = (60.0 * framing.frameRateHz / bpm).toInt()
        return FloatArray(frames) { 0.05f }.also {
            var frame = period
            while (frame < frames) { it[frame] = 1.0f; frame += period }
        }
    }

    @Test
    fun `a pulse train is estimated at its true tempo`() {
        // Analytic: the period is exact at these tempi (120 -> 50 frames, 100 -> 60, 60 -> 100),
        // so the answer is the generating tempo, not a hand-picked figure.
        listOf(60.0, 100.0, 120.0, 150.0).forEach { bpm ->
            val estimate = TempoEstimator.estimate(pulseTrain(3_000, bpm), framing, config)
            assertEquals("$bpm BPM", bpm, estimate.bpm, 1.0)
            assertTrue("$bpm confidence ${estimate.confidence}", estimate.confidence > 0.0f)
        }
    }

    @Test
    fun `the estimate never leaves the normative 40 to 240 BPM range`() {
        // §21.1 fixes the search range. A 30 BPM pulse train is outside it, so the estimator
        // must report something inside the range rather than following the signal out of it.
        val slow = TempoEstimator.estimate(pulseTrain(6_000, 30.0), framing, config)
        assertTrue("reported ${slow.bpm}", !slow.isPresent || slow.bpm in 40.0..240.0)

        val fast = TempoEstimator.estimate(pulseTrain(3_000, 400.0), framing, config)
        assertTrue("reported ${fast.bpm}", !fast.isPresent || fast.bpm in 40.0..240.0)
    }

    @Test
    fun `a narrowed search range is honoured`() {
        val narrow = config.copy(minTempoBpm = 100.0, maxTempoBpm = 140.0)
        val estimate = TempoEstimator.estimate(pulseTrain(3_000, 60.0), framing, narrow)
        assertTrue("reported ${estimate.bpm}", !estimate.isPresent || estimate.bpm in 100.0..140.0)
    }

    @Test
    fun `a constant series reports no tempo rather than a spurious one`() {
        // Mean removal is what makes this work: without it the DC component dominates every lag
        // and constant input reports a confident tempo.
        val estimate = TempoEstimator.estimate(FloatArray(2_000) { 0.7f }, framing, config)
        assertTrue("reported ${estimate.bpm}", !estimate.isPresent)
        assertEquals(0.0f, estimate.confidence)
    }

    @Test
    fun `silence and a too-short series report no tempo`() {
        assertTrue(!TempoEstimator.estimate(FloatArray(2_000), framing, config).isPresent)
        assertTrue(!TempoEstimator.estimate(FloatArray(0), framing, config).isPresent)
        assertTrue(!TempoEstimator.estimate(FloatArray(10) { 1.0f }, framing, config).isPresent)
    }

    @Test
    fun `a regular pulse train is more confident than an irregular one`() {
        val regular = TempoEstimator.estimate(pulseTrain(3_000, 120.0), framing, config)

        val irregular = FloatArray(3_000) { 0.05f }
        var frame = 50
        var step = 37
        while (frame < 3_000) {
            irregular[frame] = 1.0f
            step = 31 + (step * 7 + 13) % 45          // deterministic, not random
            frame += step
        }
        val estimate = TempoEstimator.estimate(irregular, framing, config)
        assertTrue(
            "regular ${regular.confidence} vs irregular ${estimate.confidence}",
            regular.confidence > estimate.confidence,
        )
    }

    @Test
    fun `estimation is deterministic`() {
        val flux = pulseTrain(3_000, 128.0)
        val first = TempoEstimator.estimate(flux, framing, config)
        repeat(5) { assertEquals(first, TempoEstimator.estimate(flux, framing, config)) }
    }

    @Test
    fun `tempo confidence cannot suppress a beat, because detection never consults it`() {
        // §21.1's binding requirement, asserted structurally rather than by inspection. The flux
        // series below has strong onsets but no stable period, so the tempo estimate is weak. If
        // detection consulted tempo at all, that weakness would show up as missing beats.
        val flux = FloatArray(2_000) { 0.1f }
        val onsets = intArrayOf(200, 337, 512, 655, 900, 1_111, 1_400, 1_777)
        for (frame in onsets) flux[frame] = 4.0f

        val tempo = TempoEstimator.estimate(flux, framing, config)
        val beats = BeatDetector.detect(flux, framing, config)

        assertEquals(onsets.map { it.toLong() }, beats.map { it.frameIndex })
        assertTrue("tempo was $tempo but every beat still fired", beats.size == onsets.size)
    }
}
