package com.arvs.audio.analysis

import com.arvs.core.time.AnalysisFraming

/**
 * The ITU-R BS.1770 K-weighting pre-filter, at §17.3's fixed canonical rate.
 *
 * §17.6 [T-8] ratifies K-weighting as the weighting for the Loudness Approximation. K-weighting
 * is two cascaded biquads applied in order:
 *
 *  1. a **high-shelf** approximating the acoustic effect of a head, and
 *  2. an **RLB high-pass**, which removes the low-frequency energy the ear is insensitive to.
 *
 * ### Why the coefficients are constants rather than a filter design
 *
 * BS.1770's coefficients are published for 48 kHz. §17.3 fixes the canonical analysis rate at
 * exactly 48 kHz, and §17.6 fixes the loudness measurement to that signal — so the filter never
 * needs designing at another rate. That is worth more than convenience: a runtime filter design
 * would make the cached loudness depend on the design routine's floating-point behaviour, and
 * §9.1 requires the cache to be bit-reproducible. Constants remove the question entirely.
 *
 * The filter is **stateful and streams in frame order**, which is why §17.7 phase 2a can produce
 * the weighted loudness in the same traversal that produces the raw scalars: the state carries
 * across frame boundaries exactly as it would across a continuous signal, so the result is the
 * whole-signal filter response and not a per-frame restart.
 */
public class KWeighting {

    // Direct Form I state for each biquad.
    private var shelfX1 = 0.0
    private var shelfX2 = 0.0
    private var shelfY1 = 0.0
    private var shelfY2 = 0.0

    private var rlbX1 = 0.0
    private var rlbX2 = 0.0
    private var rlbY1 = 0.0
    private var rlbY2 = 0.0

    /** Resets the filter to its initial state, for a fresh signal. */
    public fun reset() {
        shelfX1 = 0.0; shelfX2 = 0.0; shelfY1 = 0.0; shelfY2 = 0.0
        rlbX1 = 0.0; rlbX2 = 0.0; rlbY1 = 0.0; rlbY2 = 0.0
    }

    /**
     * Filters one sample, advancing the state.
     *
     * Computed in Double throughout. The intermediate between the two biquads is not rounded to
     * Float: doing so would inject quantisation into a recursive filter, where it does not stay
     * small but feeds back through the state.
     */
    public fun process(sample: Float): Double {
        val x = sample.toDouble()

        val shelfOut = SHELF_B0 * x + SHELF_B1 * shelfX1 + SHELF_B2 * shelfX2 -
            SHELF_A1 * shelfY1 - SHELF_A2 * shelfY2
        shelfX2 = shelfX1
        shelfX1 = x
        shelfY2 = shelfY1
        shelfY1 = shelfOut

        val rlbOut = RLB_B0 * shelfOut + RLB_B1 * rlbX1 + RLB_B2 * rlbX2 -
            RLB_A1 * rlbY1 - RLB_A2 * rlbY2
        rlbX2 = rlbX1
        rlbX1 = shelfOut
        rlbY2 = rlbY1
        rlbY1 = rlbOut

        return rlbOut
    }

    /** Filters a whole signal from a fresh state. Used by tests and by offline analysis. */
    public fun processAll(samples: FloatArray): DoubleArray {
        reset()
        return DoubleArray(samples.size) { process(samples[it]) }
    }

    public companion object {
        /** The rate BS.1770's published coefficients are defined at, and §17.3's canonical rate. */
        public const val COEFFICIENT_SAMPLE_RATE_HZ: Int = AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ

        // Stage 1 — high-shelf. ITU-R BS.1770 Table 1, 48 kHz.
        internal const val SHELF_B0 = 1.53512485958697
        internal const val SHELF_B1 = -2.69169618940638
        internal const val SHELF_B2 = 1.19839281085285
        internal const val SHELF_A1 = -1.69065929318241
        internal const val SHELF_A2 = 0.73248077421585

        // Stage 2 — RLB high-pass. ITU-R BS.1770 Table 2, 48 kHz.
        internal const val RLB_B0 = 1.0
        internal const val RLB_B1 = -2.0
        internal const val RLB_B2 = 1.0
        internal const val RLB_A1 = -1.99004745483398
        internal const val RLB_A2 = 0.99007225036621

        /**
         * §17.6 [T-8]'s BS.1770 offset, in dB.
         *
         * Converts the K-weighted mean square into the loudness scale. Part of the ratified
         * formula, not a tuning parameter.
         */
        public const val BS1770_OFFSET_DB: Double = -0.691

        /** §17.6 [T-8]'s ratified silence floor, in dBFS. */
        public const val SILENCE_FLOOR_DB: Double = -70.0
    }
}
