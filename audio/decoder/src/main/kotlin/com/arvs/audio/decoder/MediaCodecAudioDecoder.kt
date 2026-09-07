package com.arvs.audio.decoder

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.Outcome
import com.arvs.core.model.PcmBuffer
import com.arvs.core.model.PcmSource
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.TimeSpan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The platform decoder for §15's compressed formats: MP3, M4A/AAC, FLAC and Ogg.
 *
 * **§7 compliance.** §7 permits `MediaCodec`-backed decoding of source assets into raw
 * buffers and permanently forbids Media3 from performing any compositing. This class decodes
 * to PCM and does nothing else — it never sees a layer, an effect or a frame. It uses the
 * platform `MediaExtractor`/`MediaCodec` directly rather than pulling in Media3 at all, which
 * is the same capability with one fewer dependency and no ambiguity about §7's boundary.
 *
 * **Why this is the only file here without JVM unit tests.** `MediaCodec` is a device API;
 * there is no JVM implementation and stubbing one would test the stub. Coverage for this
 * class is §77.1's device-matrix tier, and the plan's determinism note already establishes
 * that compressed-format tests assert tolerance and structural correctness, never
 * bit-equality — hardware decoders differ across vendors. The bit-exact guarantees live on
 * [WavDecoder]'s path instead.
 */
public class MediaCodecAudioDecoder(
    private val contentResolver: ContentResolver,
) : AudioDecoder {

    override val supportedFormats: Set<AudioContainerFormat> = setOf(
        AudioContainerFormat.MP3,
        AudioContainerFormat.M4A_AAC,
        AudioContainerFormat.FLAC,
        AudioContainerFormat.OGG,
        // WAV is listed so a device build can still open one through the platform if the
        // router is configured without WavDecoder. The router prefers WavDecoder when both
        // are registered, because only its output is bit-reproducible.
        AudioContainerFormat.WAV,
    )

    override suspend fun probe(source: DecodeSource): Outcome<AudioFormatInfo> = runCatching {
        withExtractor(source) { extractor, trackFormat ->
            trackFormat.toFormatInfo()
        }
    }.fold(
        onSuccess = { Outcome.success(it) },
        onFailure = { Outcome.Failure(it.toDecoderError(source.identity)) },
    )

    override fun decodeStream(source: DecodeSource, from: AudioSourceTime): Flow<PcmChunk> = flow {
        val uri = source.requireContentUri()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")!!.fileDescriptor)
            val trackIndex = extractor.selectAudioTrack()
            val inputFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            if (from.micros > 0) {
                extractor.seekTo(from.micros, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                // Rate and channel count are read from the *output* format, not the input.
                // Some decoders report them only after INFO_OUTPUT_FORMAT_CHANGED, and a few
                // legitimately change them; trusting the input format yields buffers labelled
                // with the wrong rate, which then silently misaligns every analysis frame.
                var outputFormat: MediaFormat? = null
                var frame = 0L
                var sawInputEos = false
                val info = MediaCodec.BufferInfo()

                while (true) {
                    currentCoroutineContext().ensureActive()

                    if (!sawInputEos) {
                        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            val read = extractor.readSampleData(buffer, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val format = outputFormat ?: codec.outputFormat.also { outputFormat = it }
                            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val samples = codec.getOutputBuffer(outputIndex)!!
                                .decodeToFloats(info, format)
                            codec.releaseOutputBuffer(outputIndex, false)

                            if (samples.isNotEmpty()) {
                                emit(
                                    PcmChunk(
                                        buffer = PcmBuffer(samples, channels, rate, frame),
                                        startTime = AudioSourceTime(frame * 1_000_000L / rate),
                                        isLast = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0,
                                    ),
                                )
                                frame += samples.size / channels
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return@flow
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    override suspend fun decodeAll(source: DecodeSource): Outcome<PcmSource> = runCatching {
        val format = withExtractor(source) { _, trackFormat -> trackFormat.toFormatInfo() }
        val collected = ArrayList<FloatArray>()
        var totalSamples = 0
        var channels = format.channelCount
        var rate = format.sampleRateHz

        decodeStream(source).collect { chunk ->
            collected += chunk.buffer.samples
            totalSamples += chunk.buffer.samples.size
            channels = chunk.buffer.channelCount
            rate = chunk.buffer.sampleRateHz
        }

        val merged = FloatArray(totalSamples)
        var offset = 0
        for (part in collected) {
            part.copyInto(merged, offset)
            offset += part.size
        }

        InMemoryPcmSource(
            format = AudioFormatInfo(
                sampleRateHz = rate,
                channelCount = channels,
                duration = TimeSpan(merged.size.toLong() / channels * 1_000_000L / rate),
                codecDescription = format.codecDescription,
            ),
            samples = merged,
            channelCount = channels,
        ) as PcmSource
    }.fold(
        onSuccess = { Outcome.success(it) },
        onFailure = { Outcome.Failure(it.toDecoderError(source.identity)) },
    )

    private inline fun <T> withExtractor(
        source: DecodeSource,
        block: (MediaExtractor, MediaFormat) -> T,
    ): T {
        val uri = source.requireContentUri()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(
                contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")!!.fileDescriptor,
            )
            val trackIndex = extractor.selectAudioTrack()
            return block(extractor, extractor.getTrackFormat(trackIndex))
        } finally {
            extractor.release()
        }
    }

    private fun DecodeSource.requireContentUri(): String = (this as? ContentUriSource)?.contentUri
        ?: throw UnsupportedAudioException(
            "'$identity' cannot be decoded by the platform codec: MediaExtractor requires a " +
                "content URI or file descriptor, which this source does not provide",
        )

    private fun MediaExtractor.selectAudioTrack(): Int {
        for (index in 0 until trackCount) {
            val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        throw UnsupportedAudioException("no audio track found")
    }

    private fun MediaFormat.toFormatInfo(): AudioFormatInfo = AudioFormatInfo(
        sampleRateHz = getInteger(MediaFormat.KEY_SAMPLE_RATE),
        channelCount = getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        duration = TimeSpan(if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION) else 0L),
        codecDescription = getString(MediaFormat.KEY_MIME),
    )

    /**
     * Converts a codec output buffer to normalised floats.
     *
     * The PCM encoding is read from the output format rather than assumed to be 16-bit:
     * `MediaCodec` may hand back `ENCODING_PCM_FLOAT` (notably for FLAC and high-resolution
     * sources), and reinterpreting float bytes as shorts produces loud noise rather than a
     * quiet failure.
     */
    private fun ByteBuffer.decodeToFloats(info: MediaCodec.BufferInfo, format: MediaFormat): FloatArray {
        position(info.offset)
        limit(info.offset + info.size)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            ENCODING_PCM_16BIT
        }
        val ordered = slice().order(ByteOrder.nativeOrder())

        return when (encoding) {
            ENCODING_PCM_FLOAT -> {
                val floats = ordered.asFloatBuffer()
                FloatArray(floats.remaining()).also { floats.get(it) }
            }
            ENCODING_PCM_8BIT -> FloatArray(ordered.remaining()) {
                ((ordered.get().toInt() and 0xFF) - 128) / 128.0f
            }
            else -> {
                val shorts = ordered.asShortBuffer()
                FloatArray(shorts.remaining()) { shorts.get() / 32_768.0f }
            }
        }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L

        // android.media.AudioFormat constants, named locally so the decode path reads as
        // arithmetic rather than as a lookup.
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_8BIT = 3
        const val ENCODING_PCM_FLOAT = 4
    }
}
