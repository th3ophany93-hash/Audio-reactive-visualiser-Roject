package com.arvs.audio.analysis

import com.arvs.core.model.FrequencyBand
import com.arvs.core.model.WindowFunction
import com.arvs.core.time.AnalysisFraming
import kotlin.math.ln
import kotlin.math.max

/**
 * §17.1 stage 3 — FFT magnitude spectrum and the §20 default frequency bands.
 *
 * ### §17.4's retained representation
 *
 * §17.4 fixes it precisely: "**1024 magnitude bins**, **100 Hz** temporal sampling, **FP16**
 * storage… a real FFT of a 2048-sample window yields 1025 unique bins (DC through Nyquist). The
 * retained set is **bins 1…1024**; the DC bin (0 Hz) is discarded, as it carries no musical
 * information and is contaminated by DC offset."
 *
 * Every retained frame is a **measured** frame — the framing is already native 100 Hz (§17.5), so
 * nothing here interpolates or upsamples to reach the storage rate. §17.5 forbids it outright.
 *
 * ### Magnitude scaling
 *
 * §17.4: "magnitudes are normalized against the canonical signal's full-scale reference before
 * FP16 conversion." Implemented as
 *
 * ```
 * normalized[k] = |X[k]| / (windowSamples / 2 · coherentGain(window))
 * ```
 *
 * so a full-scale sine centred on a bin reads **1.0** in that bin, whatever window is configured.
 * Dividing by the coherent gain is what makes the reading window-independent: without it,
 * switching from Hann to Blackman-Harris would scale every cached magnitude by 0.72 — a silent,
 * systematic shift that looks like a quieter mix rather than a bug.
 *
 * The choice of *which* full-scale reference (a sine at 1.0, as here) is a stated decision, not a
 * specification value; §17.4 names the concept without fixing the constant. It is a pure scale
 * factor over a spectrum whose configuration is itself in `analysisConfigHash`, so a different
 * choice is a read-time constant transform rather than a re-analysis. See the Step 10 report.
 */
public class SpectrumStage(
    public val framing: AnalysisFraming = AnalysisFraming.CANONICAL,
    public val windowFunction: WindowFunction = WindowFunction.HANN,
) {
    private val fft = Fft(framing.windowSamples)
    private val window = WindowFunctions.coefficients(windowFunction, framing.windowSamples)
    private val fullScaleReference =
        framing.windowSamples / 2.0 * WindowFunctions.coherentGain(windowFunction, framing.windowSamples)

    /** §17.4: bins 1…1024 for the canonical 2048-point window. DC is discarded. */
    public val retainedBinCount: Int get() = framing.windowSamples / 2

    /** Centre frequency of retained bin [index] (0-based over the retained set, so bin 1 of the FFT). */
    public fun frequencyOf(index: Int): Double = (index + 1) * framing.binWidthHz

    /**
     * Computes the normalized magnitude spectrum for one frame.
     *
     * [into] receives [retainedBinCount] values — the DC bin is dropped before it is written, so
     * a caller cannot accidentally include it.
     */
    public fun spectrumOf(frame: FloatArray, scratch: DoubleArray, into: FloatArray) {
        require(frame.size == framing.windowSamples) {
            "frame must be ${framing.windowSamples} samples, got ${frame.size}"
        }
        require(into.size == retainedBinCount) {
            "spectrum buffer must be $retainedBinCount bins, got ${into.size}"
        }
        val windowed = FloatArray(frame.size) { frame[it] * window[it] }
        fft.magnitudeSpectrum(windowed, scratch)
        for (index in 0 until retainedBinCount) {
            into[index] = (scratch[index + 1] / fullScaleReference).toFloat()
        }
    }

    public fun newScratch(): DoubleArray = fft.newSpectrumBuffer()

    public fun newSpectrumBuffer(): FloatArray = FloatArray(retainedBinCount)

    /**
     * Energy in each of the §20 bands for one frame's spectrum.
     *
     * `Σ |X[k]|²` over the bins whose centre frequency falls in `[lowHz, highHz)` — the same
     * sum-of-squares convention §17.6 [T-9] ratified for Energy, so band energy and global energy
     * are the same quantity measured over different supports rather than two different things.
     *
     * Half-open at the top so adjacent §20 bands (…250, 250…) never double-count a bin.
     */
    public fun bandEnergies(spectrum: FloatArray, bands: List<FrequencyBand>, into: FloatArray) {
        require(into.size == bands.size) { "band buffer must be ${bands.size}, got ${into.size}" }
        into.fill(0.0f)
        for (index in spectrum.indices) {
            val frequency = frequencyOf(index)
            val magnitude = spectrum[index]
            val power = magnitude * magnitude
            for (bandIndex in bands.indices) {
                val band = bands[bandIndex]
                if (frequency >= band.lowHz && frequency < band.highHz) {
                    into[bandIndex] += power
                    break
                }
            }
        }
    }

    public companion object {

        /**
         * §17's "Log Spectrum", derived at read time — never stored.
         *
         * §17.4 is explicit: it is "a re-binning of the retained linear magnitude spectrum — an
         * axis transform, not new information", so it belongs on the read path and costs no disk.
         *
         * Bins are spaced logarithmically between [lowHz] and [highHz]; each output bin takes the
         * mean of the linear bins falling inside it. [binCount] is a display choice, not a cache
         * property — nothing derived here enters `analysisConfigHash`.
         */
        public fun logSpectrum(
            spectrum: FloatArray,
            binWidthHz: Double,
            binCount: Int,
            lowHz: Double = 20.0,
            highHz: Double = 20_000.0,
        ): FloatArray {
            require(binCount > 0) { "binCount must be positive: $binCount" }
            require(lowHz > 0 && highHz > lowHz) { "invalid frequency range $lowHz..$highHz" }

            val logLow = ln(lowHz)
            val logHigh = ln(highHz)
            val out = FloatArray(binCount)
            val counts = IntArray(binCount)

            for (index in spectrum.indices) {
                val frequency = (index + 1) * binWidthHz
                if (frequency < lowHz || frequency >= highHz) continue
                val position = (ln(frequency) - logLow) / (logHigh - logLow)
                val target = (position * binCount).toInt().coerceIn(0, binCount - 1)
                out[target] += spectrum[index]
                counts[target]++
            }
            for (index in out.indices) {
                if (counts[index] > 0) out[index] /= counts[index]
            }
            // A log bin narrower than one linear bin receives nothing — unavoidable at the low
            // end, where log bins are far narrower than 23.4 Hz. An empty bin takes its nearest
            // populated neighbour, which is honest about the resolution limit; leaving a zero
            // would draw a notch the signal does not have.
            //
            // Both directions are needed. Carrying forward alone cannot fill the *leading* run,
            // since there is no earlier value to carry — those bins sit below the first linear
            // bin entirely, and would stay zero.
            val firstPopulated = counts.indexOfFirst { it > 0 }
            if (firstPopulated < 0) return out          // nothing in range at all: honestly empty
            for (index in 0 until firstPopulated) out[index] = out[firstPopulated]
            for (index in firstPopulated + 1 until out.size) {
                if (counts[index] == 0) out[index] = out[index - 1]
            }
            return out
        }

        /** Smallest magnitude treated as non-zero, to keep logarithms finite. */
        internal const val EPSILON: Double = 1e-12

        internal fun safeLog(value: Double): Double = ln(max(value, EPSILON))
    }
}
