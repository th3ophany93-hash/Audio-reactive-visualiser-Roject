package com.arvs.core.model

import com.arvs.core.time.TimeSpan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins §17.3's canonical mono downmix and §97's error taxonomy.
 *
 * §17.3 makes the stereo fixture mandatory and requires it to verify *deterministic* channel
 * handling — so the downmix is asserted exactly, not approximately.
 */
class PcmAndErrorsTest {

    // --- §17.3 canonical mono ----------------------------------------------------------

    @Test
    fun `stereo downmix is exactly half left plus half right`() {
        // §17.3: mono[n] = 0.5*L[n] + 0.5*R[n]. Interleaved input.
        val stereo = PcmBuffer(
            samples = floatArrayOf(
                1.0f, 0.0f, // frame 0: L=1, R=0   -> 0.5
                0.0f, 1.0f, // frame 1: L=0, R=1   -> 0.5
                0.5f, 0.5f, // frame 2                -> 0.5
                -1.0f, 1.0f, // frame 3: cancels      -> 0.0
                0.8f, 0.4f, // frame 4                -> 0.6
            ),
            channelCount = 2,
            sampleRateHz = 48_000,
            startFrame = 0,
        )

        assertArrayEquals(
            floatArrayOf(0.5f, 0.5f, 0.5f, 0.0f, 0.6f),
            stereo.toCanonicalMono(),
            1e-7f,
        )
    }

    @Test
    fun `mono passes through unchanged`() {
        val original = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)
        val mono = PcmBuffer(original, channelCount = 1, sampleRateHz = 48_000, startFrame = 0)
        assertArrayEquals(original, mono.toCanonicalMono(), 0.0f)
    }

    @Test
    fun `the downmix is deterministic across repeated calls`() {
        val stereo = PcmBuffer(
            samples = FloatArray(2_000) { index -> kotlin.math.sin(index * 0.01f) },
            channelCount = 2,
            sampleRateHz = 48_000,
            startFrame = 0,
        )
        val first = stereo.toCanonicalMono()
        repeat(10) { assertArrayEquals(first, stereo.toCanonicalMono(), 0.0f) }
    }

    @Test
    fun `multichannel downmix is equal-weight averaging`() {
        // §17.3's flagged generalisation of the ratified stereo rule.
        val quad = PcmBuffer(
            samples = floatArrayOf(1.0f, 1.0f, 0.0f, 0.0f), // one frame, four channels
            channelCount = 4,
            sampleRateHz = 48_000,
            startFrame = 0,
        )
        assertArrayEquals(floatArrayOf(0.5f), quad.toCanonicalMono(), 1e-7f)
    }

    @Test
    fun `a ragged interleaved buffer is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmBuffer(
                samples = floatArrayOf(0.1f, 0.2f, 0.3f), // 3 samples across 2 channels
                channelCount = 2,
                sampleRateHz = 48_000,
                startFrame = 0,
            )
        }
    }

    @Test
    fun `buffers compare by content, not array identity`() {
        val left = PcmBuffer(floatArrayOf(0.1f, 0.2f), 1, 48_000, 0)
        val right = PcmBuffer(floatArrayOf(0.1f, 0.2f), 1, 48_000, 0)
        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    // --- Source metadata (§17.3, §15) --------------------------------------------------

    @Test
    fun `format info preserves the source, and flags canonical rate`() {
        // §17.3: the source is not modified; the canonical mono/48k signal is analysis-only.
        val stereo44k = AudioFormatInfo(44_100, channelCount = 2, duration = TimeSpan.ofSeconds(10))
        assertTrue(stereo44k.isStereo)
        assertEquals(false, stereo44k.isCanonicalRate) // needs deterministic resampling
        assertEquals(441_000L, stereo44k.totalFrames)

        val mono48k = AudioFormatInfo(48_000, channelCount = 1, duration = TimeSpan.ofSeconds(10))
        assertTrue(mono48k.isCanonicalRate) // passes through bit-exact
        assertEquals(480_000L, mono48k.totalFrames)
    }

    // --- §97 error taxonomy ------------------------------------------------------------

    @Test
    fun `all fourteen §97 categories are present`() {
        assertEquals(14, ErrorCategory.entries.size)
        // Spot-check the Phase 1 reachable subset.
        listOf(
            ErrorCategory.IMPORT_ERROR,
            ErrorCategory.DECODER_ERROR,
            ErrorCategory.AUDIO_ANALYSIS_ERROR,
            ErrorCategory.UNSUPPORTED_FORMAT,
            ErrorCategory.PERMISSION_ERROR,
            ErrorCategory.OUT_OF_MEMORY,
            ErrorCategory.PROJECT_CORRUPTION,
        ).forEach { category -> assertTrue(category in ErrorCategory.entries) }
    }

    @Test
    fun `a blank error message is rejected`() {
        // §97: "never display only 'Something went wrong'". An empty message is the same
        // failure wearing a different hat, so the type refuses to hold one.
        assertThrows(IllegalArgumentException::class.java) {
            ArvsError(ErrorCategory.DECODER_ERROR, "   ")
        }
    }

    @Test
    fun `errors carry a category and an actionable recovery hint`() {
        val error = ArvsError(
            category = ErrorCategory.PERMISSION_ERROR,
            message = "Storage permission for 'track.flac' was revoked",
            recoveryHint = "Relink the file to continue",
        )
        assertTrue(error.toString().contains("PERMISSION_ERROR"))
        assertTrue(error.toString().contains("Relink the file"))
    }

    @Test
    fun `outcome maps and chains without losing the category`() {
        val success: Outcome<Int> = Outcome.success(21)
        assertEquals(42, success.map { it * 2 }.getOrNull())

        val failure = Outcome.failure(
            ErrorCategory.UNSUPPORTED_FORMAT,
            "Container 'ogg/opus' is not supported",
        )
        assertNull(failure.getOrNull())
        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, failure.errorOrNull()?.category)

        // A failure short-circuits and preserves its category through a chain.
        val chained = failure.flatMap { Outcome.success("unreachable") }
        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, chained.errorOrNull()?.category)
    }
}
