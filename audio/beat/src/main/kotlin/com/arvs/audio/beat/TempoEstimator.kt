package com.arvs.audio.beat

import com.arvs.core.model.BeatConfig
import com.arvs.core.time.AnalysisFraming
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** A tempo estimate and how strongly the onset signal supported it. */
public data class TempoEstimate(
    /** Estimated tempo in BPM, always inside §21.1's normative 40…240 range, or 0 if none. */
    public val bpm: Double,
    /** Normalized autocorrelation peak height in [0,1]. 0 when no periodicity was found. */
    public val confidence: Float,
) {
    /** Whether any periodicity was found at all. */
    public val isPresent: Boolean get() = bpm > 0.0
}

/**
 * §21.1 [D-8] — deterministic tempo estimation by autocorrelation of the onset signal.
 *
 * **Structurally separate from [BeatDetector], which is the point.** §21.1 requires that "tempo
 * confidence must never suppress a valid transient beat", and §21 that beat detection "must not
 * assume all music has stable BPM". Here that is not a rule to be remembered but a fact about the
 * code: [BeatDetector.detect] does not call this class, cannot reach it, and takes no tempo input.
 * There is no path by which a tempo estimate could gate a beat.
 *
 * The search range is §21.1's normative 40…240 BPM.
 */
public object TempoEstimator {

    public fun estimate(
        flux: FloatArray,
        framing: AnalysisFraming = AnalysisFraming.CANONICAL,
        config: BeatConfig = BeatConfig(),
    ): TempoEstimate {
        // A lag of L frames is a period of L / frameRate seconds, i.e. 60·frameRate/L BPM. The
        // faster tempo gives the shorter lag, so max BPM sets the minimum lag.
        val minLag = max(1, floor(60.0 * framing.frameRateHz / config.maxTempoBpm).toInt())
        val maxLag = (60.0 * framing.frameRateHz / config.minTempoBpm).roundToInt()
        if (flux.size <= minLag + 1) return TempoEstimate(0.0, 0.0f)

        // Autocorrelate the mean-removed signal: without removal the DC component dominates and
        // every lag scores nearly the same, which reports a tempo for constant noise.
        var mean = 0.0
        for (value in flux) mean += value
        mean /= flux.size
        val centred = DoubleArray(flux.size) { flux[it] - mean }

        var energy = 0.0
        for (value in centred) energy += value * value
        if (energy <= BeatDetector.EPSILON) return TempoEstimate(0.0, 0.0f)

        var bestLag = 0
        var bestScore = 0.0
        for (lag in minLag..minOf(maxLag, flux.size - 1)) {
            var sum = 0.0
            for (index in lag until centred.size) sum += centred[index] * centred[index - lag]
            // Normalized by the overlap length so a short lag is not favoured merely for having
            // more terms in its sum.
            val score = sum / (centred.size - lag)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0) return TempoEstimate(0.0, 0.0f)

        val bpm = 60.0 * framing.frameRateHz / bestLag
        val confidence = (bestScore / (energy / centred.size)).coerceIn(0.0, 1.0)
        return TempoEstimate(bpm, confidence.toFloat())
    }
}
