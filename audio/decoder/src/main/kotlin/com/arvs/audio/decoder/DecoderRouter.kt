package com.arvs.audio.decoder

import com.arvs.core.model.ArvsError
import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.ErrorCategory
import com.arvs.core.model.Outcome
import com.arvs.core.model.PcmSource
import com.arvs.core.time.AudioSourceTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Chooses a decoder for a source by sniffing its leading bytes.
 *
 * Routing is by **content**, not by file extension: a SAF document URI often has no extension,
 * and one that does may be wrong. §82 already establishes content as the basis of identity.
 *
 * Two registered decoders in Phase 1 — [WavDecoder] for the uncompressed path that CI can run,
 * and the platform decoder for §15's four compressed formats. The router is what lets the
 * determinism suite target WAV specifically while the app still opens everything §15 requires.
 */
public class DecoderRouter(
    private val decoders: List<AudioDecoder>,
) : AudioDecoder {

    override val supportedFormats: Set<AudioContainerFormat> =
        decoders.flatMap { it.supportedFormats }.toSet()

    override suspend fun probe(source: DecodeSource): Outcome<AudioFormatInfo> =
        when (val routed = route(source)) {
            is Routing.Failed -> Outcome.Failure(routed.error)
            is Routing.To -> routed.decoder.probe(source)
        }

    override fun decodeStream(source: DecodeSource, from: AudioSourceTime): Flow<PcmChunk> =
        when (val routed = route(source)) {
            // A Flow cannot report an Outcome, so a routing failure surfaces as the same
            // categorised error thrown into the stream. Silently emitting nothing would look
            // exactly like a valid empty file.
            is Routing.Failed -> flow { throw DecoderRoutingException(routed.error) }
            is Routing.To -> routed.decoder.decodeStream(source, from)
        }

    override suspend fun decodeAll(source: DecodeSource): Outcome<PcmSource> =
        when (val routed = route(source)) {
            is Routing.Failed -> Outcome.Failure(routed.error)
            is Routing.To -> routed.decoder.decodeAll(source)
        }

    /** The format detected for [source], or null when nothing recognised it. */
    public fun detectFormat(source: DecodeSource): AudioContainerFormat? =
        runCatching {
            source.openStream().use { stream ->
                val prefix = ByteArray(FormatSniffer.REQUIRED_PREFIX_BYTES)
                val read = stream.readAtMost(prefix, prefix.size)
                FormatSniffer.sniff(prefix.copyOf(read))
            }
        }.getOrNull()

    private fun route(source: DecodeSource): Routing {
        val format = detectFormat(source)
            ?: return Routing.Failed(
                ArvsError(
                    category = ErrorCategory.UNSUPPORTED_FORMAT,
                    message = "'${source.identity}' is not a recognised audio file " +
                        "(expected WAV, MP3, M4A/AAC, FLAC or Ogg)",
                    recoveryHint = "Convert the file to one of the supported formats and import it again",
                ),
            )

        val decoder = decoders.firstOrNull { format in it.supportedFormats }
            ?: return Routing.Failed(
                ArvsError(
                    category = ErrorCategory.UNSUPPORTED_FORMAT,
                    message = "'${source.identity}' is ${format.displayName}, which no configured " +
                        "decoder handles in this build",
                    recoveryHint = "Convert the file to WAV and import it again",
                ),
            )

        return Routing.To(decoder)
    }

    private sealed interface Routing {
        data class To(val decoder: AudioDecoder) : Routing
        data class Failed(val error: ArvsError) : Routing
    }

    public companion object {
        /** Router for environments with no platform codec — CI, and every JVM unit test. */
        public fun uncompressedOnly(): DecoderRouter = DecoderRouter(listOf(WavDecoder()))
    }
}

/** Carries a §97-categorised routing failure out of a [Flow], where an `Outcome` cannot go. */
public class DecoderRoutingException(public val error: ArvsError) : Exception(error.message)
