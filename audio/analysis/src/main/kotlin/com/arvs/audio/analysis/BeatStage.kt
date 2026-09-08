package com.arvs.audio.analysis

import com.arvs.audio.beat.BeatDetector
import com.arvs.audio.beat.BeatEvent
import com.arvs.audio.beat.TempoEstimate
import com.arvs.audio.beat.TempoEstimator
import com.arvs.core.model.BeatConfig
import com.arvs.core.time.AnalysisFraming

/**
 * §17.1 stage 4 — onset, beat and tempo, per §21.1 [D-8].
 *
 * The stage is a thin seam by design. All it does is turn the §17.4 spectrum into the onset
 * signal §21.1 defines and hand that to `audio:beat`, which owns the detection arithmetic and
 * knows nothing about spectra. §116.1 fixes the direction — `audio:analysis` → `audio:beat`,
 * never the reverse — and that is what keeps the detector a pure function over a number series,
 * so §9.1's bit-reproducibility is a property of arithmetic rather than of a pipeline.
 *
 * §21.1's onset signal is the same half-wave rectified flux §17 already lists as a stored
 * feature, so this computes [SpectralDescriptors.flux] rather than a second, parallel definition
 * that could drift from it.
 */
public class BeatStage(
    public val framing: AnalysisFraming = AnalysisFraming.CANONICAL,
    public val config: BeatConfig = BeatConfig(),
) {

    /**
     * §21.1's onset signal over a whole track.
     *
     * [spectra] is one normalized magnitude spectrum per §17.5 frame, in frame order. Frame 0 has
     * no predecessor and is defined as 0.
     */
    public fun onsetSignal(spectra: List<FloatArray>): FloatArray {
        if (spectra.isEmpty()) return FloatArray(0)
        return FloatArray(spectra.size) { index ->
            if (index == 0) 0.0f else SpectralDescriptors.flux(spectra[index], spectra[index - 1])
        }
    }

    /** Runs §21.1's detector over an onset signal. */
    public fun detect(onsetSignal: FloatArray): List<BeatEvent> =
        BeatDetector.detect(onsetSignal, framing, config)

    /**
     * Estimates tempo over an onset signal.
     *
     * Kept a separate call rather than folded into [detect]'s result, so §21.1's "tempo
     * confidence must never suppress a valid transient beat" holds by construction: a caller that
     * wants beats never computes a tempo, and nothing in the beat path can consult one.
     */
    public fun estimateTempo(onsetSignal: FloatArray): TempoEstimate =
        TempoEstimator.estimate(onsetSignal, framing, config)
}
