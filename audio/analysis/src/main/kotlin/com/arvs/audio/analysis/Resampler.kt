package com.arvs.audio.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Deterministic sample-rate conversion to §17.3's canonical 48 kHz analysis rate.
 *
 * §17.3 (P-2) requires exactly two behaviours, and both are visible below:
 *
 *  - "A source already at 48 kHz is **passed through bit-exact**, with no resampling stage
 *    applied." [resampleToCanonical] returns the input array's own contents unchanged in that
 *    case — not a resampler configured with ratio 1.0, which would still round-trip every
 *    sample through the filter and perturb the low bits.
 *  - "All source PCM whose rate differs from 48 kHz is **deterministically** resampled."
 *
 * ### The algorithm, and what the specification does and does not fix
 *
 * §17.3 fixes the *target rate* and requires determinism; it does not name an algorithm.
 * Determinism alone would be satisfied by linear interpolation, but linear interpolation
 * aliases badly — for a 44.1 → 48 kHz conversion it folds high-frequency content down into the
 * band the spectral features later measure, so every band energy and centroid computed from it
 * would be wrong in a way no test of the *analysis* code could detect.
 *
 * So this is a band-limited windowed-sinc resampler: the standard construction, fully
 * specified by its tap count and window, and bit-reproducible on any JVM. When downsampling,
 * the sinc cutoff moves to the output Nyquist so the anti-alias filter is applied by the same
 * kernel rather than as a separate pass.
 *
 * The choice of kernel is an implementation decision the specification left open. It is
 * therefore covered by `AnalysisConfig.ANALYSIS_ALGORITHM_VERSION` (§18.2 item 11) — changing
 * the kernel changes cached values, and bumping that version is the mechanism that invalidates
 * them. Recorded rather than assumed.
 */
public object Resampler {

    /**
     * Taps each side of the interpolation point.
     *
     * 32 taps total. Fewer aliases audibly; more costs time §17.1 budgets tightly for stage
     * 1+2. The figure is a quality/cost choice, not a specification value.
     */
    public const val HALF_TAPS: Int = 16

    /**
     * Converts [samples] from [sourceRateHz] to [targetRateHz].
     *
     * Returns the input itself when the rates match, satisfying §17.3's bit-exact pass-through.
     */
    public fun resample(samples: FloatArray, sourceRateHz: Int, targetRateHz: Int): FloatArray {
        require(sourceRateHz > 0) { "sourceRateHz must be positive: $sourceRateHz" }
        require(targetRateHz > 0) { "targetRateHz must be positive: $targetRateHz" }
        if (sourceRateHz == targetRateHz) return samples
        if (samples.isEmpty()) return FloatArray(0)

        val ratio = targetRateHz.toDouble() / sourceRateHz
        val outputCount = floor(samples.size * ratio).toInt()
        if (outputCount <= 0) return FloatArray(0)

        // Downsampling must band-limit to the *output* Nyquist, or the content above it folds
        // back into the band every later feature measures.
        val cutoff = if (ratio < 1.0) ratio else 1.0
        val out = FloatArray(outputCount)

        for (index in 0 until outputCount) {
            val sourcePosition = index / ratio
            val centre = floor(sourcePosition).toInt()
            var accumulator = 0.0
            var weightSum = 0.0

            for (tap in -HALF_TAPS + 1..HALF_TAPS) {
                val sourceIndex = centre + tap
                if (sourceIndex < 0 || sourceIndex >= samples.size) continue
                val distance = sourcePosition - sourceIndex
                val weight = sinc(cutoff * distance) * cutoff * blackman(distance / HALF_TAPS)
                accumulator += samples[sourceIndex] * weight
                weightSum += weight
            }

            // Normalising by the realised weight sum keeps a constant input constant at the
            // edges, where taps fall outside the array. Without it every asset would begin and
            // end with a fade the source does not contain, and the §119 silence and impulse
            // fixtures would disagree with their analytic truth at the boundaries.
            out[index] = if (weightSum > 1e-12) (accumulator / weightSum).toFloat() else 0.0f
        }
        return out
    }

    private fun sinc(x: Double): Double =
        if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** Blackman window over `[-1, 1]`; zero outside, which is what bounds the tap count. */
    private fun blackman(normalised: Double): Double {
        if (normalised <= -1.0 || normalised >= 1.0) return 0.0
        val t = (normalised + 1.0) / 2.0
        return 0.42 - 0.5 * cos(2.0 * PI * t) + 0.08 * cos(4.0 * PI * t)
    }
}
