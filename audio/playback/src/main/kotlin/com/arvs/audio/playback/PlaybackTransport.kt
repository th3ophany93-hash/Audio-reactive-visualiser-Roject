package com.arvs.audio.playback

import com.arvs.core.diagnostics.DiagnosticsClock
import com.arvs.core.diagnostics.SystemDiagnosticsClock
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.TimeSpan
import com.arvs.core.time.TimelineTime
import com.arvs.core.time.TrimMapping
import java.util.concurrent.atomic.AtomicLong

/**
 * The playback transport of §16, and the state around §14.1's master clock.
 *
 * Scope is §16's *playback* controls — play, pause, seek, preview loop, volume, mute, reset,
 * and the selection fades. §16's waveform, zoom, pan and snap are Trim-editor concerns and
 * belong to Phase 2's UI (§103); they are deliberately absent here.
 *
 * Everything in this class is pure state and arithmetic, with no Android in sight. The
 * platform half — an `AudioTrack` fed with decoded PCM — lives in [AudioTrackPlayer] and
 * talks to this through [MasterClock] and [reportUnderrun]. That split is what lets §14.1's
 * clock semantics, §16's boundary behaviour and the fade curves be unit-tested on the JVM,
 * where a device would otherwise be needed to observe any of them.
 */
public class PlaybackTransport(
    trim: TrimMapping,
    diagnosticsClock: DiagnosticsClock = SystemDiagnosticsClock,
) {
    /** §14.1's master clock for the preview regime. It owns the trim; see [MasterClock.trim]. */
    public val clock: MasterClock = MasterClock(trim, diagnosticsClock)

    /**
     * The trimmed selection. Delegates to [clock] rather than storing a second copy — the
     * clock clamps and converts against it on every call, so a divergent copy here would
     * produce answers that are wrong without ever being inconsistent-looking.
     */
    public val trim: TrimMapping get() = clock.trim

    private val underruns = AtomicLong(0)

    /** §16's preview loop, over the trimmed selection. */
    public var isLooping: Boolean = false
        private set

    /** §16's volume. Unity is 1.0; see [setGain] on the range. */
    public var gain: Float = 1.0f
        private set

    /** §16's mute. Independent of [gain], so unmuting restores the previous volume. */
    public var isMuted: Boolean = false
        private set

    public var fadeIn: TimeSpan = TimeSpan.ZERO
        private set

    public var fadeOut: TimeSpan = TimeSpan.ZERO
        private set

    public val state: PlaybackState get() = clock.state()

    // --- transport ------------------------------------------------------------------------

    public fun play() {
        // Starting from the very end restarts, rather than playing nothing. A play button that
        // appears to do nothing is worse than one that does the obvious thing.
        val from = if (clock.hasReachedSelectionEnd()) trim.trimIn else clock.currentAudioSourceTime()
        clock.onPlaybackStarted(from)
    }

    public fun pause(): Unit = clock.onPlaybackPaused()

    /** §16's reset: back to the selection start, stopped. */
    public fun stop(): Unit = clock.onStopped()

    /** Seeks in timeline time — the domain §14.1 gives the transport's callers. */
    public fun seekTo(time: TimelineTime) {
        clock.onSeeked(trim.toAudioTime(clampToSelection(time)))
    }

    /** Current playhead, in §14.1's timeline domain. */
    public fun currentTime(): TimelineTime = clock.currentTimelineTime()

    /**
     * Replaces the trim points (§16's in/out).
     *
     * The playhead is kept where it is in **audio-source** time and merely re-clamped, so
     * dragging a trim handle does not teleport the playhead to a different piece of audio.
     * §9.1's consequence — changing trim invalidates nothing that is stored — has the same
     * shape: trim changes the offset, not the content.
     */
    public fun setTrim(newTrim: TrimMapping): Unit = clock.retrim(newTrim)

    // --- §16 controls ----------------------------------------------------------------------

    public fun setLooping(looping: Boolean) {
        isLooping = looping
    }

    /**
     * Sets playback volume.
     *
     * Bounded to `[0, 1]`: §16 calls this "volume" and does not give it a range, and a preview
     * transport that permits boost above unity can clip audio the user will not hear clipped
     * anywhere else. Chosen conservatively and stated rather than assumed.
     */
    public fun setGain(newGain: Float) {
        require(newGain in 0.0f..1.0f) { "gain must be within [0,1]: $newGain" }
        gain = newGain
    }

    public fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    /** §16's selection fade in / fade out. */
    public fun setFades(fadeInSpan: TimeSpan, fadeOutSpan: TimeSpan) {
        require(fadeInSpan.micros >= 0 && fadeOutSpan.micros >= 0) { "fades must not be negative" }
        fadeIn = fadeInSpan
        fadeOut = fadeOutSpan
    }

    /**
     * The gain actually applied at [time], combining volume, mute and the §16 fades.
     *
     * Fades are linear in amplitude and measured from the selection's own edges, not the
     * asset's — they belong to the selection, so moving a trim handle moves the fade with it.
     *
     * Overlapping fades multiply rather than one winning. On a selection shorter than
     * `fadeIn + fadeOut` that is the only reading that stays continuous; letting either
     * override would put a step in the middle of a fade, which is audible as a click.
     */
    public fun effectiveGainAt(time: TimelineTime): Float {
        if (isMuted) return 0.0f
        // TrimMapping guarantees a strictly positive duration, so there is no zero-length
        // case to guard against here — the type made it unrepresentable in Step 2.
        val selection = trim.duration
        val position = clampToSelection(time).micros
        var envelope = 1.0f

        if (fadeIn.micros > 0) {
            envelope *= (position.toFloat() / fadeIn.micros).coerceIn(0.0f, 1.0f)
        }
        if (fadeOut.micros > 0) {
            val remaining = selection.micros - position
            envelope *= (remaining.toFloat() / fadeOut.micros).coerceIn(0.0f, 1.0f)
        }
        return gain * envelope
    }

    // --- boundary behaviour ------------------------------------------------------------------

    /**
     * What should happen now that the playhead has been advanced.
     *
     * Returned as a value for the platform layer to act on rather than acted on here, so
     * §16's loop semantics and the end-of-selection stop are decidable — and testable —
     * without an `AudioTrack`.
     */
    public fun boundaryAction(): BoundaryAction = when {
        !clock.hasReachedSelectionEnd() -> BoundaryAction.CONTINUE
        isLooping -> BoundaryAction.LOOP
        else -> BoundaryAction.STOP_AT_END
    }

    /** Applies [boundaryAction]. Returns true when the transport moved. */
    public fun applyBoundaryAction(): Boolean = when (boundaryAction()) {
        BoundaryAction.CONTINUE -> false
        BoundaryAction.LOOP -> {
            // Back to the selection start, still playing. §16's preview loop.
            clock.onSeeked(trim.trimIn)
            true
        }
        BoundaryAction.STOP_AT_END -> {
            // Held at the end, not rewound: the playhead marks where playback finished.
            clock.onPlaybackPaused()
            true
        }
    }

    // --- §14.1's no-dropout guarantee -------------------------------------------------------

    /**
     * Records an audio underrun.
     *
     * §14.1: "dropped or resampled audio is not acceptable under any circumstance." The plan
     * makes `underrunCount == 0` a release-blocking assertion under analysis load, so the
     * count exists to be asserted on, not merely logged.
     */
    public fun reportUnderrun() {
        underruns.incrementAndGet()
    }

    public fun underrunCount(): Long = underruns.get()

    public fun resetUnderrunCount() {
        underruns.set(0)
    }

    private fun clampToSelection(time: TimelineTime): TimelineTime {
        val duration = trim.duration
        return when {
            time.micros < 0 -> TimelineTime(0)
            time.micros > duration.micros -> TimelineTime(duration.micros)
            else -> time
        }
    }
}

/** What the transport should do at the end of the trimmed selection (§16). */
public enum class BoundaryAction {
    /** Not at the end; keep playing. */
    CONTINUE,

    /** §16's preview loop: wrap to the selection start and keep playing. */
    LOOP,

    /** Hold at the end. */
    STOP_AT_END,
}

/** Where playback currently is, for a platform player to report (§14.1). */
public data class PlaybackPosition(
    public val audioSourceTime: AudioSourceTime,
    public val state: PlaybackState,
)
