package com.arvs.audio.analysis

import com.arvs.core.time.AnalysisFraming
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.FrameInterpolation
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
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
    /**
     * §17.6 [T-7]: `Energy[n] / max_m Energy[m]`, a **track-relative** reference.
     *
     * Present only in a *published* envelope. §17.7 forbids deriving it before the reference is
     * final, which is why phase 2a ([Stage2.measure]) does not produce it and phase 2b does.
     */
    public val normalizedEnergy: FloatArray,
    /** §17.6 [T-8]: K-weighted, ungated, per-frame loudness in dBFS, floored at −70. */
    public val loudnessDb: FloatArray,
    /**
     * §17.6 [T-7]'s reference: `max_m Energy[m]` over the whole track.
     *
     * §17.7 requires this to be **immutable cache metadata** — finalised before publication and
     * never updated. It is carried on the envelope so a reader can recover raw `Energy` from a
     * stored ratio, which a bare ratio would not allow.
     */
    public val trackPeakEnergy: Float,
    public val framing: AnalysisFraming = AnalysisFraming.CANONICAL,
) {
    init {
        require(rms.size == peak.size && peak.size == energy.size) {
            "envelope arrays must be the same length: ${rms.size}/${peak.size}/${energy.size}"
        }
        require(normalizedEnergy.size == rms.size && loudnessDb.size == rms.size) {
            "every envelope channel must be the same length"
        }
        require(trackPeakEnergy >= 0.0f) { "trackPeakEnergy must not be negative: $trackPeakEnergy" }
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

    public fun normalizedEnergyAt(time: AudioSourceTime): Float =
        interpolate(normalizedEnergy, framing.interpolationAt(time))

    public fun loudnessDbAt(time: AudioSourceTime): Float =
        interpolate(loudnessDb, framing.interpolationAt(time))

    private fun interpolate(values: FloatArray, at: FrameInterpolation): Float {
        if (values.isEmpty()) return 0.0f
        val lower = at.lowerFrame.coerceIn(0L, (values.size - 1).toLong()).toInt()
        val upper = at.upperFrame.coerceIn(0L, (values.size - 1).toLong()).toInt()
        return at.blend(values[lower], values[upper])
    }

    public companion object {

        /**
         * Runs §17.7's phases 2a and 2b in sequence.
         *
         * Convenience for callers that do not need to observe the split — the phases are still
         * distinct, and nothing is published between them because nothing is published here at
         * all: writing to the cache is `audio:cache`'s job, and it happens once, after this
         * returns (§17.7 clause 2).
         */
        public fun compute(frames: AnalysisFrames): ScalarEnvelope = Stage2.finalise(Stage2.measure(frames))
    }
}

/**
 * §17.1 stage 2, in the two phases §17.7 ratifies.
 *
 * The split is not a formality. §17.6 [T-7]'s reference is a whole-track maximum, so a value
 * derived from it cannot exist until the track has been measured — and §17.7 forbids publishing
 * anything derived from a provisional reference, because revising a written frame contradicts
 * §18.1's immutable-once-written contract. Phase 2a therefore produces everything measurable and
 * *nothing* derived; phase 2b derives and hands over a publishable envelope.
 */
public object Stage2 {

    /**
     * **Phase 2a — Measure.** One traversal of the signal; publishes nothing.
     *
     * Produces RMS, Peak, Energy and the §17.6 [T-8] K-weighted loudness, and records
     * `max_m Energy[m]`. A single traversal rather than a prepass plus a sweep: the K-weighting
     * filter is IIR and streams in frame order alongside the raw scalars, so the reference falls
     * out of the pass that has to happen anyway (§17.7 clause 1).
     *
     * The filter runs over the **hop**, not the window. Successive analysis windows overlap by
     * 76.5625% (§17.5), so filtering each window independently would run the recursive filter over
     * the same samples up to four times and produce a state that never corresponds to the real
     * signal. Filtering the signal once, continuously, is the only reading under which the result
     * is the whole-signal filter response.
     */
    public fun measure(frames: AnalysisFrames): Stage2Measurement {
        val count = frames.frameCount.toInt()
        val framing = frames.framing
        val window = framing.windowSamples
        val hop = framing.hopSamples

        val rms = FloatArray(count)
        val peak = FloatArray(count)
        val energy = FloatArray(count)
        val loudness = FloatArray(count)

        // The K-weighted signal, filtered once, continuously, from a fresh state.
        val weighting = KWeighting()
        val weighted = DoubleArray(frames.signal.frameCount)
        for (index in weighted.indices) weighted[index] = weighting.process(frames.signal.samples[index])

        var trackPeakEnergy = 0.0
        val buffer = frames.newFrameBuffer()

        for (frameIndex in 0 until count) {
            frames.readFrame(frameIndex.toLong(), buffer)

            var sumOfSquares = 0.0
            var maximum = 0.0f
            for (sample in buffer) {
                sumOfSquares += sample.toDouble() * sample
                val magnitude = abs(sample)
                if (magnitude > maximum) maximum = magnitude
            }
            energy[frameIndex] = sumOfSquares.toFloat()
            rms[frameIndex] = sqrt(sumOfSquares / window).toFloat()
            peak[frameIndex] = maximum
            if (sumOfSquares > trackPeakEnergy) trackPeakEnergy = sumOfSquares

            // §17.6 [T-8], over the same frame extent, on the continuously filtered signal.
            val start = (frameIndex.toLong() * hop).toInt()
            var weightedSquares = 0.0
            val available = minOf(window, weighted.size - start).coerceAtLeast(0)
            for (offset in 0 until available) {
                val value = weighted[start + offset]
                weightedSquares += value * value
            }
            // Zero-padded tail (§17.5): the padding contributes nothing, and the divisor stays the
            // full window so a tail frame reads quieter rather than artificially louder.
            val meanSquare = weightedSquares / window
            loudness[frameIndex] = if (meanSquare <= 0.0) {
                KWeighting.SILENCE_FLOOR_DB.toFloat()
            } else {
                max(
                    KWeighting.SILENCE_FLOOR_DB,
                    KWeighting.BS1770_OFFSET_DB + 10.0 * log10(meanSquare),
                ).toFloat()
            }
        }

        return Stage2Measurement(rms, peak, energy, loudness, trackPeakEnergy.toFloat(), framing)
    }

    /**
     * **Phase 2b — Finalize.** Derives §17.6 [T-7]'s Normalized Energy from the now-final
     * reference. Touches no audio: a division over an array already in memory (§17.7 clause 1).
     */
    public fun finalise(measurement: Stage2Measurement): ScalarEnvelope {
        val reference = measurement.trackPeakEnergy
        val normalized = FloatArray(measurement.frameCount)
        if (reference > 0.0f) {
            for (index in normalized.indices) normalized[index] = measurement.energy[index] / reference
        }
        // A digitally silent track has `max_m Energy[m] == 0`, making the ratio 0/0. Every frame
        // is defined as 0 — a silent track has no relative energy anywhere, and the alternative
        // (NaN) would propagate into every downstream mapping. §119's silence fixture covers it.

        return ScalarEnvelope(
            rms = measurement.rms,
            peak = measurement.peak,
            energy = measurement.energy,
            normalizedEnergy = normalized,
            loudnessDb = measurement.loudnessDb,
            trackPeakEnergy = reference,
            framing = measurement.framing,
        )
    }
}

/**
 * §17.7 phase 2a's output: measured, complete, and **not publishable**.
 *
 * A separate type from [ScalarEnvelope] on purpose. §17.7 forbids publishing anything before the
 * reference is final, and the cleanest enforcement is that the phase-2a result simply lacks the
 * derived channel — there is no partially-populated envelope that could be written by mistake.
 */
public class Stage2Measurement internal constructor(
    public val rms: FloatArray,
    public val peak: FloatArray,
    public val energy: FloatArray,
    public val loudnessDb: FloatArray,
    /** §17.6 [T-7]'s reference, final as of the end of phase 2a. */
    public val trackPeakEnergy: Float,
    public val framing: AnalysisFraming,
) {
    public val frameCount: Int get() = rms.size
}
