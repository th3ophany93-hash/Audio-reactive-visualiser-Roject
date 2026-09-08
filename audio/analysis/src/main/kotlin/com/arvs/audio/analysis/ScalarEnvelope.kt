package com.arvs.audio.analysis

import com.arvs.core.time.AnalysisFraming
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.FrameInterpolation
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * §17.1 stage 2: the RMS and peak envelope, on §17.2's 100 Hz timeline.
 *
 * ### Which samples a frame's value is computed from
 *
 * The canonical analysis frame — 2048 samples at a 480-sample hop (§17.5). §17.5 calls the hop
 * "the distance between successive **analysis** frames", not FFT frames, and §17.2 requires
 * every feature to land on one shared 100 Hz timeline. Using the same framing for time-domain
 * features is what makes that alignment free rather than something to reconcile later.
 *
 * ### The window function is not applied here
 *
 * `AnalysisConfig.windowFunction` (§18.2 item 6) exists to control **spectral leakage** in the
 * FFT. Applying it to an amplitude envelope would scale every value by the window's own RMS —
 * a Hann window would report a full-scale sine at roughly 0.61 of its true level, and the
 * analytic truth the test suite checks (`sine → A/√2`) would simply not hold. So these
 * features read the raw frame.
 */
public class ScalarEnvelope(
    /** Root-mean-square per frame, on the 100 Hz timeline. */
    public val rms: FloatArray,
    /** Absolute peak per frame. */
    public val peak: FloatArray,
    /**
     * Sum of squares per frame.
     *
     * §17 requires "Energy" but does not fix the convention. This is `Σx²` over the analysis
     * window — the physics definition. The alternative convention, mean square, differs by the
     * constant window length, so either is recoverable from the other and from [rms] without
     * re-analysing anything. Stated rather than assumed; see the Step 8 report.
     */
    public val energy: FloatArray,
    public val framing: AnalysisFraming = AnalysisFraming.CANONICAL,
) {
    init {
        require(rms.size == peak.size && peak.size == energy.size) {
            "envelope arrays must be the same length: ${rms.size}/${peak.size}/${energy.size}"
        }
    }

    public val frameCount: Int get() = rms.size

    /** Peak across the whole signal — the level a normalisation reference would use (§19). */
    public val globalPeak: Float get() = peak.fold(0.0f) { best, value -> maxOf(best, value) }

    /**
     * RMS at an arbitrary time, per §17.2's linear-interpolation rule.
     *
     * §17.2: "Any consumer requesting a value at an arbitrary timestamp T linearly interpolates
     * between the two adjacent 100 Hz cache samples." This is that rule, applied to a value
     * already on the grid — distinct from, and not to be confused with, §17.5's prohibition on
     * interpolating *spectra* in order to manufacture stored frames.
     */
    public fun rmsAt(time: AudioSourceTime): Float = interpolate(rms, framing.interpolationAt(time))

    public fun peakAt(time: AudioSourceTime): Float = interpolate(peak, framing.interpolationAt(time))

    public fun energyAt(time: AudioSourceTime): Float = interpolate(energy, framing.interpolationAt(time))

    private fun interpolate(values: FloatArray, at: FrameInterpolation): Float {
        if (values.isEmpty()) return 0.0f
        val lower = at.lowerFrame.coerceIn(0L, (values.size - 1).toLong()).toInt()
        val upper = at.upperFrame.coerceIn(0L, (values.size - 1).toLong()).toInt()
        return at.blend(values[lower], values[upper])
    }

    public companion object {

        /**
         * Computes the stage-2 envelope over [frames].
         *
         * Single pass, one reused frame buffer. Deterministic by construction: the arithmetic
         * depends only on the samples and the framing, never on chunking or call order, which
         * is what §9.1 requires of everything the cache holds.
         */
        public fun compute(frames: AnalysisFrames): ScalarEnvelope {
            val count = frames.frameCount.toInt()
            val rms = FloatArray(count)
            val peak = FloatArray(count)
            val energy = FloatArray(count)
            val window = frames.framing.windowSamples

            frames.forEachFrame { frameIndex, frame ->
                var sumOfSquares = 0.0
                var maximum = 0.0f
                for (sample in frame) {
                    sumOfSquares += sample.toDouble() * sample
                    val magnitude = abs(sample)
                    if (magnitude > maximum) maximum = magnitude
                }
                val index = frameIndex.toInt()
                energy[index] = sumOfSquares.toFloat()
                // Accumulated in Double and reduced to Float once.
                //
                // Not a formality: a Float accumulator loses any term more than ~1e-7 below
                // the running total, so a frame containing one loud sample and two thousand
                // quiet ones records the loud one and discards the rest — the quiet detail is
                // not merely imprecise, it is absent. These values are also cached and
                // compared bit-exactly by the §9.1 determinism suite, where a drifting
                // accumulator would show up as non-reproducibility rather than as inaccuracy.
                rms[index] = sqrt(sumOfSquares / window).toFloat()
                peak[index] = maximum
            }

            return ScalarEnvelope(rms, peak, energy, frames.framing)
        }
    }
}
