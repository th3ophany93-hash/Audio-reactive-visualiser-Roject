package com.arvs.audio.playback

import com.arvs.core.time.TimeSpan
import com.arvs.core.time.TimelineTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §14.1's export regime: `T = frameIndex / exportFps`, with no drops and no drift. */
class VirtualFrameClockTest {

    @Test
    fun `frame times follow T equals frameIndex over fps`() {
        val clock = VirtualFrameClock.VIDEO_30

        assertEquals(TimelineTime(0), clock.timeAt(0))
        assertEquals(TimelineTime(33_333), clock.timeAt(1))
        assertEquals(TimelineTime(1_000_000), clock.timeAt(30))
        assertEquals(TimelineTime(60_000_000), clock.timeAt(1_800))
    }

    @Test
    fun `broadcast rates are exact fractions, not decimals`() {
        // 29.97 is 30000/1001 exactly. The defining property: 30 000 frames occupy exactly
        // 1001 seconds, not 1000 — the NTSC 0.1% slowdown, which integer arithmetic gets
        // right to the microsecond and a Double does not.
        val ntsc = VirtualFrameClock.NTSC_29_97
        assertEquals(30_000, ntsc.numerator)
        assertEquals(1_001, ntsc.denominator)

        assertEquals(1_001_000_000L, ntsc.timeAt(30_000).micros)
        assertEquals(2_002_000_000L, ntsc.timeAt(60_000).micros)
    }

    @Test
    fun `an exact rational does not drift where a Double would`() {
        // The comparison that justifies the representation: one hour of NTSC frames.
        val ntsc = VirtualFrameClock.NTSC_29_97
        val oneHourOfFrames = 107_892L

        val exactMicros = ntsc.timeAt(oneHourOfFrames).micros
        val naiveMicros = (oneHourOfFrames / 29.97 * 1_000_000.0).toLong()

        assertEquals(3_599_996_400L, exactMicros)
        assertTrue(
            "a Double rate should visibly diverge, differed by ${naiveMicros - exactMicros} us",
            kotlin.math.abs(naiveMicros - exactMicros) > 1_000,
        )
    }

    @Test
    fun `23_976 is also exact`() {
        val film = VirtualFrameClock.FILM_23_976
        assertEquals(24_000, film.numerator)
        assertEquals(1_001, film.denominator)
        assertEquals(1_001_000L, film.timeAt(24).micros) // 24 frames = 1.001 s exactly
    }

    @Test
    fun `frame count rounds up so no tail frame is dropped`() {
        // §14.1: every single frame is rendered. Truncating would silently drop the tail of
        // every export whose length is not a whole number of frames.
        val clock = VirtualFrameClock.VIDEO_30

        assertEquals(30L, clock.frameCountFor(TimeSpan.ofSeconds(1)))
        assertEquals(31L, clock.frameCountFor(TimeSpan(1_000_001)))
        assertEquals(1L, clock.frameCountFor(TimeSpan(1)))
        assertEquals(0L, clock.frameCountFor(TimeSpan.ZERO))
    }

    @Test
    fun `frame enumeration covers every index with no gaps`() {
        val clock = VirtualFrameClock.VIDEO_60
        val indices = clock.frameIndices(TimeSpan.ofSeconds(2)).toList()

        assertEquals(120, indices.size)
        assertEquals(0L, indices.first())
        assertEquals(119L, indices.last())
        indices.zipWithNext { a, b -> assertEquals(1L, b - a) }
    }

    @Test
    fun `frame index is the inverse of frame time`() {
        listOf(
            VirtualFrameClock.FILM_24,
            VirtualFrameClock.PAL_25,
            VirtualFrameClock.VIDEO_30,
            VirtualFrameClock.VIDEO_60,
            VirtualFrameClock.NTSC_29_97,
        ).forEach { clock ->
            (0L until 500L step 37).forEach { frame ->
                assertEquals("$clock frame $frame", frame, clock.frameIndexAt(clock.timeAt(frame)))
            }
        }
    }

    @Test
    fun `frame duration matches the nominal rate`() {
        assertEquals(TimeSpan(33_333), VirtualFrameClock.VIDEO_30.frameDuration)
        assertEquals(TimeSpan(16_666), VirtualFrameClock.VIDEO_60.frameDuration)
        assertEquals(TimeSpan(40_000), VirtualFrameClock.PAL_25.frameDuration)
    }

    @Test
    fun `fps is exposed for display but the arithmetic never uses it`() {
        assertEquals(29.97, VirtualFrameClock.NTSC_29_97.fps, 0.001)
        assertEquals("30000/1001 fps", VirtualFrameClock.NTSC_29_97.toString())
        assertEquals("30fps", VirtualFrameClock.VIDEO_30.toString())
    }

    @Test
    fun `degenerate rates are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { VirtualFrameClock(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { VirtualFrameClock(30, 0) }
        assertThrows(IllegalArgumentException::class.java) { VirtualFrameClock(-30, 1) }
    }

    @Test
    fun `negative frame indices and durations are rejected`() {
        val clock = VirtualFrameClock.VIDEO_30
        assertThrows(IllegalArgumentException::class.java) { clock.timeAt(-1) }
        assertThrows(IllegalArgumentException::class.java) { clock.frameCountFor(TimeSpan(-1)) }
        assertThrows(IllegalArgumentException::class.java) { clock.frameIndexAt(TimelineTime(-1)) }
    }

    @Test
    fun `the export clock is a value, comparable by rate`() {
        assertEquals(VirtualFrameClock(30, 1), VirtualFrameClock.ofIntegerFps(30))
        assertEquals(VirtualFrameClock(30, 1).hashCode(), VirtualFrameClock.ofIntegerFps(30).hashCode())
    }
}
