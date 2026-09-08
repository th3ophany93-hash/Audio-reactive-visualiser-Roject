package com.arvs.audio.beat

import com.arvs.core.time.AudioSourceTime

/**
 * One confirmed beat (§21.1).
 *
 * §21.1 requires a beat event to expose *at minimum* `timestamp`, `frameIndex`, `confidence` and
 * `strength`; these four are exactly that set.
 *
 * [timestamp] is an [AudioSourceTime] — §9.1's epoch, the start of the raw asset — never a
 * timeline time. Beats are a property of the audio, not of where the audio was trimmed to, so a
 * trim change must not invalidate them. `TrimMapping` remains the single translation site.
 */
public data class BeatEvent(
    /** §17.5 frame start, in the §9.1 audio-source domain. */
    public val timestamp: AudioSourceTime,
    /** The §17.5 analysis frame this beat was detected on. */
    public val frameIndex: Long,
    /** §21.1: `clamp((flux − threshold) / max(threshold, EPSILON), 0, 1)`. */
    public val confidence: Float,
    /**
     * The raw onset value at the beat — `flux[frameIndex]`, un-normalized.
     *
     * §21.1 names this field but does not give it a formula, so this is a **stated** choice, not
     * a ratified one: strength is the onset signal itself, which is what [confidence] is the
     * threshold-relative normalisation *of*. Recoverable — the flux series is stored (§17.4), so
     * a different definition is a read-time recomputation. Recorded as T-15.
     */
    public val strength: Float,
) {
    init {
        require(frameIndex >= 0) { "frameIndex must not be negative: $frameIndex" }
        require(confidence in 0.0f..1.0f) { "confidence must be within [0,1]: $confidence" }
    }
}
