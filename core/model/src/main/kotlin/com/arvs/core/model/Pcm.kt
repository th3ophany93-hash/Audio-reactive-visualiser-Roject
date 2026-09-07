package com.arvs.core.model

import com.arvs.core.time.TimeSpan

/**
 * The PCM contracts that sit between decoding and analysis.
 *
 * These live in `core:model` rather than in `audio:decoder` on purpose. §15 requires the
 * audio components to be independent, and the module-graph check (§116.1) enforces it: the
 * analyser consumes *samples*, not a codec, so `audio:analysis` has no dependency on
 * `audio:decoder` at all. That keeps the DSP unit-testable on the JVM against §119's
 * synthetic fixtures, with no device, no Robolectric and no MediaCodec in the loop.
 *
 * `:app` is what wires a decoder's output into the analyser.
 */

/**
 * Properties of a decoded source, preserved exactly as the source presents them.
 *
 * §17.3 is explicit that the canonical mono/48 kHz analysis signal is an *analysis domain*,
 * not an output format, and that the original channel count, layout and sample rate must be
 * preserved. Playback (§14.1, §16) and any future export (§136) use the original source —
 * so this type describes the source, never the canonicalised analysis signal.
 */
public data class AudioFormatInfo(
    public val sampleRateHz: Int,
    public val channelCount: Int,
    public val duration: TimeSpan,
    /** Container/codec description, for §97-categorised error reporting. */
    public val codecDescription: String? = null,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        require(duration.micros >= 0) { "duration must not be negative: $duration" }
    }

    /** Total frames (sample positions), independent of channel count. */
    public val totalFrames: Long get() = duration.micros * sampleRateHz / 1_000_000L

    public val isMono: Boolean get() = channelCount == 1
    public val isStereo: Boolean get() = channelCount == 2

    /** Whether the source is already at §17.3's canonical rate and needs no resampling. */
    public val isCanonicalRate: Boolean
        get() = sampleRateHz == com.arvs.core.time.AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ
}

/**
 * A block of interleaved PCM, normalised to `[-1, 1]` floats.
 *
 * Interleaved (rather than planar) because that is what both `MediaCodec` and `AudioTrack`
 * produce and accept, so the common path involves no repacking.
 */
public data class PcmBuffer(
    /** Interleaved samples: `[ch0[0], ch1[0], ch0[1], ch1[1], …]`. */
    public val samples: FloatArray,
    public val channelCount: Int,
    public val sampleRateHz: Int,
    /** Index of this buffer's first frame within the source. */
    public val startFrame: Long,
) {
    init {
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(startFrame >= 0) { "startFrame must not be negative: $startFrame" }
        require(samples.size % channelCount == 0) {
            "interleaved sample count (${samples.size}) must be a multiple of " +
                "channelCount ($channelCount)"
        }
    }

    /** Number of frames (sample positions) in this buffer. */
    public val frameCount: Int get() = samples.size / channelCount

    /**
     * Reduces this buffer to §17.3's canonical mono analysis signal.
     *
     * Stereo is `0.5·L + 0.5·R`, exactly as ratified. Mono passes through unchanged. More
     * than two channels are downmixed by equal-weight averaging — the arithmetic
     * generalisation of which the ratified stereo rule is the two-channel case, flagged as
     * such in §17.3 so it is a documented rule rather than a silent assumption.
     *
     * This does not resample: §17.3's rate conversion to 48 kHz is a separate, explicitly
     * deterministic stage.
     */
    public fun toCanonicalMono(): FloatArray {
        if (channelCount == 1) return samples.copyOf()
        val frames = frameCount
        val mono = FloatArray(frames)
        val weight = 1.0f / channelCount
        for (frame in 0 until frames) {
            var sum = 0.0f
            val base = frame * channelCount
            for (channel in 0 until channelCount) {
                sum += samples[base + channel]
            }
            mono[frame] = sum * weight
        }
        return mono
    }

    // FloatArray gives data classes reference equality for `samples`, which would silently
    // break value comparison in tests. Both are implemented over the array's contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmBuffer) return false
        return channelCount == other.channelCount &&
            sampleRateHz == other.sampleRateHz &&
            startFrame == other.startFrame &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + channelCount
        result = 31 * result + sampleRateHz
        result = 31 * result + startFrame.hashCode()
        return result
    }
}

/**
 * A fully decoded source, addressable by frame.
 *
 * The analyser needs random access over the whole asset — §17.1's progressive stages each
 * sweep it, and §17.5's framing indexes it directly — so this is the contract the analyser
 * takes. It says nothing about *how* the samples were produced, which is the point.
 */
public interface PcmSource {
    public val format: AudioFormatInfo

    /** Total frames available. */
    public val totalFrames: Long

    /**
     * Reads up to [frameCount] frames starting at [startFrame], as canonical mono (§17.3).
     *
     * Reads past the end return fewer frames rather than failing; §17.5's tail windows are
     * zero-padded, and that padding is the caller's business, not the source's.
     */
    public fun readMono(startFrame: Long, frameCount: Int): FloatArray
}
