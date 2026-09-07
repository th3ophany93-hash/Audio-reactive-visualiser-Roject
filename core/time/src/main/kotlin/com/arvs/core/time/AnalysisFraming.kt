package com.arvs.core.time

/**
 * The canonical analysis framing ratified in MASTER_SPECIFICATION_v3.0 §17.5 (Appendix B
 * U-21), and the frame ↔ time arithmetic built on it.
 *
 * §17.5 insists these are **three separate quantities** that must never be conflated:
 *
 * | Quantity            | Canonical value | Nature                        |
 * |---------------------|-----------------|-------------------------------|
 * | FFT window size     | 2048 samples    | input                         |
 * | Analysis hop        | 480 samples     | input                         |
 * | Native frame rate   | 100 Hz          | *derived* (`48000 / 480`)     |
 *
 * Window overlap (76.5625% canonically) is likewise derived, never an input — which is
 * why this type exposes it as a computed property and refuses to accept it as one. The
 * v2.0 "50% overlap" figure is superseded and must not reappear.
 *
 * Because the native frame rate equals §17.2's storage rate exactly, spectral frames are
 * written to the cache one-for-one as *measured* frames. Nothing here interpolates or
 * upsamples spectra to reach the storage grid; §17.5 forbids it.
 */
public data class AnalysisFraming(
    /** §17.3: the canonical analysis sample rate. All source PCM is resampled to this. */
    public val sampleRateHz: Int = CANONICAL_SAMPLE_RATE_HZ,
    /** §17.5: FFT window size, in samples. */
    public val windowSamples: Int = CANONICAL_WINDOW_SAMPLES,
    /** §17.5: analysis hop, in samples. Distinct from both window size and frame rate. */
    public val hopSamples: Int = CANONICAL_HOP_SAMPLES,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(windowSamples > 0) { "windowSamples must be positive: $windowSamples" }
        require(hopSamples > 0) { "hopSamples must be positive: $hopSamples" }
        require(hopSamples <= windowSamples) {
            "hopSamples ($hopSamples) must not exceed windowSamples ($windowSamples): a hop " +
                "larger than the window would leave gaps in the analysed signal."
        }
    }

    /** Derived, never an input (§17.5). Canonically `48000 / 480 = 100.0`. */
    public val frameRateHz: Double get() = sampleRateHz.toDouble() / hopSamples

    /** Derived, never an input (§17.5). Canonically `(2048 - 480) / 2048 = 0.765625`. */
    public val overlapFraction: Double
        get() = (windowSamples - hopSamples).toDouble() / windowSamples

    /** Duration of one analysis window. Canonically `2048 / 48000 s ≈ 42.667 ms`. */
    public val windowDuration: TimeSpan
        get() = TimeSpan(samplesToMicros(windowSamples.toLong()))

    /** Spacing between successive frames. Canonically exactly 10 ms. */
    public val hopDuration: TimeSpan
        get() = TimeSpan(samplesToMicros(hopSamples.toLong()))

    /** Width of one FFT bin in Hz. Canonically `48000 / 2048 = 23.4375 Hz`. */
    public val binWidthHz: Double get() = sampleRateHz.toDouble() / windowSamples

    /**
     * First sample covered by frame [frameIndex].
     *
     * §17.5: a frame is anchored at its window **start**, so frame `n` covers samples
     * `[n·hop, n·hop + window)`. Centre-anchoring would place frames off the 10 ms grid
     * and reintroduce exactly the interpolation the ratification forbids.
     */
    public fun frameStartSample(frameIndex: Long): Long {
        require(frameIndex >= 0) { "frameIndex must not be negative: $frameIndex" }
        return frameIndex * hopSamples
    }

    /** Half-open sample range `[start, start + window)` covered by [frameIndex]. */
    public fun frameSampleRange(frameIndex: Long): LongRange {
        val start = frameStartSample(frameIndex)
        return start until (start + windowSamples)
    }

    /**
     * Number of frames produced for [totalSamples] of input.
     *
     * §17.5: `N = ceil(totalSamples / hop)`, with the final windows zero-padded where they
     * extend past the end of the asset. That makes the frame count a pure function of
     * asset length — no dependence on buffering, chunking, or decode order.
     */
    public fun frameCount(totalSamples: Long): Long {
        require(totalSamples >= 0) { "totalSamples must not be negative: $totalSamples" }
        if (totalSamples == 0L) return 0
        return (totalSamples + hopSamples - 1) / hopSamples
    }

    /**
     * Audio-source time at which frame [frameIndex] starts.
     *
     * Exact for the canonical framing: `n · 480 / 48000 s = n · 10 000 µs`.
     */
    public fun frameStartTime(frameIndex: Long): AudioSourceTime =
        AudioSourceTime(samplesToMicros(frameStartSample(frameIndex)))

    /** Index of the last frame starting at or before [time]; clamped at frame 0. */
    public fun frameIndexAtOrBefore(time: AudioSourceTime): Long {
        if (time.micros <= 0) return 0
        return microsToSamples(time.micros) / hopSamples
    }

    /**
     * How to read a value at an arbitrary [time] from the 100 Hz grid.
     *
     * §17.2 mandates linear interpolation between the two adjacent stored samples. This
     * returns the two frame indices and the blend fraction rather than the value itself,
     * so the arithmetic lives in one place while the storage stays in `audio:cache`.
     *
     * Note this concerns *reading between stored frames*. It is unrelated to — and must
     * not be confused with — the prohibition in §17.5 on interpolating spectra in order to
     * manufacture stored frames.
     */
    public fun interpolationAt(time: AudioSourceTime): FrameInterpolation {
        if (time.micros <= 0) return FrameInterpolation(0, 0, 0.0)
        val lower = frameIndexAtOrBefore(time)
        val lowerMicros = frameStartTime(lower).micros
        val hopMicros = hopDuration.micros
        if (hopMicros == 0L) return FrameInterpolation(lower, lower, 0.0)
        val fraction = ((time.micros - lowerMicros).toDouble() / hopMicros).coerceIn(0.0, 1.0)
        return FrameInterpolation(lower, lower + 1, fraction)
    }

    /** Sample index → microseconds, floored. */
    public fun samplesToMicros(samples: Long): Long = samples * 1_000_000L / sampleRateHz

    /** Microseconds → sample index, floored. */
    public fun microsToSamples(micros: Long): Long = micros * sampleRateHz / 1_000_000L

    public companion object {
        /** §17.3 (Appendix B U-2/P-2). */
        public const val CANONICAL_SAMPLE_RATE_HZ: Int = 48_000

        /** §17.5 — FFT window size. */
        public const val CANONICAL_WINDOW_SAMPLES: Int = 2_048

        /** §17.5 — analysis hop, chosen so the native frame rate is exactly 100 Hz. */
        public const val CANONICAL_HOP_SAMPLES: Int = 480

        /** §17.2 / §17.5 — the storage timeline, and natively the analysis rate too. */
        public const val CANONICAL_FRAME_RATE_HZ: Int = 100

        /** The ratified canonical framing (§17.5). */
        public val CANONICAL: AnalysisFraming = AnalysisFraming()
    }
}

/**
 * A read position on the 100 Hz grid: blend [fraction] of the way from [lowerFrame] to
 * [upperFrame], per §17.2's linear interpolation rule.
 */
public data class FrameInterpolation(
    public val lowerFrame: Long,
    public val upperFrame: Long,
    public val fraction: Double,
) {
    init {
        require(fraction in 0.0..1.0) { "fraction must be within [0,1]: $fraction" }
        require(upperFrame >= lowerFrame) { "upperFrame must not precede lowerFrame" }
    }

    /** Applies the interpolation to two already-read sample values. */
    public fun blend(lowerValue: Float, upperValue: Float): Float =
        (lowerValue + (upperValue - lowerValue) * fraction).toFloat()
}
