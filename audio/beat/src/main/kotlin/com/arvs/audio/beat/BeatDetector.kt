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
 * ### The three conditions
 *
 * A frame is a confirmed beat if and only if all of:
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
     * Detects beats over a whole flux series.
     *
     * [flux] is indexed by §17.5 analysis frame. Returns beats in ascending frame order.
     */
    public fun detect(
        flux: FloatArray,
        framing: AnalysisFraming = AnalysisFraming.CANONICAL,
        config: BeatConfig = BeatConfig(),
    ): List<BeatEvent> {
        if (!config.enabled || flux.isEmpty()) return emptyList()

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

        for (index in flux.indices) {
            val value = flux[index].toDouble()
            val from = max(0, index - windowFrames + 1)
            val count = index - from + 1
            for (offset in 0 until count) scratch[offset] = flux[from + offset].toDouble()

            val threshold = adaptiveThreshold(scratch, count, config.madMultiplier)
            if (value <= threshold) continue
            if (!isLocalMaximum(flux, index)) continue

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
     * Whether `flux[index]` is a strict local maximum among the neighbours that exist.
     *
     * Strict on both sides so a plateau cannot fire twice. At the series boundaries only the
     * neighbour that exists is compared, which keeps the condition total. That matters at the
     * **last** frame, where an onset is otherwise silently dropped; frame 0 is a different case
     * and is excluded by the threshold condition instead — see [detect].
     */
    internal fun isLocalMaximum(flux: FloatArray, index: Int): Boolean {
        val risingInto = index == 0 || flux[index] > flux[index - 1]
        val fallingAfter = index == flux.size - 1 || flux[index] > flux[index + 1]
        return risingInto && fallingAfter
    }
}
