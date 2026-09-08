package com.arvs.audio.playback

import com.arvs.core.diagnostics.ManualDiagnosticsClock
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.TimeSpan
import com.arvs.core.time.TimelineTime
import com.arvs.core.time.TrimMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §16's playback transport and the boundary behaviour around the trimmed selection. */
class PlaybackTransportTest {

    private val trim = TrimMapping(
        trimIn = AudioSourceTime.ofSeconds(10.0),
        trimOut = AudioSourceTime.ofSeconds(40.0),
    )

    private fun transportPair(): Pair<ManualDiagnosticsClock, PlaybackTransport> {
        val wall = ManualDiagnosticsClock()
        return wall to PlaybackTransport(trim, wall)
    }

    // --- play / pause / stop -----------------------------------------------------------

    @Test
    fun `play starts from the selection start and advances`() {
        val (wall, transport) = transportPair()
        transport.play()
        assertEquals(TimelineTime(0), transport.currentTime())

        wall.advanceMillis(2_000)
        assertEquals(TimelineTime(2_000_000), transport.currentTime())
    }

    @Test
    fun `pause holds the playhead and resume continues from it`() {
        val (wall, transport) = transportPair()
        transport.play()
        wall.advanceMillis(5_000)
        transport.pause()

        wall.advanceMillis(30_000)
        assertEquals(TimelineTime(5_000_000), transport.currentTime())

        transport.play()
        wall.advanceMillis(1_000)
        assertEquals(TimelineTime(6_000_000), transport.currentTime())
    }

    @Test
    fun `stop is section 16's reset - back to the selection start`() {
        val (wall, transport) = transportPair()
        transport.play()
        wall.advanceMillis(9_000)

        transport.stop()

        assertEquals(TimelineTime(0), transport.currentTime())
        assertEquals(PlaybackState.STOPPED, transport.state)
    }

    @Test
    fun `pressing play at the end restarts rather than doing nothing`() {
        // A play button that appears inert is worse than one that does the obvious thing.
        val (wall, transport) = transportPair()
        transport.play()
        wall.advanceMillis(60_000) // well past trimOut
        assertTrue(transport.clock.hasReachedSelectionEnd())

        transport.play()

        assertEquals(TimelineTime(0), transport.currentTime())
        assertEquals(PlaybackState.PLAYING, transport.state)
    }

    // --- seeking -------------------------------------------------------------------------

    @Test
    fun `seeking takes timeline time and lands on the right audio`() {
        val (_, transport) = transportPair()
        transport.seekTo(TimelineTime(15_000_000))

        assertEquals(TimelineTime(15_000_000), transport.currentTime())
        assertEquals(AudioSourceTime.ofSeconds(25.0), transport.clock.currentAudioSourceTime())
    }

    @Test
    fun `seeking outside the selection is clamped at both ends`() {
        val (_, transport) = transportPair()

        transport.seekTo(TimelineTime(-9_000_000))
        assertEquals(TimelineTime(0), transport.currentTime())

        transport.seekTo(TimelineTime(999_000_000))
        assertEquals(TimelineTime(trim.duration.micros), transport.currentTime())
    }

    @Test
    fun `seeking while playing keeps playing from the new position`() {
        val (wall, transport) = transportPair()
        transport.play()
        wall.advanceMillis(1_000)

        transport.seekTo(TimelineTime(20_000_000))
        wall.advanceMillis(1_000)

        assertEquals(TimelineTime(21_000_000), transport.currentTime())
        assertEquals(PlaybackState.PLAYING, transport.state)
    }

    // --- trim changes ----------------------------------------------------------------------

    @Test
    fun `changing trim keeps the playhead on the same audio`() {
        // §9.1's shape: trim changes the offset, not the content. Teleporting the playhead to
        // different audio when a handle moves would make dragging unusable.
        val (_, transport) = transportPair()
        transport.seekTo(TimelineTime(10_000_000)) // audio-source 20 s
        assertEquals(AudioSourceTime.ofSeconds(20.0), transport.clock.currentAudioSourceTime())

        transport.setTrim(
            TrimMapping(AudioSourceTime.ofSeconds(5.0), AudioSourceTime.ofSeconds(35.0)),
        )

        assertEquals(AudioSourceTime.ofSeconds(20.0), transport.clock.currentAudioSourceTime())
        assertEquals(TimelineTime(15_000_000), transport.currentTime()) // re-expressed, not moved
    }

    @Test
    fun `a trim change that excludes the playhead clamps it into the new selection`() {
        val (_, transport) = transportPair()
        transport.seekTo(TimelineTime(25_000_000)) // audio-source 35 s

        transport.setTrim(
            TrimMapping(AudioSourceTime.ofSeconds(10.0), AudioSourceTime.ofSeconds(20.0)),
        )

        assertEquals(AudioSourceTime.ofSeconds(20.0), transport.clock.currentAudioSourceTime())
    }

    // --- §16 preview loop and end behaviour -------------------------------------------------

    @Test
    fun `without looping the transport stops at the end and holds there`() {
        val (wall, transport) = transportPair()
        transport.play()
        wall.advanceMillis(30_000)

        assertEquals(BoundaryAction.STOP_AT_END, transport.boundaryAction())
        assertTrue(transport.applyBoundaryAction())

        assertEquals(PlaybackState.PAUSED, transport.state)
        assertEquals(TimelineTime(trim.duration.micros), transport.currentTime())
    }

    @Test
    fun `with looping the transport wraps to the selection start and keeps playing`() {
        val (wall, transport) = transportPair()
        transport.setLooping(true)
        transport.play()
        wall.advanceMillis(30_000)

        assertEquals(BoundaryAction.LOOP, transport.boundaryAction())
        assertTrue(transport.applyBoundaryAction())

        assertEquals(TimelineTime(0), transport.currentTime())
        assertEquals(PlaybackState.PLAYING, transport.state)
    }

    @Test
    fun `looping repeats indefinitely without drifting`() {
        val (wall, transport) = transportPair()
        transport.setLooping(true)
        transport.play()

        repeat(10) {
            wall.advanceMillis(30_000)
            transport.applyBoundaryAction()
            assertEquals(TimelineTime(0), transport.currentTime())
        }
        assertEquals(PlaybackState.PLAYING, transport.state)
    }

    @Test
    fun `mid-selection the boundary action is to continue`() {
        val (wall, transport) = transportPair()
        transport.play()
        wall.advanceMillis(10_000)

        assertEquals(BoundaryAction.CONTINUE, transport.boundaryAction())
        assertFalse(transport.applyBoundaryAction())
    }

    // --- §16 volume, mute and fades ------------------------------------------------------

    @Test
    fun `mute silences without discarding the volume setting`() {
        val (_, transport) = transportPair()
        transport.setGain(0.7f)

        transport.setMuted(true)
        assertEquals(0.0f, transport.effectiveGainAt(TimelineTime(15_000_000)))
        assertEquals(0.7f, transport.gain) // the setting survives

        transport.setMuted(false)
        assertEquals(0.7f, transport.effectiveGainAt(TimelineTime(15_000_000)))
    }

    @Test
    fun `gain outside zero to one is rejected`() {
        val (_, transport) = transportPair()
        assertThrows(IllegalArgumentException::class.java) { transport.setGain(-0.1f) }
        assertThrows(IllegalArgumentException::class.java) { transport.setGain(1.5f) }
    }

    @Test
    fun `the fade in ramps linearly from the selection start`() {
        val (_, transport) = transportPair()
        transport.setFades(TimeSpan.ofSeconds(2), TimeSpan.ZERO)

        assertEquals(0.0f, transport.effectiveGainAt(TimelineTime(0)), 1e-6f)
        assertEquals(0.5f, transport.effectiveGainAt(TimelineTime(1_000_000)), 1e-6f)
        assertEquals(1.0f, transport.effectiveGainAt(TimelineTime(2_000_000)), 1e-6f)
        assertEquals(1.0f, transport.effectiveGainAt(TimelineTime(10_000_000)), 1e-6f)
    }

    @Test
    fun `the fade out ramps linearly to the selection end`() {
        val (_, transport) = transportPair()
        transport.setFades(TimeSpan.ZERO, TimeSpan.ofSeconds(4))
        val end = trim.duration.micros

        assertEquals(1.0f, transport.effectiveGainAt(TimelineTime(end - 4_000_000)), 1e-6f)
        assertEquals(0.5f, transport.effectiveGainAt(TimelineTime(end - 2_000_000)), 1e-6f)
        assertEquals(0.0f, transport.effectiveGainAt(TimelineTime(end)), 1e-6f)
    }

    @Test
    fun `fades are measured from the selection edges, so they move with the trim`() {
        val (_, transport) = transportPair()
        transport.setFades(TimeSpan.ofSeconds(2), TimeSpan.ZERO)
        assertEquals(0.5f, transport.effectiveGainAt(TimelineTime(1_000_000)), 1e-6f)

        transport.setTrim(TrimMapping(AudioSourceTime.ofSeconds(20.0), AudioSourceTime.ofSeconds(50.0)))

        // Still half-way through the fade at one second into the *new* selection.
        assertEquals(0.5f, transport.effectiveGainAt(TimelineTime(1_000_000)), 1e-6f)
    }

    @Test
    fun `overlapping fades multiply instead of one overriding the other`() {
        // On a selection shorter than fadeIn + fadeOut, letting either win would put a step in
        // the middle of a fade, which is audible as a click.
        val shortTrim = TrimMapping(AudioSourceTime.ofSeconds(0.0), AudioSourceTime.ofSeconds(2.0))
        val transport = PlaybackTransport(shortTrim, ManualDiagnosticsClock())
        transport.setFades(TimeSpan.ofSeconds(2), TimeSpan.ofSeconds(2))

        // At the midpoint both fades are at 0.5, so the envelope is 0.25 — continuous.
        assertEquals(0.25f, transport.effectiveGainAt(TimelineTime(1_000_000)), 1e-6f)
        assertEquals(0.0f, transport.effectiveGainAt(TimelineTime(0)), 1e-6f)
        assertEquals(0.0f, transport.effectiveGainAt(TimelineTime(2_000_000)), 1e-6f)

        // Monotone up then down, with no step anywhere.
        val samples = (0..20).map { transport.effectiveGainAt(TimelineTime(it * 100_000L)) }
        samples.zipWithNext { a, b -> assertTrue("step of ${b - a}", kotlin.math.abs(b - a) < 0.2f) }
    }

    @Test
    fun `gain multiplies the fade envelope rather than replacing it`() {
        val (_, transport) = transportPair()
        transport.setGain(0.5f)
        transport.setFades(TimeSpan.ofSeconds(2), TimeSpan.ZERO)

        assertEquals(0.25f, transport.effectiveGainAt(TimelineTime(1_000_000)), 1e-6f)
    }

    @Test
    fun `with no fades set the gain is applied flat`() {
        val (_, transport) = transportPair()
        transport.setGain(0.8f)
        listOf(0L, 1_000_000L, 29_999_999L).forEach { micros ->
            assertEquals(0.8f, transport.effectiveGainAt(TimelineTime(micros)), 1e-6f)
        }
    }

    @Test
    fun `negative fades are rejected`() {
        val (_, transport) = transportPair()
        assertThrows(IllegalArgumentException::class.java) {
            transport.setFades(TimeSpan(-1), TimeSpan.ZERO)
        }
    }

    // --- §14.1's no-dropout guarantee -------------------------------------------------------

    @Test
    fun `underruns are counted so the release-blocking assertion has something to assert`() {
        val (_, transport) = transportPair()
        assertEquals(0L, transport.underrunCount())

        repeat(3) { transport.reportUnderrun() }
        assertEquals(3L, transport.underrunCount())

        transport.resetUnderrunCount()
        assertEquals(0L, transport.underrunCount())
    }

    @Test
    fun `underrun counting is safe from many threads`() {
        // §101 runs audio on its own thread while analysis runs on others.
        val (_, transport) = transportPair()
        val threads = (0 until 8).map { Thread { repeat(1_000) { transport.reportUnderrun() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(8_000L, transport.underrunCount())
    }
}
