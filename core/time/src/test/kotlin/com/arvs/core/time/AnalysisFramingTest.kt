package com.arvs.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the canonical analysis framing ratified in §17.5 (Appendix B U-21).
 *
 * These are not incidental unit tests. §17.5's whole purpose is that FFT window size, hop
 * and frame rate are three separate quantities that must never be conflated, and that the
 * native rate equals the storage rate so spectra are never interpolated into existence. If
 * any of that drifts, every cached value and every golden vector silently changes meaning —
 * so it is asserted here, arithmetically.
 */
class AnalysisFramingTest {

    private val canonical = AnalysisFraming.CANONICAL

    @Test
    fun `canonical framing is 2048 window, 480 hop, 48 kHz`() {
        assertEquals(48_000, canonical.sampleRateHz)
        assertEquals(2_048, canonical.windowSamples)
        assertEquals(480, canonical.hopSamples)
    }

    @Test
    fun `native frame rate is exactly 100 Hz`() {
        // The entire point of U-21: 48000/480 is exact, so stored frames are measured
        // frames. A hop of 1024 (the superseded "50% overlap") would give 46.875 Hz and
        // force interpolation, which §17.5 forbids.
        assertEquals(100.0, canonical.frameRateHz, 0.0)
        assertEquals(
            AnalysisFraming.CANONICAL_FRAME_RATE_HZ.toDouble(),
            canonical.frameRateHz,
            0.0,
        )
    }

    @Test
    fun `hop duration is exactly 10 ms and representable without rounding`() {
        assertEquals(10_000L, canonical.hopDuration.micros)
    }

    @Test
    fun `overlap is derived from window and hop, never an input`() {
        // (2048 - 480) / 2048 = 0.765625, exactly representable in binary.
        assertEquals(0.765625, canonical.overlapFraction, 0.0)
        // And emphatically not the superseded 50% figure.
        assertNotEquals(0.5, canonical.overlapFraction, 1e-9)
    }

    @Test
    fun `window duration and bin width follow from the canonical rate`() {
        // 2048 / 48000 s = 42666.66..µs, floored to 42666.
        assertEquals(42_666L, canonical.windowDuration.micros)
        assertEquals(23.4375, canonical.binWidthHz, 0.0)
    }

    @Test
    fun `frames are anchored at the window start`() {
        // §17.5: frame n covers [n*480, n*480 + 2048).
        assertEquals(0L, canonical.frameStartSample(0))
        assertEquals(480L, canonical.frameStartSample(1))
        assertEquals(4_800L, canonical.frameStartSample(10))

        assertEquals(0L until 2_048L, canonical.frameSampleRange(0))
        assertEquals(480L until 2_528L, canonical.frameSampleRange(1))
    }

    @Test
    fun `frame timestamps land exactly on the 10 ms grid`() {
        // This is what start-anchoring buys. Centre-anchoring would put frame n at
        // n*10ms + 21.333ms, never on the grid, reintroducing interpolation.
        for (frame in longArrayOf(0, 1, 7, 100, 6_000, 360_000)) {
            assertEquals(frame * 10_000L, canonical.frameStartTime(frame).micros)
        }
    }

    @Test
    fun `frame count is ceil of totalSamples over hop`() {
        // §17.5: N = ceil(totalSamples / 480), a pure function of asset length.
        assertEquals(0L, canonical.frameCount(0))
        assertEquals(1L, canonical.frameCount(1))
        assertEquals(1L, canonical.frameCount(480))
        assertEquals(2L, canonical.frameCount(481))
        assertEquals(100L, canonical.frameCount(48_000)) // exactly one second
        assertEquals(101L, canonical.frameCount(48_001))
    }

    @Test
    fun `one second of audio yields exactly 100 frames`() {
        assertEquals(100L, canonical.frameCount(canonical.sampleRateHz.toLong()))
    }

    @Test
    fun `frame lookup and interpolation agree on grid points`() {
        val onGrid = AudioSourceTime(70_000) // exactly frame 7
        assertEquals(7L, canonical.frameIndexAtOrBefore(onGrid))

        val interpolation = canonical.interpolationAt(onGrid)
        assertEquals(7L, interpolation.lowerFrame)
        assertEquals(0.0, interpolation.fraction, 1e-12)
    }

    @Test
    fun `interpolation between grid points blends linearly`() {
        // §17.2: consumers linearly interpolate between the two adjacent 100 Hz samples.
        val betweenFrames = AudioSourceTime(75_000) // halfway between frame 7 and 8
        val interpolation = canonical.interpolationAt(betweenFrames)

        assertEquals(7L, interpolation.lowerFrame)
        assertEquals(8L, interpolation.upperFrame)
        assertEquals(0.5, interpolation.fraction, 1e-12)
        assertEquals(15.0f, interpolation.blend(10.0f, 20.0f), 1e-6f)
    }

    @Test
    fun `interpolation clamps before the epoch rather than extrapolating`() {
        val result = canonical.interpolationAt(AudioSourceTime(-5_000))
        assertEquals(0L, result.lowerFrame)
        assertEquals(0.0, result.fraction, 0.0)
    }

    @Test
    fun `sample and micro conversions round-trip on grid boundaries`() {
        for (frame in 0L until 1_000L) {
            val samples = canonical.frameStartSample(frame)
            val micros = canonical.samplesToMicros(samples)
            assertEquals(samples, canonical.microsToSamples(micros))
        }
    }

    @Test
    fun `a hop larger than the window is rejected`() {
        // Would leave unanalysed gaps in the signal — silent data loss, so it fails loudly.
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisFraming(sampleRateHz = 48_000, windowSamples = 512, hopSamples = 1_024)
        }
    }

    @Test
    fun `non-canonical framings remain expressible for configurability`() {
        // §106 keeps FFT size user-configurable; only the defaults are fixed by §17.5.
        val largeWindow = AnalysisFraming(windowSamples = 4_096)
        assertEquals(100.0, largeWindow.frameRateHz, 0.0) // hop unchanged, so rate unchanged
        assertTrue(largeWindow.overlapFraction > canonical.overlapFraction)
        assertEquals(11.71875, largeWindow.binWidthHz, 1e-9) // finer bass resolution
    }
}
