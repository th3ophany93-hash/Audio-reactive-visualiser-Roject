package com.arvs.audio.playback

import com.arvs.core.time.TimeSpan
import com.arvs.core.time.TimelineTime

/**
 * §14.1's **export** clock-authority regime: `T = frameIndex / exportFps`.
 *
 * §14.1 is explicit that this is deliberately a *different* regime from preview, and why it is
 * safe: "export never drops frames — sample-accurate sync is achieved at mux time, not by
 * sharing a clock object with preview." There is no live audio position here and nothing to
 * chase; the clock simply enumerates every frame index, and dropping one is not possible
 * because nothing is racing.
 *
 * Phase 8 (§136) builds the Export Engine. This clock lands in Phase 1 because §14.1 defines
 * both regimes together and the frame-time arithmetic is where the subtle error lives — see
 * below — so it is worth having proven long before an exporter depends on it.
 *
 * ### Frame rate is rational, not a Double
 *
 * The broadcast rates are exact fractions: 29.97 fps is 30000/1001 and 23.976 is 24000/1001.
 * Stored as a `Double` and multiplied out per frame, the error accumulates: at 30000/1001,
 * `frameIndex / 29.97` drifts past a millisecond within a few minutes and past a frame within
 * an hour — an export that ends visibly out of sync with its own audio, from arithmetic
 * alone. Holding numerator and denominator separately makes every frame time exact.
 */
public class VirtualFrameClock(
    public val numerator: Int,
    public val denominator: Int,
) {
    init {
        require(numerator > 0) { "frame-rate numerator must be positive: $numerator" }
        require(denominator > 0) { "frame-rate denominator must be positive: $denominator" }
        // A frame must be at least one microsecond long, so that microsecond storage can
        // distinguish adjacent frames at all — and so [frameIndexAt]'s correction below is
        // bounded to a single step. A million frames per second is not a video rate.
        require(numerator.toLong() <= 1_000_000L * denominator) {
            "frame rate must not exceed 1 000 000 fps: $numerator/$denominator"
        }
    }

    /** Nominal frames per second. For display and reporting only — never for arithmetic. */
    public val fps: Double get() = numerator.toDouble() / denominator

    /**
     * Timeline time of frame [frameIndex] — §14.1's `T = frameIndex / exportFps`.
     *
     * Computed as `frameIndex · 1 000 000 · denominator / numerator` in integer arithmetic, so
     * the only rounding is the final truncation to microseconds. Multiplying before dividing
     * is what keeps it exact; dividing first would quantise the frame interval and reintroduce
     * the drift the rational representation exists to avoid.
     */
    public fun timeAt(frameIndex: Long): TimelineTime {
        require(frameIndex >= 0) { "frameIndex must not be negative: $frameIndex" }
        return TimelineTime(frameIndex * 1_000_000L * denominator / numerator)
    }

    /** Duration of one frame. Exact for integer rates; truncated to microseconds otherwise. */
    public val frameDuration: TimeSpan get() = TimeSpan(1_000_000L * denominator / numerator)

    /**
     * Number of frames needed to cover [duration].
     *
     * Rounded **up**: a partial final frame is still a frame that must be rendered, and
     * truncating would silently drop the tail of every export whose length is not an exact
     * multiple of the frame interval.
     */
    public fun frameCountFor(duration: TimeSpan): Long {
        require(duration.micros >= 0) { "duration must not be negative: $duration" }
        if (duration.micros == 0L) return 0
        val frameMicrosNumerator = 1_000_000L * denominator
        return (duration.micros * numerator + frameMicrosNumerator - 1) / frameMicrosNumerator
    }

    /**
     * Index of the frame covering [time] — the exact inverse of [timeAt].
     *
     * The naive quotient `time · numerator / (1 000 000 · denominator)` is **wrong**, and
     * subtly: [timeAt] floors to microseconds, so its own output can sit up to one microsecond
     * below the true frame boundary and the quotient then lands one frame *early*. At 24 fps,
     * `frameIndexAt(timeAt(37))` returns 36. In an export that is a duplicated or dropped
     * frame, which is exactly what §14.1's "no drops" forbids.
     *
     * So this is defined as what it claims to be: the largest `n` with `timeAt(n) <= time`.
     * The correction is at most one step in either direction, because the truncation is under
     * one microsecond and the constructor requires a frame to be at least that long.
     */
    public fun frameIndexAt(time: TimelineTime): Long {
        require(time.micros >= 0) { "time must not be negative: $time" }
        var candidate = time.micros * numerator / (1_000_000L * denominator)
        while (candidate > 0 && timeAt(candidate).micros > time.micros) candidate--
        while (timeAt(candidate + 1).micros <= time.micros) candidate++
        return candidate
    }

    /**
     * Every frame index for an export of [duration], in order.
     *
     * §14.1: "every single frame at every such `T` is rendered with no drops." Exposing the
     * enumeration rather than a "next frame" call is what makes that structural — there is no
     * stateful cursor that could skip.
     */
    public fun frameIndices(duration: TimeSpan): LongRange = 0 until frameCountFor(duration)

    override fun toString(): String =
        if (denominator == 1) "${numerator}fps" else "$numerator/$denominator fps"

    override fun equals(other: Any?): Boolean =
        other is VirtualFrameClock && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = numerator * 31 + denominator

    public companion object {
        public fun ofIntegerFps(fps: Int): VirtualFrameClock = VirtualFrameClock(fps, 1)

        /** 24 fps. */
        public val FILM_24: VirtualFrameClock = VirtualFrameClock(24, 1)

        /** 23.976 fps — exactly 24000/1001, never 23.976. */
        public val FILM_23_976: VirtualFrameClock = VirtualFrameClock(24_000, 1_001)

        /** 25 fps. */
        public val PAL_25: VirtualFrameClock = VirtualFrameClock(25, 1)

        /** 29.97 fps — exactly 30000/1001. */
        public val NTSC_29_97: VirtualFrameClock = VirtualFrameClock(30_000, 1_001)

        /** 30 fps. */
        public val VIDEO_30: VirtualFrameClock = VirtualFrameClock(30, 1)

        /** 60 fps — the §87 preview budget's nominal rate. */
        public val VIDEO_60: VirtualFrameClock = VirtualFrameClock(60, 1)
    }
}
