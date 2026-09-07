package com.arvs.audio.decoder

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Builds RIFF/WAVE byte arrays for tests, one byte at a time.
 *
 * Deliberately hand-built inside this module rather than taken from `testing:audio`'s
 * `WavWriter`. Two reasons, and the second is the important one:
 *
 *  1. §116.1's allowed-edge set does not include `audio:decoder → testing:audio`, and that
 *     edge is not mine to add (see obligation T-2 in the plan).
 *  2. A parser is best tested against bytes the test controls completely — including the
 *     malformed ones. `WavWriter` can only produce *valid* files, so it could never generate
 *     a truncated header, a data chunk before fmt, or an unsupported bit depth. Those are the
 *     cases §97 cares about.
 */
internal object WavBytes {

    const val FORMAT_PCM = 0x0001
    const val FORMAT_IEEE_FLOAT = 0x0003
    const val FORMAT_EXTENSIBLE = 0xFFFE

    /** A canonical 44-byte-header WAV holding [samples] as 16-bit PCM. */
    fun pcm16(samples: FloatArray, channels: Int = 1, rate: Int = 48_000): ByteArray {
        val payload = ByteArrayOutputStream()
        for (sample in samples) {
            val value = (sample.coerceIn(-1.0f, 1.0f) * 32_767.0f).roundToInt()
            payload.write(value and 0xFF)
            payload.write((value ushr 8) and 0xFF)
        }
        return build(FORMAT_PCM, channels, rate, bits = 16, payload = payload.toByteArray())
    }

    fun float32(samples: FloatArray, channels: Int = 1, rate: Int = 48_000): ByteArray {
        val payload = ByteArrayOutputStream()
        for (sample in samples) payload.writeIntLe(sample.toRawBits())
        return build(FORMAT_IEEE_FLOAT, channels, rate, bits = 32, payload = payload.toByteArray())
    }

    fun pcm8(unsignedSamples: IntArray, channels: Int = 1, rate: Int = 48_000): ByteArray =
        build(FORMAT_PCM, channels, rate, 8, ByteArray(unsignedSamples.size) { unsignedSamples[it].toByte() })

    fun pcm24(signedSamples: IntArray, channels: Int = 1, rate: Int = 48_000): ByteArray {
        val payload = ByteArrayOutputStream()
        for (value in signedSamples) {
            payload.write(value and 0xFF)
            payload.write((value shr 8) and 0xFF)
            payload.write((value shr 16) and 0xFF)
        }
        return build(FORMAT_PCM, channels, rate, 24, payload.toByteArray())
    }

    fun pcm32(signedSamples: IntArray, channels: Int = 1, rate: Int = 48_000): ByteArray {
        val payload = ByteArrayOutputStream()
        for (value in signedSamples) payload.writeIntLe(value)
        return build(FORMAT_PCM, channels, rate, 32, payload.toByteArray())
    }

    /** A WAV with extra chunks before `data`, as real files carry. */
    fun withLeadingChunks(samples: FloatArray, channels: Int = 1, rate: Int = 48_000): ByteArray {
        val payload = ByteArrayOutputStream()
        for (sample in samples) {
            val value = (sample.coerceIn(-1.0f, 1.0f) * 32_767.0f).roundToInt()
            payload.write(value and 0xFF)
            payload.write((value ushr 8) and 0xFF)
        }
        val extra = ByteArrayOutputStream().apply {
            writeAscii("LIST")
            writeIntLe(10)
            writeAscii("INFOhello ")     // 10 bytes, even
            writeAscii("fact")
            writeIntLe(4)
            writeIntLe(samples.size)
        }
        return build(FORMAT_PCM, channels, rate, 16, payload.toByteArray(), betweenFmtAndData = extra.toByteArray())
    }

    /** A WAV whose `data` chunk precedes `fmt ` — structurally invalid. */
    fun dataBeforeFmt(): ByteArray {
        val body = ByteArrayOutputStream().apply {
            writeAscii("data"); writeIntLe(4); writeIntLe(0)
            writeAscii("fmt "); writeIntLe(16)
            writeShortLe(FORMAT_PCM); writeShortLe(1); writeIntLe(48_000)
            writeIntLe(96_000); writeShortLe(2); writeShortLe(16)
        }
        return riff(body.toByteArray())
    }

    /** A file that stops partway through its header. */
    fun truncatedHeader(bytes: Int): ByteArray = pcm16(FloatArray(10)).copyOf(bytes)

    /** A structurally valid header whose payload is shorter than `data` claims. */
    fun truncatedPayload(declaredFrames: Int, actualFrames: Int): ByteArray {
        val payload = ByteArray(actualFrames * 2)
        return build(FORMAT_PCM, 1, 48_000, 16, payload, declaredDataSize = declaredFrames * 2)
    }

    /** A valid RIFF/WAVE with an encoding this project does not support. */
    fun unsupportedEncoding(formatTag: Int = 0x0011, bits: Int = 4): ByteArray =
        build(formatTag, 1, 48_000, bits, ByteArray(16))

    fun notRiff(): ByteArray = "NOTAFILEATALL".toByteArray(Charsets.US_ASCII)

    fun riffButNotWave(): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("RIFF"); writeIntLe(4); writeAscii("AVI ")
    }.toByteArray()

    private fun build(
        formatTag: Int,
        channels: Int,
        rate: Int,
        bits: Int,
        payload: ByteArray,
        betweenFmtAndData: ByteArray = ByteArray(0),
        declaredDataSize: Int = payload.size,
    ): ByteArray {
        val blockAlign = channels * (bits / 8)
        val body = ByteArrayOutputStream().apply {
            writeAscii("fmt ")
            writeIntLe(16)
            writeShortLe(formatTag)
            writeShortLe(channels)
            writeIntLe(rate)
            writeIntLe(rate * blockAlign)
            writeShortLe(blockAlign)
            writeShortLe(bits)
            write(betweenFmtAndData)
            writeAscii("data")
            writeIntLe(declaredDataSize)
            write(payload)
        }
        return riff(body.toByteArray())
    }

    private fun riff(body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("RIFF")
        writeIntLe(4 + body.size)
        writeAscii("WAVE")
        write(body)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeAscii(text: String) { for (c in text) write(c.code) }
    private fun ByteArrayOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }
    private fun ByteArrayOutputStream.writeShortLe(v: Int) { write(v and 0xFF); write((v ushr 8) and 0xFF) }
}
