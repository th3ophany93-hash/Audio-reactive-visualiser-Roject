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
     * §21.1 [D-9]: `spectralFlux[frameIndex]` — the raw half-wave-rectified spectral-flux value at
     * the detected beat frame.
     *
     * **Not normalized, not clamped, not otherwise transformed.** Unbounded above.
     *
     * Deliberately a different quantity from [confidence], and neither substitutes for the other:
     * this is the raw transient magnitude, [confidence] is the normalized threshold exceedance. A
     * quiet track and a loud one can both produce `confidence = 1.0`; only strength distinguishes
     * them, so transforming it would collapse both fields onto the same information.
     */
    public val strength: Float,
) {
    init {
        require(frameIndex >= 0) { "frameIndex must not be negative: $frameIndex" }
        require(confidence in 0.0f..1.0f) { "confidence must be within [0,1]: $confidence" }
        // Deliberately *no* upper bound on strength: §21.1 [D-9] leaves it unbounded, and a
        // require() capping it here would be exactly the silent transform that decision forbids.
        require(strength >= 0.0f) { "strength must not be negative: $strength" }
    }
}
