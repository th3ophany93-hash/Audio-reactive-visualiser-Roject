package com.arvs.audio.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An in-place radix-2 Cooley–Tukey FFT, sized for §17.5's 2048-sample analysis window.
 *
 * Written here rather than pulled from a library for the reason that governs every persistent
 * format in this project: §9.1 requires the cache to be bit-reproducible, and a library's
 * arithmetic — vectorisation, FMA contraction, twiddle-factor generation — is not something this
 * project controls or can pin. This implementation's operation order is fixed and its twiddle
 * factors are precomputed once from `cos`/`sin`, so identical input yields identical output on
 * every JVM.
 *
 * The twiddle table is built per instance and reused across frames: a five-minute track is
 * ~30 000 frames, and recomputing 2048 trig pairs per frame would dominate the stage's cost for
 * no benefit.
 *
 * Not thread-safe — the scratch buffers are instance state. One instance per analysis pass.
 */
public class Fft(public val size: Int) {

    init {
        require(size > 0 && size and (size - 1) == 0) {
            "FFT size must be a power of two: $size"
        }
    }

    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)

    /**
     * Magnitude spectrum of a real input, returning bins `0 … size/2` (DC through Nyquist).
     *
     * `size/2 + 1` values — 1025 for the canonical 2048-point window. §17.4 discards the DC bin
     * and retains 1…1024; that selection belongs to the retention step, not here, so this stays a
     * plain transform.
     */
    public fun magnitudeSpectrum(samples: FloatArray, into: DoubleArray) {
        require(samples.size == size) { "input must be $size samples, got ${samples.size}" }
        require(into.size == size / 2 + 1) { "output must be ${size / 2 + 1} bins, got ${into.size}" }

        for (index in 0 until size) {
            real[index] = samples[index].toDouble()
            imaginary[index] = 0.0
        }
        transform()

        for (bin in 0..size / 2) {
            val re = real[bin]
            val im = imaginary[bin]
            into[bin] = sqrt(re * re + im * im)
        }
    }

    public fun newSpectrumBuffer(): DoubleArray = DoubleArray(size / 2 + 1)

    private fun transform() {
        // Bit-reversal permutation.
        var target = 0
        for (index in 0 until size) {
            if (target > index) {
                var swap = real[index]; real[index] = real[target]; real[target] = swap
                swap = imaginary[index]; imaginary[index] = imaginary[target]; imaginary[target] = swap
            }
            var mask = size shr 1
            while (mask in 1..target) {
                target -= mask
                mask = mask shr 1
            }
            target += mask
        }

        // Butterfly stages.
        var stageSize = 2
        while (stageSize <= size) {
            val halfStage = stageSize / 2
            val tableStep = size / stageSize
            var start = 0
            while (start < size) {
                var tableIndex = 0
                for (offset in start until start + halfStage) {
                    val partner = offset + halfStage
                    val wr = cosTable[tableIndex]
                    val wi = -sinTable[tableIndex]
                    val tr = real[partner] * wr - imaginary[partner] * wi
                    val ti = real[partner] * wi + imaginary[partner] * wr
                    real[partner] = real[offset] - tr
                    imaginary[partner] = imaginary[offset] - ti
                    real[offset] += tr
                    imaginary[offset] += ti
                    tableIndex += tableStep
                }
                start += stageSize
            }
            stageSize = stageSize shl 1
        }
    }
}
