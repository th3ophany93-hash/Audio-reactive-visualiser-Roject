package com.arvs.audio.playback

import com.arvs.core.diagnostics.ManualDiagnosticsClock
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.TimelineTime
import com.arvs.core.time.TrimMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins §14.1's preview clock-authority regime.
 *
 * The trim used throughout starts at 10 s and ends at 40 s, so the two time domains never
 * coincide numerically. A clock that returned audio-source time where timeline time was
 * required would pass every test written against `trimIn = 0`, which is exactly the bug the
 * two-type split in `core:time` exists to prevent — the tests have to be able to see it too.
 */
class MasterClockTest {

    private val trim = TrimMapping(
        trimIn = AudioSourceTime.ofSeconds(10.0),
        trimOut = AudioSourceTime.ofSeconds(40.0),
    )

    private fun clockPair(): Pair<ManualDiagnosticsClock, MasterClock> {
        val wall = ManualDiagnosticsClock()
        return wall to MasterClock(trim, wall)
    }

    // --- domain separation -------------------------------------------------------------

    @Test
    fun `timeline time is measured from the selection start, not the asset start`() {
        // §14.1: T is timeline time, relative to the trimmed selection's own t=0.
        val (_, clock) = clockPair()
        clock.onSeeked(AudioSourceTime.ofSeconds(25.0))

        assertEquals(AudioSourceTime.ofSeconds(25.0), clock.currentAudioSourceTime())
        assertEquals(TimelineTime(15_000_000), clock.currentTimelineTime())
    }

    @Test
    fun `the two domains stay distinct across the whole selection`() {
        val (_, clock) = clockPair()
        listOf(10.0, 17.5, 25.0, 39.9).forEach { seconds ->
            clock.onSeeked(AudioSourceTime.ofSeconds(seconds))
            assertEquals(
                "domain offset must remain exactly trimIn",
                trim.trimIn.micros,
                clock.currentAudioSourceTime().micros - clock.currentTimelineTime().micros,
            )
        }
    }

    // --- rate ---------------------------------------------------------------------------

    @Test
    fun `while playing the clock advances at exactly one to one`() {
        // §14.1 forbids resampled audio; a clock running at any other rate would demand it.
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))

        wall.advanceMillis(1_000)
        assertEquals(11_000_000L, clock.currentAudioSourceTime().micros)

        wall.advanceMillis(2_500)
        assertEquals(13_500_000L, clock.currentAudioSourceTime().micros)
    }

    @Test
    fun `while paused the clock does not advance at all`() {
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))
        wall.advanceMillis(1_000)
        clock.onPlaybackPaused()

        val frozen = clock.currentAudioSourceTime()
        wall.advanceMillis(60_000)

        assertEquals(frozen, clock.currentAudioSourceTime())
        assertEquals(PlaybackState.PAUSED, clock.state())
    }

    @Test
    fun `a stopped clock does not advance`() {
        val (wall, clock) = clockPair()
        clock.onStopped()
        wall.advanceMillis(5_000)
        assertEquals(trim.trimIn, clock.currentAudioSourceTime())
    }

    // --- pause and resume ----------------------------------------------------------------

    @Test
    fun `pause captures the extrapolated position, losing no time`() {
        // Freezing to the stale anchor instead would rewind the playhead by up to one buffer
        // on every pause — small, and cumulative across repeated pause/resume.
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))
        clock.onPositionReported(AudioSourceTime.ofSeconds(10.0))

        wall.advanceMillis(750) // no new report: this is pure extrapolation
        clock.onPlaybackPaused()

        assertEquals(10_750_000L, clock.currentAudioSourceTime().micros)
    }

    @Test
    fun `pause and resume neither loses nor invents time`() {
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))

        repeat(20) {
            wall.advanceMillis(100)
            clock.onPlaybackPaused()
            wall.advanceMillis(5_000) // paused: must contribute nothing
            clock.onPlaybackStarted(clock.currentAudioSourceTime())
        }
        wall.advanceMillis(100)

        // 21 playing intervals of 100 ms, and none of the paused time.
        assertEquals(12_100_000L, clock.currentAudioSourceTime().micros)
    }

    // --- extrapolation and re-anchoring ---------------------------------------------------

    @Test
    fun `an authoritative report overrides extrapolation`() {
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))
        wall.advanceMillis(500)
        assertEquals(10_500_000L, clock.currentAudioSourceTime().micros)

        // The player says it is actually slightly behind. The clock must believe it.
        clock.onPositionReported(AudioSourceTime.ofSeconds(10.4))
        assertEquals(10_400_000L, clock.currentAudioSourceTime().micros)
    }

    @Test
    fun `extrapolation error is bounded by one report interval, never integrated`() {
        // Each report re-anchors, so a persistently late player does not accumulate drift.
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))

        var reported = 10.0
        repeat(100) {
            wall.advanceMillis(20)
            reported += 0.019 // the player runs 1 ms slow per 20 ms report
            clock.onPositionReported(AudioSourceTime.ofSeconds(reported))
        }

        // Error tracks the player, and is not the sum of 100 extrapolation gaps.
        assertEquals(reported * 1_000_000.0, clock.currentAudioSourceTime().micros.toDouble(), 1_000.0)
    }

    @Test
    fun `extrapolation is reported as such`() {
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))
        assertFalse(clock.isExtrapolating())
        wall.advanceMillis(10)
        assertTrue(clock.isExtrapolating())

        clock.onPlaybackPaused()
        assertFalse(clock.isExtrapolating())
    }

    // --- seeking ---------------------------------------------------------------------------

    @Test
    fun `seeking while playing resets the anchor rather than compounding`() {
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))
        wall.advanceMillis(3_000)

        clock.onSeeked(AudioSourceTime.ofSeconds(20.0))
        assertEquals(20_000_000L, clock.currentAudioSourceTime().micros)

        wall.advanceMillis(1_000)
        assertEquals(21_000_000L, clock.currentAudioSourceTime().micros)
    }

    @Test
    fun `seeking while paused does not start the clock`() {
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(10.0))
        clock.onPlaybackPaused()

        clock.onSeeked(AudioSourceTime.ofSeconds(30.0))
        wall.advanceMillis(5_000)

        assertEquals(30_000_000L, clock.currentAudioSourceTime().micros)
        assertEquals(PlaybackState.PAUSED, clock.state())
    }

    // --- boundaries -------------------------------------------------------------------------

    @Test
    fun `the clock never reports outside the trimmed selection`() {
        val (wall, clock) = clockPair()

        clock.onSeeked(AudioSourceTime.ofSeconds(0.0))     // before trimIn
        assertEquals(trim.trimIn, clock.currentAudioSourceTime())
        assertEquals(TimelineTime(0), clock.currentTimelineTime())

        clock.onSeeked(AudioSourceTime.ofSeconds(999.0))   // past trimOut
        assertEquals(trim.trimOut, clock.currentAudioSourceTime())
        assertEquals(TimelineTime(trim.duration.micros), clock.currentTimelineTime())

        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(39.9))
        wall.advanceMillis(60_000)
        assertEquals(trim.trimOut, clock.currentAudioSourceTime())
    }

    @Test
    fun `timeline time is never negative`() {
        // A negative T would be outside the domain §14.1 defines, and would index the analysis
        // cache before the epoch.
        val (_, clock) = clockPair()
        clock.onSeeked(AudioSourceTime(-5_000_000))
        assertTrue(clock.currentTimelineTime().micros >= 0)
    }

    @Test
    fun `reaching the selection end is detectable`() {
        val (wall, clock) = clockPair()
        clock.onPlaybackStarted(AudioSourceTime.ofSeconds(39.0))
        assertFalse(clock.hasReachedSelectionEnd())

        wall.advanceMillis(1_000)
        assertTrue(clock.hasReachedSelectionEnd())
    }

    @Test
    fun `a fresh clock reports the selection start, not zero`() {
        val (_, clock) = clockPair()
        assertEquals(trim.trimIn, clock.currentAudioSourceTime())
        assertEquals(TimelineTime(0), clock.currentTimelineTime())
        assertEquals(PlaybackState.STOPPED, clock.state())
    }

    @Test
    fun `a zero-length selection is unrepresentable, so the clock never has to handle one`() {
        // Step 2's TrimMapping already rejects trimOut <= trimIn at construction. That is a
        // stronger guarantee than handling the case defensively here: the clock cannot be
        // handed a selection it would have to divide by, so there is no branch to get wrong
        // and no dead guard pretending the case is reachable.
        assertThrows(IllegalArgumentException::class.java) {
            TrimMapping(AudioSourceTime.ofSeconds(5.0), AudioSourceTime.ofSeconds(5.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrimMapping(AudioSourceTime.ofSeconds(5.0), AudioSourceTime.ofSeconds(4.0))
        }
    }

    @Test
    fun `the shortest representable selection still behaves`() {
        val oneMicro = TrimMapping(AudioSourceTime(1_000_000), AudioSourceTime(1_000_001))
        val wall = ManualDiagnosticsClock()
        val clock = MasterClock(oneMicro, wall)

        clock.onPlaybackStarted()
        assertEquals(TimelineTime(0), clock.currentTimelineTime())
        wall.advanceMillis(1_000)
        assertEquals(TimelineTime(1), clock.currentTimelineTime())
        assertTrue(clock.hasReachedSelectionEnd())
    }
}
