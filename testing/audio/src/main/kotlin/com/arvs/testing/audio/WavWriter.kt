package com.arvs.testing.audio

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Writes a §119 fixture as canonical RIFF/WAVE bytes.
 *
 * Fixtures exist as `FloatArray`s; a decoder test needs *a file*. This produces one in
 * memory, with no third-party audio and no committed binary (§115).
 *
 * The writer is deliberately independent of `audio:decoder`'s WAV reader — they share no
 * constants and neither imports the other. That is worth the small duplication of four magic
 * strings: an encoder and a decoder written from the specification separately, agreeing, is
 * evidence about the format. Sharing one constants file would only prove they agree with
 * themselves.
 */
public object WavWriter {

    /** Sample encodings this writer emits. */
    public enum class Encoding { PCM_16, FLOAT_32 }

    public fun write(fixture: AudioFixture, encoding: Encoding = Encoding.PCM_16): ByteArray =
        write(fixture.samples, fixture.channelCount, fixture.sampleRateHz, encoding)

    public fun write(
        samples: FloatArray,
        channelCount: Int,
        sampleRateHz: Int,
        encoding: Encoding = Encoding.PCM_16,
    ): ByteArray {
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }

        val bitsPerSample = when (encoding) {
            Encoding.PCM_16 -> 16
            Encoding.FLOAT_32 -> 32
        }
        val formatTag = when (encoding) {
            Encoding.PCM_16 -> WAVE_FORMAT_PCM
            Encoding.FLOAT_32 -> WAVE_FORMAT_IEEE_FLOAT
        }
        val bytesPerSample = bitsPerSample / 8
        val blockAlign = channelCount * bytesPerSample
        val dataSize = samples.size * bytesPerSample

        val out = ByteArrayOutputStream(44 + dataSize)
        out.writeAscii("RIFF")
        out.writeIntLe(36 + dataSize)          // chunk size = file size - 8
        out.writeAscii("WAVE")

        out.writeAscii("fmt ")
        out.writeIntLe(16)                     // PCM/float fmt chunk size
        out.writeShortLe(formatTag)
        out.writeShortLe(channelCount)
        out.writeIntLe(sampleRateHz)
        out.writeIntLe(sampleRateHz * blockAlign)  // byte rate
        out.writeShortLe(blockAlign)
        out.writeShortLe(bitsPerSample)

        out.writeAscii("data")
        out.writeIntLe(dataSize)
        when (encoding) {
            // 32767, not 32768: scaling by 32768 makes -1.0 encode to -32768 (fine) and
            // +1.0 to +32768, which overflows a signed 16-bit sample and wraps to the most
            // negative value. A full-scale positive peak would come back as a full-scale
            // negative one — silent, and catastrophic for the clipping fixture.
            Encoding.PCM_16 -> for (sample in samples) {
                out.writeShortLe((sample.coerceIn(-1.0f, 1.0f) * 32_767.0f).roundToInt())
            }
            Encoding.FLOAT_32 -> for (sample in samples) {
                out.writeIntLe(sample.toRawBits())
            }
        }
        return out.toByteArray()
    }

    private const val WAVE_FORMAT_PCM = 0x0001
    private const val WAVE_FORMAT_IEEE_FLOAT = 0x0003

    private fun ByteArrayOutputStream.writeAscii(text: String) {
        for (character in text) write(character.code)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }
}
