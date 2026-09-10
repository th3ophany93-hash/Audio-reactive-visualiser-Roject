package com.arvs.audio.beat

import com.arvs.core.model.BeatConfig
import com.arvs.core.time.AnalysisFraming
import kotlin.math.abs
import kotlin.math.max

/**
 * §21.1 [D-8] — beat detection v1.
 *
 * CPU-side, deterministic, and derived entirely from the §17.4 spectral magnitude analysis. It
 * takes the onset signal as input rather than computing it: the flux series is already a stored
 * §17 feature, and §116.1 places this module beneath `audio:analysis`, which owns the spectrum.
 * That direction is not incidental — it is what keeps the detector a pure function over a number
 * series, so §9.1's bit-reproducibility is a property of arithmetic rather than of a pipeline.
 *
 * §21.1 forbids, and this implementation contains, no ML or neural inference, no randomisation,
 * no wall-clock dependence, no GPU work, and no device-dependent threshold.
 *
 * ### Frame eligibility (§21.1 [D-10])
 *
 * Only frames whose §17.5 analysis window is **fully backed by real source audio** are evaluated.
 * §17.5 zero-pads the final windows so the framing covers the whole signal; that padding is an
 * implementation detail of spectral framing and is not audio. A padded window contains a hard
 * truncation, which smears energy across the spectrum and reads as a large positive flux —
 * unguarded, a phantom beat at maximum confidence at the end of every track.
 *
 * See [lastEligibleFrame]. Ineligible frames take no part at all, not even as neighbours in the
 * local-maximum test, because their flux is contaminated and a contaminated neighbour could
 * suppress a real beat at the last eligible frame — which §21.1 requires be preserved.
 *
 * ### The three conditions
 *
 * An **eligible** frame is a confirmed beat if and only if all of:
 *  1. `flux[t] > threshold[t]`, where `threshold[t] = median(window) + madMultiplier · MAD(window)`;
 *  2. `flux[t]` is a local maximum;
 *  3. at least `refractoryMs` have elapsed since the previous **confirmed** beat.
 *
 * Median and MAD rather than mean and standard deviation because a transient is exactly the
 * outlier a mean-based threshold would absorb into its own estimate — a loud onset would raise
 * the very threshold it has to clear.
 */
public object BeatDetector {

    /** §21.1's `EPSILON` — the guard in the confidence denominator, not a magnitude floor. */
    public const val EPSILON: Double = 1e-12

    /**
     * §21.1 [D-10]'s last frame whose analysis window is fully backed by real source audio.
     *
     * Frame `f` covers samples `[f·hop, f·hop + window)`, so it is fully backed exactly when
     * `f·hop + window <= sourceSampleCount`. Returns [NO_ELIGIBLE_FRAME] when the source is
     * shorter than a single window and no frame qualifies.
     */
    public fun lastEligibleFrame(
        sourceSampleCount: Long,
        framing: AnalysisFraming = AnalysisFraming.CANONICAL,
    ): Long {
        require(sourceSampleCount >= 0) { "sourceSampleCount must not be negative: $sourceSampleCount" }
        if (sourceSampleCount < framing.windowSamples) return NO_ELIGIBLE_FRAME
        return (sourceSampleCount - framing.windowSamples) / framing.hopSamples
    }

    /** [lastEligibleFrame]'s answer when the source is too short for any complete window. */
    public const val NO_ELIGIBLE_FRAME: Long = -1L

    /**
     * Detects beats over a whole flux series.
     *
     * [flux] is indexed by §17.5 analysis frame. [sourceSampleCount] is the length of the **real**
     * source audio in canonical samples, which fixes §21.1 [D-10]'s eligible range; it is required
     * rather than defaulted, because a default would let a caller silently opt out of the tail
     * rule and get the phantom beat back. Returns beats in ascending frame order.
     */
    public fun detect(
        flux: FloatArray,
        sourceSampleCount: Long,
        framing: AnalysisFraming = AnalysisFraming.CANONICAL,
        config: BeatConfig = BeatConfig(),
    ): List<BeatEvent> {
        if (!config.enabled || flux.isEmpty()) return emptyList()

        // §21.1 [D-10]. The eligible frames are the *domain* of detection, not a filter applied
        // to its output: everything below — thresholds, neighbours, the refractory chain — sees
        // only frames backed by real audio.
        val lastEligible = minOf(lastEligibleFrame(sourceSampleCount, framing), flux.size - 1L)
        if (lastEligible < 0) return emptyList()
        val eligibleCount = (lastEligible + 1).toInt()

        // §21.1: "the 1.0 second threshold history window ending at t". History, so the window
        // is trailing and inclusive of t — a beat is judged against what preceded it, never
        // against material that has not been heard yet.
        // A consequence worth naming: at frame 0 the window holds only frame 0, so median =
        // flux[0] and MAD = 0, making threshold = flux[0] — and a value cannot exceed itself.
        // Frame 0 is therefore never a beat. That is what a *history* window means, not an
        // oversight: an onset is an excursion from what preceded it, and nothing precedes 0.
        val windowFrames = max(1, Math.round(config.thresholdWindowSeconds * framing.frameRateHz).toInt())
        val beats = ArrayList<BeatEvent>()
        var lastBeatFrame = Long.MIN_VALUE
        val scratch = DoubleArray(windowFrames)

        for (index in 0 until eligibleCount) {
            val value = flux[index].toDouble()
            val from = max(0, index - windowFrames + 1)
            val count = index - from + 1
            for (offset in 0 until count) scratch[offset] = flux[from + offset].toDouble()

            val threshold = adaptiveThreshold(scratch, count, config.madMultiplier)
            if (value <= threshold) continue
            if (!isLocalMaximum(flux, index, eligibleCount)) continue

            // Condition 3, evaluated in the time domain rather than by converting the refractory
            // period to a frame count. Converting would need a rounding rule that §21.1 does not
            // state, and would make the boundary depend on it; the inequality is exact.
            if (lastBeatFrame != Long.MIN_VALUE) {
                val elapsedMs = (index - lastBeatFrame) * 1000.0 / framing.frameRateHz
                if (elapsedMs < config.refractoryMs) continue
            }

            val confidence = ((value - threshold) / max(threshold, EPSILON)).coerceIn(0.0, 1.0)
            beats += BeatEvent(
                timestamp = framing.frameStartTime(index.toLong()),
                frameIndex = index.toLong(),
                confidence = confidence.toFloat(),
                // §21.1 [D-9]: the raw flux value, never normalized, clamped or transformed.
                strength = flux[index],
            )
            lastBeatFrame = index.toLong()
        }
        return beats
    }

    /**
     * §21.1's `median(window) + madMultiplier · MAD(window)` over `window[0 until count]`.
     *
     * [window] is scratch and is reordered in place — the caller refills it each frame.
     */
    internal fun adaptiveThreshold(window: DoubleArray, count: Int, madMultiplier: Double): Double {
        require(count in 1..window.size) { "count $count outside 1..${window.size}" }
        val median = medianOf(window, count)
        for (index in 0 until count) window[index] = abs(window[index] - median)
        val mad = medianOf(window, count)
        return median + madMultiplier * mad
    }

    /**
     * Median of `values[0 until count]`, by sorting that prefix in place.
     *
     * The even case takes the mean of the two central values. Sorting rather than a selection
     * algorithm: the window is 100 frames at the canonical framing, where the constant factor
     * dominates any asymptotic advantage, and a sort is trivially deterministic.
     */
    internal fun medianOf(values: DoubleArray, count: Int): Double {
        java.util.Arrays.sort(values, 0, count)
        val middle = count / 2
        return if (count % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
    }

    /**
     * Whether `flux[index]` is a strict local maximum among the **eligible** neighbours that exist.
     *
     * Strict on both sides so a plateau cannot fire twice. At the boundaries of the eligible range
     * only the neighbour that exists is compared, which keeps the condition total. That matters at
     * the last eligible frame, where a real onset would otherwise be silently dropped — and where
     * comparing against the first *ineligible* frame would be worse still, since that frame's flux
     * is inflated by §17.5's zero-padding and would suppress the beat §21.1 requires be kept.
     * Frame 0 is a different case and is excluded by the threshold condition instead — see [detect].
     *
     * [eligibleCount] is the number of frames in the eligible range, so `eligibleCount - 1` is the
     * last index this may look at.
     */
    internal fun isLocalMaximum(flux: FloatArray, index: Int, eligibleCount: Int): Boolean {
        val risingInto = index == 0 || flux[index] > flux[index - 1]
        val fallingAfter = index == eligibleCount - 1 || flux[index] > flux[index + 1]
        return risingInto && fallingAfter
    }
}
