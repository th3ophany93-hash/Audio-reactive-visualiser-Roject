package com.arvs.audio.analysis

import com.arvs.core.model.WindowFunction
import kotlin.math.PI
import kotlin.math.cos

/**
 * The §18.2 item 4 analysis window functions, and the coherent gain each implies.
 *
 * A window is applied before the FFT to control spectral leakage. It also **attenuates** the
 * signal — a Hann window's average value is 0.5, so a full-scale sine windowed by it produces a
 * peak bin at half the unwindowed magnitude. §17.4 requires magnitudes "normalized against the
 * canonical signal's full-scale reference", which means that attenuation has to be divided back
 * out; [coherentGain] is the factor that does it. Without it, changing the window function would
 * change every cached magnitude by a constant — a silent, systematic error that looks like a
 * quieter mix rather than like a bug.
 *
 * Windows are periodic (`/ N`), not symmetric (`/ (N−1)`). Periodic is the correct choice for
 * spectral analysis of a continuous signal: successive frames tile the signal, and the symmetric
 * variant would introduce a discontinuity at the frame boundary that the window exists to avoid.
 */
public object WindowFunctions {

    /** Builds the window coefficients for [function] over [size] samples. */
    public fun coefficients(function: WindowFunction, size: Int): FloatArray {
        require(size > 0) { "window size must be positive: $size" }
        val n = size.toDouble()
        return FloatArray(size) { index ->
            val t = index / n
            when (function) {
                WindowFunction.HANN ->
                    0.5 - 0.5 * cos(2.0 * PI * t)
                WindowFunction.HAMMING ->
                    0.54 - 0.46 * cos(2.0 * PI * t)
                WindowFunction.BLACKMAN_HARRIS ->
                    0.35875 - 0.48829 * cos(2.0 * PI * t) +
                        0.14128 * cos(4.0 * PI * t) - 0.01168 * cos(6.0 * PI * t)
            }.toFloat()
        }
    }

    /**
     * Mean of the window's coefficients — the factor by which it scales a coherent sinusoid.
     *
     * Computed from the coefficients rather than quoted from a table, so it stays correct if a
     * window's definition is ever corrected and cannot drift out of step with it.
     */
    public fun coherentGain(function: WindowFunction, size: Int): Double {
        val coefficients = coefficients(function, size)
        var sum = 0.0
        for (value in coefficients) sum += value
        return sum / size
    }
}
