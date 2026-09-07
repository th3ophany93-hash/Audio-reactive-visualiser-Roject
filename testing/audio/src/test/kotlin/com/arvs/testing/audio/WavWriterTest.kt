package com.arvs.testing.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the RIFF/WAVE bytes the fixture writer emits.
 *
 * Checked against the format specification by hand-reading the header, not by feeding the
 * output back through a reader of our own — a writer and reader that share a bug agree
 * perfectly.
 */
class WavWriterTest {

    private fun ascii(bytes: ByteArray, offset: Int, length: Int) =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun intLe(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `writes a canonical 44-byte PCM header`() {
        val wav = WavWriter.write(FloatArray(100), channelCount = 2, sampleRateHz = 48_000)

        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, intLe(wav, 16))            // fmt chunk size
        assertEquals(1, shortLe(wav, 20))           // WAVE_FORMAT_PCM
        assertEquals(2, shortLe(wav, 22))           // channels
        assertEquals(48_000, intLe(wav, 24))        // sample rate
        assertEquals(48_000 * 4, intLe(wav, 28))    // byte rate = rate * blockAlign
        assertEquals(4, shortLe(wav, 32))           // blockAlign = channels * bytesPerSample
        assertEquals(16, shortLe(wav, 34))          // bits per sample
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(200, intLe(wav, 40))           // 100 samples * 2 bytes
        assertEquals(44 + 200, wav.size)
        assertEquals(wav.size - 8, intLe(wav, 4))   // RIFF size
    }

    @Test
    fun `full-scale positive samples do not overflow to negative`() {
        // Scaling by 32768 would send +1.0 to +32768, which wraps to -32768 in a signed
        // 16-bit sample: a full-scale positive peak comes back full-scale negative. Silent,
        // and it would corrupt the clipping fixture specifically.
        val wav = WavWriter.write(floatArrayOf(1.0f, -1.0f), channelCount = 1, sampleRateHz = 48_000)

        assertEquals(32_767, shortLe(wav, 44).toShort().toInt())
        assertEquals(-32_767, shortLe(wav, 46).toShort().toInt())
    }

    @Test
    fun `out-of-range samples are clamped, not wrapped`() {
        val wav = WavWriter.write(floatArrayOf(4.0f, -4.0f), channelCount = 1, sampleRateHz = 48_000)
        assertEquals(32_767, shortLe(wav, 44).toShort().toInt())
        assertEquals(-32_767, shortLe(wav, 46).toShort().toInt())
    }

    @Test
    fun `float encoding declares format tag 3 and 32 bits`() {
        val wav = WavWriter.write(
            floatArrayOf(0.25f), channelCount = 1, sampleRateHz = 48_000,
            encoding = WavWriter.Encoding.FLOAT_32,
        )

        assertEquals(3, shortLe(wav, 20))           // WAVE_FORMAT_IEEE_FLOAT
        assertEquals(32, shortLe(wav, 34))
        assertEquals(0.25f, Float.fromBits(intLe(wav, 44)))
    }

    @Test
    fun `float encoding is lossless where 16-bit is not`() {
        // The reason both encodings exist: golden vectors need a path with no quantisation.
        val tiny = floatArrayOf(1.0e-6f)
        val asFloat = WavWriter.write(tiny, 1, 48_000, WavWriter.Encoding.FLOAT_32)
        val asPcm16 = WavWriter.write(tiny, 1, 48_000, WavWriter.Encoding.PCM_16)

        assertEquals(1.0e-6f, Float.fromBits(intLe(asFloat, 44)))
        assertEquals(0, shortLe(asPcm16, 44))       // quantised away entirely
    }

    @Test
    fun `a fixture round-trips its declared format`() {
        val fixture = Fixtures.stereo(480)
        val wav = WavWriter.write(fixture)

        assertEquals(2, shortLe(wav, 22))
        assertEquals(48_000, intLe(wav, 24))
        assertEquals(fixture.samples.size * 2, intLe(wav, 40))
    }

    @Test
    fun `writing is deterministic`() {
        val fixture = Fixtures.whiteNoise(480)
        assertTrue(WavWriter.write(fixture).contentEquals(WavWriter.write(fixture)))
    }

    @Test
    fun `different fixtures produce different bytes`() {
        assertNotEquals(
            WavWriter.write(Fixtures.sine440(480)).toList(),
            WavWriter.write(Fixtures.sine1000(480)).toList(),
        )
    }
}
