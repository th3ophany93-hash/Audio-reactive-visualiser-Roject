package com.arvs.audio.analysis

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * §17.1 stage 5 — the spectral descriptors and chroma.
 *
 * All five are computed from one frame's normalized magnitude spectrum (§17.4's retained set,
 * bins 1…1024). §17.4 stores them at full float32 precision rather than deriving them at read
 * time from the FP16 spectrum, "because computing them from the reduced FP16 spectrum at read
 * time would be both less accurate and more expensive".
 *
 * ### On the definitions
 *
 * §17 names these features and does not define them. Centroid, flatness and flux have settled,
 * near-universal formulations and are implemented as such. Two carry a **free parameter** that
 * the specification does not fix, and both are marked at the site:
 *
 *  - [rolloff]'s energy fraction, conventionally 0.85.
 *  - [chroma]'s tuning reference, conventionally A4 = 440 Hz.
 *
 * Neither is unrecoverable: §17.4 retains the full spectrum, so a different choice can be
 * recomputed at read time without re-decoding or re-running the FFT — subject to FP16 precision.
 * They are stated decisions pending ratification, not silent ones. See the Step 10 report.
 */
public object SpectralDescriptors {

    /** §17.4's conventional rolloff fraction. A free parameter the specification does not fix. */
    public const val DEFAULT_ROLLOFF_FRACTION: Double = 0.85

    /** Concert-pitch reference for [chroma]. A free parameter the specification does not fix. */
    public const val DEFAULT_TUNING_A4_HZ: Double = 440.0

    /** Number of pitch classes. Fixed by twelve-tone equal temperament, not a free choice. */
    public const val CHROMA_BINS: Int = 12

    /**
     * Spectral centroid — the magnitude-weighted mean frequency, in Hz.
     *
     * `Σ(f_k · |X_k|) / Σ|X_k|`. The "brightness" of the frame. Returns 0 for a silent frame:
     * there is no meaningful centre frequency when there is no energy, and 0/0 would otherwise
     * propagate NaN into every downstream mapping.
     */
    public fun centroid(spectrum: FloatArray, binWidthHz: Double): Float {
        var weighted = 0.0
        var total = 0.0
        for (index in spectrum.indices) {
            val magnitude = spectrum[index].toDouble()
            weighted += (index + 1) * binWidthHz * magnitude
            total += magnitude
        }
        return if (total <= SpectrumStage.EPSILON) 0.0f else (weighted / total).toFloat()
    }

    /**
     * Spectral flux — the half-wave rectified sum of positive magnitude increases between frames.
     *
     * `Σ max(0, |X_k[n]| − |X_k[n−1]|)`. Rectified rather than a plain L2 distance because flux
     * is used as an onset indicator: energy *appearing* marks an attack, energy decaying does not,
     * and an unrectified difference reports both equally. The first frame has no predecessor and
     * is defined as 0.
     */
    public fun flux(spectrum: FloatArray, previous: FloatArray?): Float {
        if (previous == null) return 0.0f
        require(previous.size == spectrum.size) { "spectra must be the same length" }
        var sum = 0.0
        for (index in spectrum.indices) {
            val difference = spectrum[index] - previous[index]
            if (difference > 0.0f) sum += difference.toDouble()
        }
        return sum.toFloat()
    }

    /**
     * Spectral rolloff — the frequency below which [fraction] of the total magnitude lies, in Hz.
     *
     * [fraction] is a free parameter; see [DEFAULT_ROLLOFF_FRACTION]. Returns 0 for a silent
     * frame, for the same reason as [centroid].
     */
    public fun rolloff(
        spectrum: FloatArray,
        binWidthHz: Double,
        fraction: Double = DEFAULT_ROLLOFF_FRACTION,
    ): Float {
        require(fraction in 0.0..1.0) { "fraction must be within [0,1]: $fraction" }
        var total = 0.0
        for (value in spectrum) total += value.toDouble()
        if (total <= SpectrumStage.EPSILON) return 0.0f

        val threshold = total * fraction
        var running = 0.0
        for (index in spectrum.indices) {
            running += spectrum[index].toDouble()
            if (running >= threshold) return ((index + 1) * binWidthHz).toFloat()
        }
        return (spectrum.size * binWidthHz).toFloat()
    }

    /**
     * Spectral flatness — the ratio of the geometric to the arithmetic mean of the power spectrum.
     *
     * Wiener entropy: 1.0 for white noise (a perfectly flat spectrum), approaching 0 for a pure
     * tone. The geometric mean is computed in the log domain — the direct product of 1024 powers
     * underflows to zero long before it can be rooted.
     *
     * A silent frame returns 0: silence is not "flat", it is absent, and reporting 1.0 would make
     * every silent passage look like white noise to a reactive mapping.
     */
    public fun flatness(spectrum: FloatArray): Float {
        var logSum = 0.0
        var arithmeticSum = 0.0
        for (value in spectrum) {
            val power = value.toDouble() * value
            arithmeticSum += power
            // Every bin contributes, with its power floored so the logarithm stays finite.
            //
            // Skipping quiet bins instead — while still dividing by the full bin count — would
            // inflate the geometric mean enormously: a pure tone has almost every bin near zero,
            // so the sum would cover three bins and be averaged over 1024, giving exp(≈0) ≈ 1 and
            // reporting the tone as maximally flat. The floor is what makes near-empty bins pull
            // the geometric mean *down*, which is the whole mechanism flatness relies on.
            logSum += ln(max(power, SpectrumStage.EPSILON))
        }
        if (arithmeticSum <= SpectrumStage.EPSILON) return 0.0f

        val geometricMean = exp(logSum / spectrum.size)
        val arithmeticMean = arithmeticSum / spectrum.size
        return (geometricMean / arithmeticMean).toFloat().coerceIn(0.0f, 1.0f)
    }

    /**
     * Chroma — energy folded onto the twelve pitch classes, index 0 = C.
     *
     * Each bin's power is added to the pitch class of its centre frequency, computed against
     * [tuningA4Hz]. Bins below the lowest musical frequency or above the top of the piano range
     * are ignored: they carry no pitch-class information and would smear rumble and cymbal wash
     * evenly across all twelve.
     *
     * The result is normalised so its maximum is 1.0, which makes it usable as a reactive source
     * without knowing the track's absolute level. A silent frame returns all zeros.
     */
    public fun chroma(
        spectrum: FloatArray,
        binWidthHz: Double,
        tuningA4Hz: Double = DEFAULT_TUNING_A4_HZ,
        into: FloatArray = FloatArray(CHROMA_BINS),
    ): FloatArray {
        require(into.size == CHROMA_BINS) { "chroma buffer must be $CHROMA_BINS, got ${into.size}" }
        into.fill(0.0f)

        for (index in spectrum.indices) {
            val frequency = (index + 1) * binWidthHz
            if (frequency < LOWEST_PITCHED_HZ || frequency > HIGHEST_PITCHED_HZ) continue
            val magnitude = spectrum[index].toDouble()
            if (magnitude <= SpectrumStage.EPSILON) continue

            // MIDI note number, then its pitch class. A4 = MIDI 69 by definition.
            val midi = 69.0 + 12.0 * log2(frequency / tuningA4Hz)
            val pitchClass = Math.floorMod(midi.roundToInt() - MIDI_C0, CHROMA_BINS)
            into[pitchClass] += (magnitude * magnitude).toFloat()
        }

        val peak = into.fold(0.0f) { best, value -> max(best, value) }
        if (peak > 0.0f) {
            for (index in into.indices) into[index] /= peak
        }
        return into
    }

    /** C0, MIDI note 12 — the origin the pitch class is measured from. */
    private const val MIDI_C0 = 12

    /** Roughly C1; below this, content is rumble rather than pitch. */
    private const val LOWEST_PITCHED_HZ = 32.7

    /** Roughly C8, the top of a piano. Above it, content is overtone wash. */
    private const val HIGHEST_PITCHED_HZ = 4_186.0
}
