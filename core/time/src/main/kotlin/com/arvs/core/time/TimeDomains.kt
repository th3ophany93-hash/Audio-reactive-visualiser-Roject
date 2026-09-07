package com.arvs.core.time

/**
 * The two time domains this project distinguishes, and the single mapping between them.
 *
 * MASTER_SPECIFICATION_v3.0 §9.1 fixes the determinism epoch at `t = 0` of the **raw
 * audio asset**, never the trimmed timeline. §14.1 then requires that the translation
 * `audioT = T + trimIn` happens exactly once, inside the parameter-resolution pipeline.
 *
 * Those are architectural rules, and rules stated only in prose get broken quietly. So
 * the two domains are separate types here: [TimelineTime] and [AudioSourceTime] cannot be
 * added to each other, compared, or passed interchangeably. A conflation that would
 * otherwise be a subtle rendering bug — visuals drifting by exactly `trimIn` — becomes a
 * compile error instead.
 *
 * Unit is microseconds, matching Android's media stack (`MediaCodec` presentation
 * timestamps). At the canonical 48 kHz / 480-sample hop of §17.5 an analysis frame lands
 * every 10 000 µs exactly, so the analysis grid is representable without rounding.
 */

/** A duration. Domain-neutral: the difference between two instants in *either* domain. */
@JvmInline
public value class TimeSpan(public val micros: Long) : Comparable<TimeSpan> {
    public operator fun plus(other: TimeSpan): TimeSpan = TimeSpan(micros + other.micros)
    public operator fun minus(other: TimeSpan): TimeSpan = TimeSpan(micros - other.micros)
    public operator fun unaryMinus(): TimeSpan = TimeSpan(-micros)
    override fun compareTo(other: TimeSpan): Int = micros.compareTo(other.micros)

    public val millis: Double get() = micros / 1_000.0
    public val seconds: Double get() = micros / 1_000_000.0

    override fun toString(): String = "${seconds}s"

    public companion object {
        public val ZERO: TimeSpan = TimeSpan(0)
        public fun ofMillis(millis: Long): TimeSpan = TimeSpan(millis * 1_000)
        public fun ofSeconds(seconds: Long): TimeSpan = TimeSpan(seconds * 1_000_000)
    }
}

/**
 * Absolute time within the raw audio asset — the §9.1 epoch domain.
 *
 * Every analysis frame, every cached feature sample, and every temporal modulator
 * trajectory is indexed in this domain. It is unaffected by trimming: dragging a trim
 * handle changes which audio-source time a given timeline time maps to, and changes
 * nothing that is stored (§9.1, §17.5).
 */
@JvmInline
public value class AudioSourceTime(public val micros: Long) : Comparable<AudioSourceTime> {
    public operator fun plus(span: TimeSpan): AudioSourceTime = AudioSourceTime(micros + span.micros)
    public operator fun minus(span: TimeSpan): AudioSourceTime = AudioSourceTime(micros - span.micros)
    public operator fun minus(other: AudioSourceTime): TimeSpan = TimeSpan(micros - other.micros)
    override fun compareTo(other: AudioSourceTime): Int = micros.compareTo(other.micros)

    override fun toString(): String = "AudioSourceTime(${micros / 1_000_000.0}s)"

    public companion object {
        /** The §9.1 epoch: `t = 0` of the raw asset. */
        public val EPOCH: AudioSourceTime = AudioSourceTime(0)
        public fun ofMillis(millis: Long): AudioSourceTime = AudioSourceTime(millis * 1_000)
        public fun ofSeconds(seconds: Double): AudioSourceTime =
            AudioSourceTime(Math.round(seconds * 1_000_000.0))
    }
}

/**
 * Time relative to the trimmed selection's own `t = 0` — the domain the timeline,
 * playhead and (from Phase 2) the renderer speak in.
 *
 * Per §14.1 this is the `T` that both clock regimes produce: the audio playback clock
 * during preview, and the virtual frame clock during export.
 */
@JvmInline
public value class TimelineTime(public val micros: Long) : Comparable<TimelineTime> {
    public operator fun plus(span: TimeSpan): TimelineTime = TimelineTime(micros + span.micros)
    public operator fun minus(span: TimeSpan): TimelineTime = TimelineTime(micros - span.micros)
    public operator fun minus(other: TimelineTime): TimeSpan = TimeSpan(micros - other.micros)
    override fun compareTo(other: TimelineTime): Int = micros.compareTo(other.micros)

    override fun toString(): String = "TimelineTime(${micros / 1_000_000.0}s)"

    public companion object {
        public val ZERO: TimelineTime = TimelineTime(0)
        public fun ofMillis(millis: Long): TimelineTime = TimelineTime(millis * 1_000)
        public fun ofSeconds(seconds: Double): TimelineTime =
            TimelineTime(Math.round(seconds * 1_000_000.0))
    }
}

/**
 * The trimmed region of an audio asset, and the **only** definition of how timeline time
 * maps onto audio-source time.
 *
 * §14.1 requires `audioT = T + trimIn` to be applied exactly once inside the
 * parameter-resolution pipeline, and nowhere else within it. That constraint governs the
 * render path; per §14.1's P-5 clarification the Trim Editor legitimately converts
 * between domains for display, which is why this mapping is public rather than hidden.
 *
 * Trim is deliberately *not* part of analysis-cache identity (§18.2): moving these
 * handles changes the offset used to query cached data and never the data itself.
 */
public class TrimMapping(
    public val trimIn: AudioSourceTime,
    public val trimOut: AudioSourceTime,
) {
    init {
        require(trimIn.micros >= 0) { "trimIn must not be negative: $trimIn" }
        require(trimOut > trimIn) { "trimOut ($trimOut) must be strictly after trimIn ($trimIn)" }
    }

    /** Duration of the trimmed selection. */
    public val duration: TimeSpan get() = trimOut - trimIn

    /** §14.1: `audioT = T + trimIn`. */
    public fun toAudioTime(timelineTime: TimelineTime): AudioSourceTime =
        AudioSourceTime(trimIn.micros + timelineTime.micros)

    /** Inverse of [toAudioTime]; used by the Trim Editor for display (§14.1 P-5). */
    public fun toTimelineTime(audioTime: AudioSourceTime): TimelineTime =
        TimelineTime(audioTime.micros - trimIn.micros)

    /** Whether [audioTime] falls inside the trimmed selection (half-open: `[trimIn, trimOut)`). */
    public operator fun contains(audioTime: AudioSourceTime): Boolean =
        audioTime >= trimIn && audioTime < trimOut

    override fun equals(other: Any?): Boolean =
        other is TrimMapping && other.trimIn == trimIn && other.trimOut == trimOut

    override fun hashCode(): Int = 31 * trimIn.micros.hashCode() + trimOut.micros.hashCode()

    override fun toString(): String = "TrimMapping($trimIn..$trimOut)"

    public companion object {
        /** The whole asset: no trimming applied. */
        public fun full(assetDuration: TimeSpan): TrimMapping =
            TrimMapping(AudioSourceTime.EPOCH, AudioSourceTime(assetDuration.micros))
    }
}
