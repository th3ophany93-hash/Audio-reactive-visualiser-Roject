package com.arvs.audio.decoder

import com.arvs.core.model.ArvsError
import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.ErrorCategory
import com.arvs.core.model.Outcome
import com.arvs.core.model.PcmBuffer
import com.arvs.core.model.PcmSource
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.TimeSpan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * A pure-Kotlin RIFF/WAVE decoder.
 *
 * **Why this exists alongside the platform decoder.** The plan's determinism note is explicit:
 * hardware decoders are not bit-exact across devices and codec vendors, so golden analysis
 * vectors are cut only from uncompressed fixtures. CI has no `MediaCodec`. Without a PCM path
 * that runs on a plain JVM, the release-blocking CI tier of §77.1 could not run the
 * determinism suite at all — every §9.1 guarantee would be asserted only on a device, which
 * is precisely the "permanently-flaky suite" §123 warns about. So this is infrastructure for
 * the determinism contract, not a shortcut around the platform.
 *
 * Handles WAVE_FORMAT_PCM (8/16/24/32-bit integer) and WAVE_FORMAT_IEEE_FLOAT (32-bit), which
 * covers everything a fixture or a user-supplied WAV realistically carries. Anything else is
 * reported as `UNSUPPORTED_FORMAT` naming what was found — §97 forbids a generic failure.
 *
 * Chunk parsing walks the RIFF structure rather than assuming a 44-byte header. Real files
 * carry `LIST`, `fact` and metadata chunks before `data`, and a decoder that assumes the
 * canonical layout reads metadata as audio and produces noise.
 */
public class WavDecoder : AudioDecoder {

    override val supportedFormats: Set<AudioContainerFormat> = setOf(AudioContainerFormat.WAV)

    override suspend fun probe(source: DecodeSource): Outcome<AudioFormatInfo> =
        runCatching { source.openStream().use { readHeader(it, source.identity) } }
            .fold(
                onSuccess = { header -> Outcome.success(header.toFormatInfo()) },
                onFailure = { failure -> Outcome.Failure(failure.toDecoderError(source.identity)) },
            )

    override fun decodeStream(source: DecodeSource, from: AudioSourceTime): Flow<PcmChunk> = flow {
        source.openStream().use { stream ->
            val header = readHeader(stream, source.identity)
            val bytesPerFrame = header.blockAlign
            val skipFrames = (from.micros.coerceAtLeast(0) * header.sampleRateHz / 1_000_000L)
                .coerceAtMost(header.frameCount)
            stream.skipFullyOrThrow(skipFrames * bytesPerFrame)

            var frame = skipFrames
            val framesPerChunk = STREAM_CHUNK_FRAMES
            val chunkBytes = ByteArray(framesPerChunk * bytesPerFrame)

            while (frame < header.frameCount) {
                currentCoroutineContext().ensureActive()
                val framesWanted = minOf(framesPerChunk.toLong(), header.frameCount - frame).toInt()
                val bytesWanted = framesWanted * bytesPerFrame
                val read = stream.readAtMost(chunkBytes, bytesWanted)
                if (read <= 0) break

                val framesRead = read / bytesPerFrame
                if (framesRead == 0) break

                emit(
                    PcmChunk(
                        buffer = PcmBuffer(
                            samples = header.decodeSamples(chunkBytes, framesRead),
                            channelCount = header.channelCount,
                            sampleRateHz = header.sampleRateHz,
                            startFrame = frame,
                        ),
                        startTime = AudioSourceTime(frame * 1_000_000L / header.sampleRateHz),
                        isLast = frame + framesRead >= header.frameCount,
                    ),
                )
                frame += framesRead
            }
        }
    }

    override suspend fun decodeAll(source: DecodeSource): Outcome<PcmSource> =
        runCatching {
            source.openStream().use { stream ->
                val header = readHeader(stream, source.identity)
                val payload = ByteArray(header.dataSizeBytes.toInt())
                val read = stream.readAtMost(payload, payload.size)
                val framesRead = read / header.blockAlign
                InMemoryPcmSource(
                    format = header.toFormatInfo(),
                    samples = header.decodeSamples(payload, framesRead),
                    channelCount = header.channelCount,
                )
            }
        }.fold(
            onSuccess = { Outcome.success(it) },
            onFailure = { failure -> Outcome.Failure(failure.toDecoderError(source.identity)) },
        )

    private companion object {
        const val STREAM_CHUNK_FRAMES = 4_096
    }
}

/** A fully decoded asset held in memory, as the analyser consumes it. */
internal class InMemoryPcmSource(
    override val format: AudioFormatInfo,
    private val samples: FloatArray,
    private val channelCount: Int,
) : PcmSource {

    override val totalFrames: Long = (samples.size / channelCount).toLong()

    override fun readMono(startFrame: Long, frameCount: Int): FloatArray {
        if (startFrame >= totalFrames || frameCount <= 0) return FloatArray(0)
        val available = minOf(frameCount.toLong(), totalFrames - startFrame).toInt()
        val out = FloatArray(available)
        val weight = 1.0f / channelCount
        for (index in 0 until available) {
            val base = ((startFrame + index) * channelCount).toInt()
            var sum = 0.0f
            for (channel in 0 until channelCount) sum += samples[base + channel]
            out[index] = sum * weight
        }
        return out
    }
}

/** The parsed `fmt ` chunk plus the located `data` chunk size. */
internal data class WavHeader(
    val formatTag: Int,
    val channelCount: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val dataSizeBytes: Long,
    val identity: String,
) {
    val blockAlign: Int get() = channelCount * (bitsPerSample / 8)
    val frameCount: Long get() = if (blockAlign == 0) 0 else dataSizeBytes / blockAlign

    fun toFormatInfo(): AudioFormatInfo = AudioFormatInfo(
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        duration = TimeSpan(frameCount * 1_000_000L / sampleRateHz),
        codecDescription = "WAV/${if (formatTag == WAVE_FORMAT_IEEE_FLOAT) "float" else "pcm"}$bitsPerSample",
    )

    /** Converts [framesRead] frames of raw little-endian payload to normalised floats. */
    fun decodeSamples(payload: ByteArray, framesRead: Int): FloatArray {
        val sampleCount = framesRead * channelCount
        val out = FloatArray(sampleCount)
        when {
            formatTag == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32 ->
                for (i in 0 until sampleCount) out[i] = Float.fromBits(payload.intLe(i * 4))

            bitsPerSample == 8 ->
                // 8-bit WAV is unsigned with a 128 bias — the one integer depth that is not
                // two's complement. Treating it as signed inverts the waveform.
                for (i in 0 until sampleCount) out[i] = ((payload[i].toInt() and 0xFF) - 128) / 128.0f

            bitsPerSample == 16 ->
                for (i in 0 until sampleCount) out[i] = payload.shortLe(i * 2) / 32_768.0f

            bitsPerSample == 24 ->
                for (i in 0 until sampleCount) out[i] = payload.int24Le(i * 3) / 8_388_608.0f

            bitsPerSample == 32 ->
                for (i in 0 until sampleCount) out[i] = payload.intLe(i * 4) / 2_147_483_648.0f

            else -> error("unreachable: header validation rejects other depths")
        }
        return out
    }

    companion object {
        const val WAVE_FORMAT_PCM = 0x0001
        const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
        const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    }
}

/**
 * Walks the RIFF structure to the `fmt ` and `data` chunks.
 *
 * Leaves the stream positioned at the first payload byte, so the caller reads audio next.
 */
internal fun readHeader(stream: InputStream, identity: String): WavHeader {
    val input = DataInputStream(stream)

    val riff = input.readAsciiOrThrow(4, identity)
    if (riff != "RIFF") {
        throw UnsupportedAudioException("'$identity' is not a RIFF file (found '$riff')")
    }
    input.readIntLeOrThrow(identity) // RIFF size — not trusted; chunk walking is authoritative
    val wave = input.readAsciiOrThrow(4, identity)
    if (wave != "WAVE") {
        throw UnsupportedAudioException("'$identity' is a RIFF container but not WAVE (found '$wave')")
    }

    var formatTag = -1
    var channels = 0
    var rate = 0
    var bits = 0

    while (true) {
        val chunkId = input.readAsciiOrThrow(4, identity)
        val chunkSize = input.readIntLeOrThrow(identity).toLong() and 0xFFFF_FFFFL

        when (chunkId) {
            "fmt " -> {
                if (chunkSize < 16) {
                    throw CorruptAudioException("'$identity' has a truncated fmt chunk ($chunkSize bytes)")
                }
                formatTag = input.readShortLeOrThrow(identity)
                channels = input.readShortLeOrThrow(identity)
                rate = input.readIntLeOrThrow(identity)
                input.readIntLeOrThrow(identity)   // byte rate — derivable, not trusted
                input.readShortLeOrThrow(identity) // block align — likewise
                bits = input.readShortLeOrThrow(identity)
                if (formatTag == WavHeader.WAVE_FORMAT_EXTENSIBLE && chunkSize >= 40) {
                    // WAVE_FORMAT_EXTENSIBLE moves the real tag into the first two bytes of
                    // the SubFormat GUID, 24 bytes into the extension.
                    input.skipFullyOrThrow(8)
                    formatTag = input.readShortLeOrThrow(identity)
                    input.skipFullyOrThrow(chunkSize - 40)
                } else {
                    input.skipFullyOrThrow(chunkSize - 16)
                }
            }

            "data" -> {
                if (formatTag == -1) {
                    throw CorruptAudioException("'$identity' has a data chunk before its fmt chunk")
                }
                validate(formatTag, channels, rate, bits, identity)
                return WavHeader(formatTag, channels, rate, bits, chunkSize, identity)
            }

            // LIST, fact, id3, cue and friends. Skipping them is the whole reason this walks
            // the structure instead of assuming a 44-byte header.
            else -> input.skipFullyOrThrow(chunkSize + (chunkSize and 1L)) // chunks are word-aligned
        }
    }
}

private fun validate(formatTag: Int, channels: Int, rate: Int, bits: Int, identity: String) {
    if (channels <= 0) throw CorruptAudioException("'$identity' declares $channels channels")
    if (rate <= 0) throw CorruptAudioException("'$identity' declares a sample rate of $rate Hz")

    val supported = when (formatTag) {
        WavHeader.WAVE_FORMAT_PCM -> bits == 8 || bits == 16 || bits == 24 || bits == 32
        WavHeader.WAVE_FORMAT_IEEE_FLOAT -> bits == 32
        else -> false
    }
    if (!supported) {
        throw UnsupportedAudioException(
            "'$identity' uses WAVE format tag 0x${formatTag.toString(16)} at $bits-bit, which is " +
                "not supported (expected PCM 8/16/24/32-bit or IEEE float 32-bit)",
        )
    }
}

/** The file is structurally valid but encoded in a way this project does not support (§97). */
internal class UnsupportedAudioException(message: String) : Exception(message)

/** The file is malformed or truncated (§97). */
internal class CorruptAudioException(message: String) : Exception(message)

internal fun Throwable.toDecoderError(identity: String): ArvsError = when (this) {
    is UnsupportedAudioException -> ArvsError(
        category = ErrorCategory.UNSUPPORTED_FORMAT,
        message = message ?: "'$identity' is in an unsupported format",
        recoveryHint = "Convert the file to WAV, MP3, M4A/AAC, FLAC or Ogg and import it again",
        cause = this,
    )

    is CorruptAudioException, is EOFException -> ArvsError(
        category = ErrorCategory.DECODER_ERROR,
        message = message?.let { "$it" } ?: "'$identity' ended unexpectedly while decoding",
        recoveryHint = "The file appears incomplete or damaged; try re-exporting it from its source",
        cause = this,
    )

    is OutOfMemoryError -> ArvsError(
        category = ErrorCategory.OUT_OF_MEMORY,
        message = "Ran out of memory decoding '$identity'",
        recoveryHint = "Close other apps, or use a shorter or lower-rate file",
        cause = this,
    )

    else -> ArvsError(
        category = ErrorCategory.DECODER_ERROR,
        message = "Could not decode '$identity': ${message ?: this::class.java.simpleName}",
        recoveryHint = "Check that the file is complete and readable",
        cause = this,
    )
}

// --- little-endian readers ------------------------------------------------------------------

internal fun ByteArray.intLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.shortLe(offset: Int): Int =
    (((this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)).toShort()).toInt()

internal fun ByteArray.int24Le(offset: Int): Int {
    val raw = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)
    // Sign-extend from 24 to 32 bits; without this every negative sample reads as a large
    // positive one and the waveform is destroyed rather than merely wrong.
    return if (raw and 0x80_0000 != 0) raw or -0x100_0000 else raw
}

private fun InputStream.readAsciiOrThrow(length: Int, identity: String): String {
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = read(bytes, read, length - read)
        if (n < 0) throw CorruptAudioException("'$identity' ended inside its header")
        read += n
    }
    return String(bytes, Charsets.US_ASCII)
}

private fun InputStream.readIntLeOrThrow(identity: String): Int {
    val bytes = ByteArray(4)
    var read = 0
    while (read < 4) {
        val n = read(bytes, read, 4 - read)
        if (n < 0) throw CorruptAudioException("'$identity' ended inside its header")
        read += n
    }
    return bytes.intLe(0)
}

private fun InputStream.readShortLeOrThrow(identity: String): Int {
    val bytes = ByteArray(2)
    var read = 0
    while (read < 2) {
        val n = read(bytes, read, 2 - read)
        if (n < 0) throw CorruptAudioException("'$identity' ended inside its header")
        read += n
    }
    return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
}

internal fun InputStream.skipFullyOrThrow(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) {
            // skip() may legitimately return 0; fall back to reading before giving up.
            if (read() < 0) return
            remaining--
        } else {
            remaining -= skipped
        }
    }
}

/** Reads up to [count] bytes, tolerating short reads. Returns the number actually read. */
internal fun InputStream.readAtMost(into: ByteArray, count: Int): Int {
    var read = 0
    while (read < count) {
        val n = read(into, read, count - read)
        if (n < 0) break
        read += n
    }
    return read
}
