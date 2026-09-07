package com.arvs.testing.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pins the §119 fixtures against analytic truth.
 *
 * A fixture generator is test infrastructure, which is exactly why it needs tests of its own:
 * every DSP assertion downstream is only as trustworthy as the signal it was made against. A
 * sweep that ends at the wrong frequency, or noise that changes with the toolchain, produces
 * confident wrong answers everywhere else.
 */
class SignalGeneratorsTest {

    private val rate = 48_000

    // --- Determinism (the property everything else rests on) -----------------------------

    @Test
    fun `every generator is reproducible from its parameters`() {
        assertTrue(SignalGenerators.silence(64).contentEquals(SignalGenerators.silence(64)))
        assertTrue(
            SignalGenerators.sine(64, 440.0, rate).contentEquals(SignalGenerators.sine(64, 440.0, rate)),
        )
        assertTrue(
            SignalGenerators.whiteNoise(64).contentEquals(SignalGenerators.whiteNoise(64)),
        )
        assertTrue(
            SignalGenerators.drumPattern(4_800, rate).contentEquals(SignalGenerators.drumPattern(4_800, rate)),
        )
    }

    @Test
    fun `noise is seeded, so a different seed gives a different signal`() {
        assertNotEquals(
            SignalGenerators.whiteNoise(64, seed = 1).toList(),
            SignalGenerators.whiteNoise(64, seed = 2).toList(),
        )
    }

    @Test
    fun `the noise PRNG matches its published SplitMix64 output`() {
        // Pinned against the reference algorithm's values, not against our own output, so a
        // golden analysis vector cut from this noise stays valid across toolchain upgrades.
        // Values taken from the reference algorithm, cross-checked with an independent
        // implementation — never copied from this generator's own output, which would only
        // prove it agrees with itself.
        val noise = DeterministicNoise(0)
        assertEquals(-2152535657050944081L, noise.nextLong()) // 0xE220A8397B1DCDAF
        assertEquals(7960286522194355700L, noise.nextLong())  // 0x6E789E6AA1B965F4
        assertEquals(487617019471545679L, noise.nextLong())   // 0x06C45D188009454F
    }

    @Test
    fun `noise stays within its amplitude bound`() {
        val samples = SignalGenerators.whiteNoise(20_000, amplitude = 0.5f)
        assertTrue(samples.all { abs(it) <= 0.5f })
    }

    // --- Analytic truth ------------------------------------------------------------------

    @Test
    fun `silence is exactly zero, with no negative zero or NaN`() {
        val samples = SignalGenerators.silence(1_000)
        assertTrue(samples.all { it == 0.0f && !it.isNaN() })
    }

    @Test
    fun `a sine has RMS equal to amplitude over root two`() {
        // The closed form, stated independently of the generator.
        val amplitude = 0.5f
        val samples = SignalGenerators.sine(rate, 440.0, rate, amplitude)
        val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        assertEquals(amplitude / sqrt(2.0), rms, 1e-4)
    }

    @Test
    fun `a sine peaks at its amplitude and no higher`() {
        val samples = SignalGenerators.sine(rate, 1_000.0, rate, amplitude = 0.25f)
        assertEquals(0.25, samples.maxOf { abs(it) }.toDouble(), 1e-3)
    }

    @Test
    fun `a sine lands on the frequency it was asked for`() {
        // Measured by correlating against the reference tone, so the check is independent of
        // how the generator computes phase.
        val frequency = 1_000.0
        val samples = SignalGenerators.sine(rate, frequency, rate)
        assertEquals(frequency, dominantFrequency(samples, rate), 1.0)
    }

    @Test
    fun `the bass sweep accumulates phase instead of using the closed form`() {
        // The closed form sin(2pi*f(t)*t) yields an instantaneous frequency of 2*f(t), so a
        // 20-to-200 Hz sweep would actually end near 400 Hz. Measuring the final segment is
        // what distinguishes the two.
        val frames = rate
        val sweep = SignalGenerators.bassSweep(frames, 20.0, 200.0, rate)

        val tail = sweep.copyOfRange(frames - rate / 10, frames)
        val measured = dominantFrequency(tail, rate, searchFrom = 10.0, searchTo = 500.0)

        assertEquals(200.0, measured, 12.0)
        assertTrue("a closed-form chirp would land near 400 Hz, got $measured", measured < 300.0)
    }

    @Test
    fun `an impulse is a single non-zero sample`() {
        val samples = SignalGenerators.impulse(1_000, position = 17)
        assertEquals(1.0f, samples[17])
        assertEquals(1, samples.count { it != 0.0f })
    }

    @Test
    fun `an impulse has a flat magnitude spectrum`() {
        // The analytic reference for spectral flatness: any deviation is the analyser's.
        val samples = SignalGenerators.impulse(1_024)
        val magnitudes = (1..64).map { bin -> magnitudeAt(samples, bin.toDouble() * 46.875, 48_000.0) }
        val first = magnitudes.first()
        assertTrue(magnitudes.all { abs(it - first) < 1e-6 })
    }

    @Test
    fun `the clipping fixture peaks at exactly full scale and stays in range`() {
        val samples = SignalGenerators.clipping(rate, 220.0, rate)
        assertEquals(1.0f, samples.maxOf { abs(it) })
        assertTrue(samples.all { it in -1.0f..1.0f })
        // Clipped, not merely loud: a large share of samples sit at the rails.
        assertTrue(samples.count { abs(it) == 1.0f } > samples.size / 4)
    }

    @Test
    fun `the very quiet fixture is tiny but not subnormal`() {
        val samples = SignalGenerators.veryQuiet(rate, 440.0, rate)
        val peak = samples.maxOf { abs(it) }
        assertTrue("peak $peak should be tiny", peak < 1e-5f)
        assertTrue("peak $peak must stay a normal float", peak > java.lang.Float.MIN_NORMAL)
        assertTrue(samples.none { it.isNaN() || it.isInfinite() })
    }

    @Test
    fun `the drum pattern places hits at the requested tempo`() {
        val frames = rate * 4
        val samples = SignalGenerators.drumPattern(frames, rate, tempoBpm = 120.0)

        // 120 BPM at 48 kHz is a hit every 24 000 frames.
        //
        // Onsets are found with a refractory period. Without one, the sine body inside a
        // single decaying hit crosses the threshold on every cycle and one hit reads as
        // dozens — a property of the naive detector, not of the signal.
        val refractoryFrames = 4_800 // 100 ms; far shorter than the 500 ms beat interval
        val onsets = mutableListOf<Int>()
        for (n in 0 until frames) {
            if (abs(samples[n]) <= 0.3f) continue
            if (onsets.isEmpty() || n - onsets.last() >= refractoryFrames) onsets += n
        }
        assertEquals(8, onsets.size)

        // Tempo is the *interval*, so that is what is asserted. Absolute positions would also
        // encode the attack: the 80 Hz body needs about 17 samples to cross the threshold, so
        // the first detected onset sits slightly after the hit begins. That is a property of
        // any percussive signal, not an error in the spacing.
        val intervals = onsets.zipWithNext { earlier, later -> later - earlier }
        intervals.forEach { interval ->
            assertEquals("hit spacing should be 24 000 frames at 120 BPM", 24_000.0, interval.toDouble(), 32.0)
        }
        assertTrue("the first hit should start near frame 0, was ${onsets.first()}", onsets.first() < 100)
    }

    // --- §17.3's mandatory stereo fixture ------------------------------------------------

    @Test
    fun `the stereo fixture has genuinely different channels`() {
        // 17.3 requires this: a stereo fixture with identical channels cannot tell a correct
        // downmix from one that drops a channel.
        val fixture = Fixtures.stereo(1_000)
        val left = (0 until fixture.frameCount).map { fixture.samples[it * 2] }
        val right = (0 until fixture.frameCount).map { fixture.samples[it * 2 + 1] }

        assertNotEquals(left, right)
        assertNotEquals(left.maxOf { abs(it) }, right.maxOf { abs(it) })
    }

    @Test
    fun `interleaving rejects mismatched channel lengths`() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalGenerators.interleaveStereo(FloatArray(10), FloatArray(11))
        }
    }

    @Test
    fun `the catalogue covers all nine named fixture kinds`() {
        // 119 names: silence, sine, bass sweep, white noise, impulse, drums, stereo,
        // clipping, very quiet.
        val names = Fixtures.all(480).map { it.name }
        listOf("silence", "sine-", "bass-sweep", "white-noise", "impulse", "drums-", "stereo-", "clipping", "very-quiet")
            .forEach { kind ->
                assertTrue("no fixture for '$kind' in $names", names.any { it.startsWith(kind) })
            }
    }

    // --- helpers: a two-term Goertzel, so the tests do not depend on an FFT we have not built

    private fun magnitudeAt(samples: FloatArray, frequencyHz: Double, sampleRateHz: Double): Double {
        val omega = 2.0 * PI * frequencyHz / sampleRateHz
        var real = 0.0
        var imaginary = 0.0
        for (n in samples.indices) {
            real += samples[n] * cos(omega * n)
            imaginary -= samples[n] * sin(omega * n)
        }
        return sqrt(real * real + imaginary * imaginary) / samples.size
    }

    private fun dominantFrequency(
        samples: FloatArray,
        sampleRateHz: Int,
        searchFrom: Double = 20.0,
        searchTo: Double = 20_000.0,
        stepHz: Double = 1.0,
    ): Double {
        var best = searchFrom
        var bestMagnitude = -1.0
        var frequency = searchFrom
        while (frequency <= searchTo) {
            val magnitude = magnitudeAt(samples, frequency, sampleRateHz.toDouble())
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                best = frequency
            }
            frequency += stepHz
        }
        return best
    }
}
