package com.arvs.audio.analysis

import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.time.AnalysisFraming
import com.arvs.core.time.TimeSpan
import com.arvs.testing.audio.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ratified U-21 framing (§17.5) as this module actually applies it.
 *
 * `core:time` already tests the arithmetic. What is tested here is that the analysis pipeline
 * *uses* it — window 2048, hop 480, frames anchored at the window start, `N = ceil(total/hop)`,
 * zero-padded tail — because a correct framing type used incorrectly is exactly as wrong as a
 * broken one, and only this side can tell.
 */
class AnalysisFramingConformanceTest {

    private fun signal(samples: FloatArray) = CanonicalSignal.ofCanonicalMono(
        samples,
        AudioFormatInfo(48_000, 1, TimeSpan(samples.size.toLong() * 1_000_000L / 48_000)),
    )

    @Test
    fun `the pipeline uses the ratified canonical framing`() {
        val framing = AnalysisFrames(signal(FloatArray(48_000))).framing

        assertEquals(2_048, framing.windowSamples)
        assertEquals(480, framing.hopSamples)
        assertEquals(48_000, framing.sampleRateHz)
        assertEquals(100.0, framing.frameRateHz, 0.0)
    }

    @Test
    fun `overlap is derived and is not the superseded fifty percent figure`() {
        // §17.5: the "50% overlap" figure is superseded and must not be retained, cited or
        // described as canonical anywhere in code or tests.
        val framing = AnalysisFrames(signal(FloatArray(4_800))).framing

        assertEquals(0.765625, framing.overlapFraction, 1e-12)
        assertNotEquals(0.5, framing.overlapFraction, 1e-9)
    }

    @Test
    fun `frame count is ceil of total samples over hop`() {
        // A pure function of asset length: no dependence on buffering, chunking or decode
        // order, which is what §9.1's determinism rests on.
        listOf(
            0 to 0L,
            1 to 1L,
            479 to 1L,
            480 to 1L,
            481 to 2L,
            48_000 to 100L,
            48_001 to 101L,
        ).forEach { (samples, expected) ->
            assertEquals("for $samples samples", expected, AnalysisFrames(signal(FloatArray(samples))).frameCount)
        }
    }

    @Test
    fun `one second of audio produces exactly one hundred frames`() {
        assertEquals(100L, AnalysisFrames(signal(FloatArray(48_000))).frameCount)
    }

    @Test
    fun `frames are anchored at the window start, on the ten millisecond grid`() {
        // Centre-anchoring would land at n*10ms + 21.333ms and never on the grid, which is
        // what would force the interpolation §17.5 forbids.
        val frames = AnalysisFrames(signal(FloatArray(48_000)))
        val buffer = frames.newFrameBuffer()

        // A ramp makes each sample's identity visible.
        val ramp = FloatArray(48_000) { it.toFloat() }
        val rampFrames = AnalysisFrames(signal(ramp))
        rampFrames.readFrame(0, buffer)
        assertEquals(0.0f, buffer[0])
        rampFrames.readFrame(1, buffer)
        assertEquals(480.0f, buffer[0])
        rampFrames.readFrame(7, buffer)
        assertEquals(3_360.0f, buffer[0])

        assertEquals(2_048, buffer.size)
        assertEquals(2_048, frames.newFrameBuffer().size)
    }

    @Test
    fun `frame start times land exactly on ten millisecond boundaries`() {
        val framing = AnalysisFraming.CANONICAL
        (0L..100L).forEach { index ->
            assertEquals(index * 10_000L, framing.frameStartTime(index).micros)
        }
    }

    @Test
    fun `the tail is zero-padded rather than truncated`() {
        // §17.5's rule. Truncating would make the final frames shorter and their features
        // incomparable with every other frame's.
        val frames = AnalysisFrames(signal(FloatArray(500) { 1.0f }))
        val buffer = frames.newFrameBuffer()

        frames.readFrame(1, buffer) // covers samples 480..2527, only 480..499 exist
        assertEquals(1.0f, buffer[0])
        assertEquals(1.0f, buffer[19])
        assertEquals(0.0f, buffer[20])
        assertTrue(buffer.drop(20).all { it == 0.0f })
    }

    @Test
    fun `a frame entirely past the end is all zeros`() {
        val frames = AnalysisFrames(signal(FloatArray(481) { 1.0f }))
        val buffer = frames.newFrameBuffer()
        frames.readFrame(1, buffer)
        assertEquals(1.0f, buffer[0])
        assertTrue(buffer.drop(1).all { it == 0.0f })
    }

    @Test
    fun `out-of-range frame indices and wrong buffer sizes are rejected`() {
        val frames = AnalysisFrames(signal(FloatArray(4_800)))
        assertThrows(IllegalArgumentException::class.java) { frames.readFrame(-1, frames.newFrameBuffer()) }
        assertThrows(IllegalArgumentException::class.java) {
            frames.readFrame(frames.frameCount, frames.newFrameBuffer())
        }
        assertThrows(IllegalArgumentException::class.java) { frames.readFrame(0, FloatArray(1_024)) }
    }

    @Test
    fun `framing a signal at the wrong rate is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisFrames(signal(FloatArray(4_800)), AnalysisFraming(sampleRateHz = 44_100))
        }
    }

    @Test
    fun `the section 119 fixtures all frame consistently`() {
        Fixtures.all(frames = 4_800).forEach { fixture ->
            val canonical = CanonicalSignal.from(fixture.toPcmBuffer(), fixture.format)
            val frames = AnalysisFrames(canonical)
            assertEquals("${fixture.name} frame count", 10L, frames.frameCount)
        }
    }

    @Test
    fun `the section 17_1 stage order is the ratified priority order`() {
        assertEquals(
            listOf(
                AnalysisStage.WAVEFORM_PEAKS,
                AnalysisStage.SCALAR_ENVELOPE,
                AnalysisStage.SPECTRUM,
                AnalysisStage.BEAT,
                AnalysisStage.SPECTRAL_DESCRIPTORS,
            ),
            AnalysisStage.PRIORITY_ORDER,
        )
        assertEquals(
            listOf(AnalysisStage.WAVEFORM_PEAKS, AnalysisStage.SCALAR_ENVELOPE),
            AnalysisStage.TRIM_CRITICAL,
        )
    }
}
