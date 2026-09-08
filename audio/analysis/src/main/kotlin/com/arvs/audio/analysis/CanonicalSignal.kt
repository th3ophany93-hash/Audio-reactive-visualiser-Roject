package com.arvs.audio.analysis

import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.PcmBuffer
import com.arvs.core.model.PcmSource
import com.arvs.core.time.AnalysisFraming

/**
 * §17.3's canonical analysis signal: mono, 48 kHz, derived once and reused by every stage.
 *
 * §17.3 is emphatic that this is "an analysis-only derivation: it never replaces, alters, or is
 * substituted for the source audio", and that "playback (§14.1, §16) and any future export
 * (§136) use the original source audio, never the canonical mono signal." That separation is
 * why this type exists at all rather than the pipeline mutating a [PcmSource] in place: the
 * canonical signal is a *different object* from the source, and [sourceFormat] keeps the
 * original's rate and channel count alongside it, as §17.3 requires.
 *
 * The order is fixed and matters: **downmix first, then resample.** Resampling two channels
 * separately and averaging afterwards costs twice the filter work for an identical result,
 * because both operations are linear and downmixing is the cheaper one to do first.
 */
public class CanonicalSignal private constructor(
    /** Mono samples at [AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ]. */
    public val samples: FloatArray,
    /** The source's own properties, preserved unchanged (§17.3). */
    public val sourceFormat: AudioFormatInfo,
) {
    public val sampleRateHz: Int get() = AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ

    public val frameCount: Int get() = samples.size

    /** Whether the source needed resampling. False means §17.3's bit-exact pass-through held. */
    public val wasResampled: Boolean
        get() = sourceFormat.sampleRateHz != AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ

    public companion object {
        /** Derives the canonical signal from an already-decoded buffer. */
        public fun from(buffer: PcmBuffer, sourceFormat: AudioFormatInfo): CanonicalSignal {
            val mono = buffer.toCanonicalMono()
            return CanonicalSignal(
                samples = Resampler.resample(
                    mono,
                    buffer.sampleRateHz,
                    AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ,
                ),
                sourceFormat = sourceFormat,
            )
        }

        /**
         * Derives the canonical signal from a decoded [PcmSource].
         *
         * `readMono` already applies §17.3's downmix, so only the rate conversion remains.
         */
        public fun from(source: PcmSource): CanonicalSignal {
            val mono = source.readMono(0, source.totalFrames.toInt())
            return CanonicalSignal(
                samples = Resampler.resample(
                    mono,
                    source.format.sampleRateHz,
                    AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ,
                ),
                sourceFormat = source.format,
            )
        }

        /** For fixtures and tests: samples already mono and already at the canonical rate. */
        public fun ofCanonicalMono(samples: FloatArray, sourceFormat: AudioFormatInfo): CanonicalSignal {
            require(sourceFormat.sampleRateHz == AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ) {
                "ofCanonicalMono requires a source already at " +
                    "${AnalysisFraming.CANONICAL_SAMPLE_RATE_HZ} Hz, got ${sourceFormat.sampleRateHz}"
            }
            return CanonicalSignal(samples, sourceFormat)
        }
    }
}
