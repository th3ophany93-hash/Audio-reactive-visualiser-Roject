package com.arvs.testing.audio

import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.PcmBuffer
import com.arvs.core.time.AnalysisFraming
import com.arvs.core.time.TimeSpan
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One §119 fixture: the samples, their format, and the analytic truth they can be checked
 * against.
 *
 * The plan's first test suite is "DSP unit tests against **analytic truth**" — sine to a
 * known RMS, impulse to a flat spectrum, clipping to full-scale peak. That only works if the
 * expected values travel with the fixture instead of being retyped, approximately, at each
 * call site. [expectedRms] and [expectedPeak] are computed here from the samples, so they are
 * exact for the fixture as generated; a test asserting a *closed-form* expectation (sine RMS
 * = A/√2) states it independently, and the two agreeing is the actual check.
 */
public data class AudioFixture(
    public val name: String,
    /** Interleaved samples, normalised to `[-1, 1]`. */
    public val samples: FloatArray,
    public val channelCount: Int,
    public val sampleRateHz: Int,
) {
    init {
        require(name.isNotBlank()) { "a fixture must be named — the name appears in failures" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(samples.size % channelCount == 0) {
            "interleaved sample count (${samples.size}) must be a multiple of channelCount ($channelCount)"
        }
    }

    public val frameCount: Int get() = samples.size / channelCount

    public val duration: TimeSpan
        get() = TimeSpan(frameCount.toLong() * 1_000_000L / sampleRateHz)

    public val format: AudioFormatInfo
        get() = AudioFormatInfo(
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            duration = duration,
            codecDescription = "synthetic/pcm-float",
        )

    public fun toPcmBuffer(startFrame: Long = 0): PcmBuffer =
        PcmBuffer(samples.copyOf(), channelCount, sampleRateHz, startFrame)

    /** RMS across all interleaved samples. */
    public val expectedRms: Double
        get() {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (sample in samples) sum += sample.toDouble() * sample.toDouble()
            return sqrt(sum / samples.size)
        }

    /** Absolute peak across all interleaved samples. */
    public val expectedPeak: Float
        get() = samples.fold(0.0f) { peak, sample -> maxOf(peak, abs(sample)) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFixture) return false
        return name == other.name &&
            channelCount == other.channelCount &&
            sampleRateHz == other.sampleRateHz &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + channelCount
        result = 31 * result + sampleRateHz
        return result
    }
}

/**
 * The nine §119 fixtures at the canonical 48 kHz rate (§17.3), one second each unless stated.
 *
 * A named catalogue rather than nine loose factory calls, so that "the §119 set" is a thing
 * the build can enumerate — [ALL] is what lets a test assert the set is complete, and what a
 * future golden-vector generator will iterate.
 */
public object Fixtures {

    public const val DEFAULT_SAMPLE_RATE_HZ: Int = AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ
    public const val DEFAULT_FRAMES: Int = DEFAULT_SAMPLE_RATE_HZ // one second

    public fun silence(frames: Int = DEFAULT_FRAMES): AudioFixture =
        mono("silence", SignalGenerators.silence(frames))

    public fun sine440(frames: Int = DEFAULT_FRAMES, amplitude: Float = 0.5f): AudioFixture =
        mono("sine-440", SignalGenerators.sine(frames, 440.0, DEFAULT_SAMPLE_RATE_HZ, amplitude))

    public fun sine1000(frames: Int = DEFAULT_FRAMES, amplitude: Float = 0.5f): AudioFixture =
        mono("sine-1000", SignalGenerators.sine(frames, 1_000.0, DEFAULT_SAMPLE_RATE_HZ, amplitude))

    public fun sine10000(frames: Int = DEFAULT_FRAMES, amplitude: Float = 0.5f): AudioFixture =
        mono("sine-10000", SignalGenerators.sine(frames, 10_000.0, DEFAULT_SAMPLE_RATE_HZ, amplitude))

    public fun bassSweep(frames: Int = DEFAULT_FRAMES): AudioFixture =
        mono("bass-sweep", SignalGenerators.bassSweep(frames, 20.0, 200.0, DEFAULT_SAMPLE_RATE_HZ))

    public fun whiteNoise(frames: Int = DEFAULT_FRAMES): AudioFixture =
        mono("white-noise", SignalGenerators.whiteNoise(frames))

    public fun impulse(frames: Int = DEFAULT_FRAMES): AudioFixture =
        mono("impulse", SignalGenerators.impulse(frames))

    public fun drums(frames: Int = DEFAULT_FRAMES * 4): AudioFixture =
        mono("drums-120bpm", SignalGenerators.drumPattern(frames, DEFAULT_SAMPLE_RATE_HZ, tempoBpm = 120.0))

    public fun clipping(frames: Int = DEFAULT_FRAMES): AudioFixture =
        mono("clipping", SignalGenerators.clipping(frames, 220.0, DEFAULT_SAMPLE_RATE_HZ))

    public fun veryQuiet(frames: Int = DEFAULT_FRAMES): AudioFixture =
        mono("very-quiet", SignalGenerators.veryQuiet(frames, 440.0, DEFAULT_SAMPLE_RATE_HZ))

    /**
     * §119's stereo fixture, with genuinely different channels (§17.3).
     *
     * Left is a 440 Hz tone, right a 660 Hz tone at a different amplitude. Both the
     * frequencies and the amplitudes differ, so a downmix that drops a channel, averages the
     * wrong pair, or swaps them is distinguishable from the correct `0.5·L + 0.5·R`.
     */
    public fun stereo(frames: Int = DEFAULT_FRAMES): AudioFixture = AudioFixture(
        name = "stereo-440-660",
        samples = SignalGenerators.interleaveStereo(
            left = SignalGenerators.sine(frames, 440.0, DEFAULT_SAMPLE_RATE_HZ, amplitude = 0.6f),
            right = SignalGenerators.sine(frames, 660.0, DEFAULT_SAMPLE_RATE_HZ, amplitude = 0.3f),
        ),
        channelCount = 2,
        sampleRateHz = DEFAULT_SAMPLE_RATE_HZ,
    )

    /** The complete §119 set. Short fixtures, so enumerating them stays cheap. */
    public fun all(frames: Int = DEFAULT_SAMPLE_RATE_HZ / 10): List<AudioFixture> = listOf(
        silence(frames),
        sine440(frames),
        sine1000(frames),
        sine10000(frames),
        bassSweep(frames),
        whiteNoise(frames),
        impulse(frames),
        drums(frames),
        clipping(frames),
        veryQuiet(frames),
        stereo(frames),
    )

    private fun mono(name: String, samples: FloatArray) =
        AudioFixture(name, samples, channelCount = 1, sampleRateHz = DEFAULT_SAMPLE_RATE_HZ)
}
