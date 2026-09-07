package com.arvs.audio.decoder

import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.Outcome
import com.arvs.core.model.PcmBuffer
import com.arvs.core.model.PcmSource
import com.arvs.core.time.AudioSourceTime
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * §15's headless audio decoder.
 *
 * §15 requires `AudioDecoder`, `AudioPlayback`, `AudioAnalyzer`, `AudioAnalysisCache` and
 * `AudioExport` to be **independent components**, and §116.1 enforces it: `audio:analysis`
 * has no edge to this module, because the analyser consumes PCM rather than a codec. So
 * nothing here knows about playback, and nothing here knows about analysis.
 *
 * §7 bounds what a decoder may be: Media3/`MediaCodec`-backed decoding into raw buffers is
 * explicitly permitted, and compositing through Media3 is permanently forbidden. Decoding is
 * all this module does.
 *
 * Two access modes over one contract, as the plan requires:
 *  - [decodeStream] for playback — incremental, seekable, backpressured.
 *  - [decodeAll] for analysis — random-access over the whole asset, which §17.1's staged
 *    sweeps and §17.5's framing both need.
 */
public interface AudioDecoder {

    /** Formats this decoder can handle. Used to route a source to the right implementation. */
    public val supportedFormats: Set<AudioContainerFormat>

    /**
     * Reads format metadata without decoding the payload.
     *
     * §17.3 requires the source's own channel count, layout and sample rate to be preserved:
     * this reports what the file says, never the canonicalised analysis view of it.
     */
    public suspend fun probe(source: DecodeSource): Outcome<AudioFormatInfo>

    /**
     * Streams PCM from [from] onward, for playback (§16).
     *
     * Chunked so that playback can start before the file has been read, and so a long asset
     * never has to be resident in memory.
     */
    public fun decodeStream(source: DecodeSource, from: AudioSourceTime = AudioSourceTime.EPOCH): Flow<PcmChunk>

    /**
     * Decodes the whole asset into a random-access [PcmSource], for analysis (§17).
     *
     * Separate from [decodeStream] because the two have genuinely different shapes, not
     * because analysis is playback with the pause removed: the analyser sweeps the asset
     * repeatedly and out of order.
     */
    public suspend fun decodeAll(source: DecodeSource): Outcome<PcmSource>
}

/**
 * Something decodable: a byte stream that can be reopened.
 *
 * Reopenable rather than a single [InputStream], because [AudioDecoder.decodeAll] and
 * [AudioDecoder.decodeStream] may each need their own pass, and because a seek to a point
 * before the current position is otherwise impossible for a non-seekable stream.
 */
public interface DecodeSource {
    /** Stable description used in diagnostics and §97 error messages. Never for identity. */
    public val identity: String

    /** Opens a fresh stream positioned at the first byte. The caller closes it. */
    public fun openStream(): InputStream

    /** Total byte length if known; null when the provider does not report one. */
    public val sizeBytes: Long? get() = null
}

/**
 * One decoded block, tagged with where it starts (§9.1's audio-source domain).
 *
 * [startTime] is an [AudioSourceTime] and not a [com.arvs.core.time.TimelineTime]: decoding
 * knows nothing of trimming, and §9.1 fixes the epoch at `t = 0` of the raw asset. The type
 * makes that non-negotiable rather than merely documented.
 */
public data class PcmChunk(
    public val buffer: PcmBuffer,
    public val startTime: AudioSourceTime,
    /** True for the final chunk of the stream. */
    public val isLast: Boolean = false,
) {
    public val frameCount: Int get() = buffer.frameCount
}
