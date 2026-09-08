package com.arvs.audio.playback

import com.arvs.core.diagnostics.DiagnosticsClock
import com.arvs.core.diagnostics.SystemDiagnosticsClock
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.TimeSpan
import com.arvs.core.time.TimelineTime
import com.arvs.core.time.TrimMapping

/**
 * §14.1's **preview** clock-authority regime: the audio playback clock is master.
 *
 * §14.1 is unambiguous about the hierarchy — "the media playback position … drives the render
 * timestamp `T` requested from the RenderGraph each frame. Dropped video frames are
 * acceptable …; dropped or resampled audio is not acceptable under any circumstance." So this
 * class never asks audio to wait for anything. It reads where audio *is* and reports the
 * timeline time that corresponds to it.
 *
 * ### The domain conversion, and why it is here
 *
 * An audio player reports its position within the **raw asset** — [AudioSourceTime], §9.1's
 * epoch domain. `T` is defined by §14.1 as **timeline time**, relative to the trimmed
 * selection's own `t = 0`. Producing `T` therefore requires `T = audioPosition − trimIn`.
 *
 * This is the *inverse* of the `audioT = T + trimIn` translation §14.1 confines to
 * `ParameterResolver`, and it sits **upstream** of the parameter-resolution pipeline rather
 * than inside it — it produces that pipeline's input. P-5 scopes §14.1's "and nowhere else"
 * to the render/parameter-resolution pipeline, and names the Trim Editor as a permitted
 * conversion site outside it. A master clock is a third such site, and structurally it cannot
 * be otherwise: `T` has to be derived from a player position somewhere, and there is no
 * position source that speaks timeline time. Flagged as observation T-5 rather than treated
 * as settled — §14.1 and P-5 are unmodified.
 *
 * ### Why the clock extrapolates
 *
 * A player reports position per audio buffer, tens of milliseconds apart; the renderer asks
 * for `T` every frame. Reporting the last known position unchanged between updates would
 * stutter the video to the buffer rate. So the clock anchors on the most recent authoritative
 * sample and advances from it using a monotonic clock at **exactly 1.0** — it never applies a
 * rate of its own. Every authoritative sample re-anchors, so extrapolation error cannot
 * accumulate: it is bounded by one reporting interval and is corrected, not integrated.
 */
public class MasterClock(
    trim: TrimMapping,
    private val clock: DiagnosticsClock = SystemDiagnosticsClock,
) {
    /**
     * The trimmed selection this clock reports against.
     *
     * Stored here and **only** here. An earlier draft kept a second copy on
     * [PlaybackTransport] and updated that one when a trim handle moved, leaving the clock
     * clamping and converting against a stale selection — a silent wrong answer of exactly
     * the kind two sources of truth produce. [retrim] is the single mutation point.
     */
    public var trim: TrimMapping = trim
        private set

    private var anchor: Anchor? = null
    private var state: PlaybackState = PlaybackState.STOPPED

    /** Where playback currently is in the raw asset, and when that was true. */
    private data class Anchor(val position: AudioSourceTime, val monotonicNanos: Long)

    /** Current transport state. The clock advances only in [PlaybackState.PLAYING]. */
    public fun state(): PlaybackState = state

    /**
     * Accepts an authoritative position report from the player.
     *
     * Every call re-anchors, which is what keeps extrapolation from drifting: error is bounded
     * by one reporting interval rather than integrated over the session.
     */
    public fun onPositionReported(position: AudioSourceTime) {
        anchor = Anchor(clampToSelection(position), clock.monotonicNanos())
    }

    /** Begins advancing. Without a prior position the clock starts at the selection's start. */
    public fun onPlaybackStarted(position: AudioSourceTime = trim.trimIn) {
        anchor = Anchor(clampToSelection(position), clock.monotonicNanos())
        state = PlaybackState.PLAYING
    }

    /**
     * Freezes the clock at the position it had reached.
     *
     * The current extrapolated position is captured *before* the state changes, so a pause
     * neither loses nor invents the time since the last report. Freezing to the stale anchor
     * instead would rewind the playhead by up to one buffer on every pause — small, cumulative
     * over repeated pause/resume, and exactly the kind of drift §9.1 forbids elsewhere.
     */
    public fun onPlaybackPaused() {
        if (state == PlaybackState.PLAYING) {
            anchor = Anchor(currentAudioSourceTime(), clock.monotonicNanos())
        }
        state = PlaybackState.PAUSED
    }

    /** Moves the playhead. Valid whether playing or paused; the anchor is reset either way. */
    public fun onSeeked(position: AudioSourceTime) {
        anchor = Anchor(clampToSelection(position), clock.monotonicNanos())
    }

    /**
     * Replaces the trimmed selection, keeping the playhead on the same audio.
     *
     * The position is held in §9.1's epoch domain and merely re-clamped, so moving a trim
     * handle re-expresses where the playhead is rather than moving it to different audio.
     */
    public fun retrim(newTrim: TrimMapping) {
        val positionBefore = currentAudioSourceTime()
        trim = newTrim
        anchor = Anchor(clampToSelection(positionBefore), clock.monotonicNanos())
    }

    /** Returns to the selection start and stops. §16's "reset". */
    public fun onStopped() {
        anchor = Anchor(trim.trimIn, clock.monotonicNanos())
        state = PlaybackState.STOPPED
    }

    /**
     * Current position in §9.1's epoch domain, extrapolated when playing.
     *
     * Clamped to the trimmed selection: the player is not asked to play outside it, and a
     * position beyond [TrimMapping.trimOut] has no timeline time to map to.
     */
    public fun currentAudioSourceTime(): AudioSourceTime {
        val current = anchor ?: return trim.trimIn
        if (state != PlaybackState.PLAYING) return current.position
        val elapsedNanos = clock.monotonicNanos() - current.monotonicNanos
        if (elapsedNanos <= 0) return current.position
        // Rate is exactly 1.0. §14.1 forbids resampled audio, and a clock that ran at any
        // other rate would be asking for exactly that.
        return clampToSelection(current.position + TimeSpan(elapsedNanos / 1_000))
    }

    /**
     * §14.1's `T` — the timestamp the RenderGraph is asked for.
     *
     * Timeline time, relative to the trimmed selection's `t = 0`.
     */
    public fun currentTimelineTime(): TimelineTime = trim.toTimelineTime(currentAudioSourceTime())

    /** Whether the reported time is extrapolated rather than an authoritative report. */
    public fun isExtrapolating(): Boolean {
        val current = anchor ?: return false
        return state == PlaybackState.PLAYING && clock.monotonicNanos() > current.monotonicNanos
    }

    /** True once the playhead has reached the end of the trimmed selection. */
    public fun hasReachedSelectionEnd(): Boolean = currentAudioSourceTime() >= trim.trimOut

    private fun clampToSelection(position: AudioSourceTime): AudioSourceTime = when {
        position < trim.trimIn -> trim.trimIn
        position > trim.trimOut -> trim.trimOut
        else -> position
    }
}

/**
 * Transport state (§14, §16).
 *
 * Three states, not four: there is no "buffering" here because §14.1 makes audio the master —
 * the clock reports where audio is, and audio is either advancing or it is not. A separate
 * buffering state would be the renderer's concern, and the renderer is Phase 2.
 */
public enum class PlaybackState {
    /** Not started, or reset to the selection start (§16's "reset"). */
    STOPPED,

    /** Position held. The clock does not advance. */
    PAUSED,

    /** Advancing at exactly 1.0. */
    PLAYING,
    ;

    public val isAdvancing: Boolean get() = this == PLAYING
}
