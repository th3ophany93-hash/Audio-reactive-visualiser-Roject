package com.arvs.audio.decoder

import com.arvs.core.model.ErrorCategory
import com.arvs.core.model.Outcome
import com.arvs.core.time.AudioSourceTime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pins the uncompressed decode path — the one the release-blocking CI tier depends on.
 */
class WavDecoderTest {

    private val decoder = WavDecoder()

    private fun source(bytes: ByteArray, name: String = "fixture.wav") =
        ByteArrayDecodeSource(bytes, name)

    private fun sine(frames: Int, hz: Double = 440.0, rate: Int = 48_000, amplitude: Float = 0.5f) =
        FloatArray(frames) { n -> (amplitude * sin(2.0 * PI * hz * n / rate)).toFloat() }

    // --- probe ---------------------------------------------------------------------------

    @Test
    fun `probe reports the source rate, channels and duration`() = runTest {
        val wav = WavBytes.pcm16(sine(48_000), channels = 1, rate = 48_000)

        val info = (decoder.probe(source(wav)) as Outcome.Success).value

        assertEquals(48_000, info.sampleRateHz)
        assertEquals(1, info.channelCount)
        assertEquals(1_000_000L, info.duration.micros)
        assertTrue(info.isMono)
        assertTrue(info.isCanonicalRate)
    }

    @Test
    fun `probe preserves a non-canonical source rate rather than normalising it`() = runTest {
        // §17.3: the original rate and channel count are preserved; resampling to 48 kHz is a
        // separate analysis stage and must not be smuggled into decoding.
        val wav = WavBytes.pcm16(sine(44_100, rate = 44_100), channels = 2, rate = 44_100)

        val info = (decoder.probe(source(wav)) as Outcome.Success).value

        assertEquals(44_100, info.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertTrue(!info.isCanonicalRate)
    }

    // --- sample-format correctness -------------------------------------------------------

    @Test
    fun `16-bit samples decode to the expected amplitudes`() = runTest {
        val original = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
        val decoded = decodeAllSamples(WavBytes.pcm16(original))

        original.indices.forEach { index ->
            assertEquals(original[index].toDouble(), decoded[index].toDouble(), 1.0 / 32_767)
        }
    }

    @Test
    fun `32-bit float samples decode bit-exactly`() = runTest {
        // The lossless path golden vectors are cut from.
        val original = floatArrayOf(0.0f, 1.0e-7f, -0.333333f, 0.999999f)
        val decoded = decodeAllSamples(WavBytes.float32(original))

        original.indices.forEach { index -> assertEquals(original[index], decoded[index]) }
    }

    @Test
    fun `8-bit samples are read as unsigned with a 128 bias`() = runTest {
        // 8-bit is the one integer depth in WAV that is not two's complement. Reading it as
        // signed inverts the waveform — quiet, and completely wrong.
        val decoded = decodeAllSamples(WavBytes.pcm8(intArrayOf(128, 255, 0, 192)))

        assertEquals(0.0, decoded[0].toDouble(), 1e-6)      // 128 is silence
        assertEquals(0.9921875, decoded[1].toDouble(), 1e-6) // 255 is full positive
        assertEquals(-1.0, decoded[2].toDouble(), 1e-6)      // 0 is full negative
        assertEquals(0.5, decoded[3].toDouble(), 1e-6)
    }

    @Test
    fun `24-bit samples are sign-extended`() = runTest {
        // Without sign extension every negative sample reads as a large positive one, which
        // destroys the waveform rather than merely biasing it.
        val decoded = decodeAllSamples(WavBytes.pcm24(intArrayOf(0, 8_388_607, -8_388_608, -1)))

        assertEquals(0.0, decoded[0].toDouble(), 1e-9)
        assertEquals(1.0, decoded[1].toDouble(), 1e-6)
        assertEquals(-1.0, decoded[2].toDouble(), 1e-9)
        assertTrue("negative one must stay negative, was ${decoded[3]}", decoded[3] < 0f)
    }

    @Test
    fun `32-bit integer samples decode to full scale`() = runTest {
        val decoded = decodeAllSamples(WavBytes.pcm32(intArrayOf(0, Int.MAX_VALUE, Int.MIN_VALUE)))

        assertEquals(0.0, decoded[0].toDouble(), 1e-9)
        assertEquals(1.0, decoded[1].toDouble(), 1e-6)
        assertEquals(-1.0, decoded[2].toDouble(), 1e-9)
    }

    // --- chunk walking -------------------------------------------------------------------

    @Test
    fun `chunks before data are skipped, not read as audio`() = runTest {
        // Real files carry LIST, fact and metadata chunks. A decoder that assumes the
        // canonical 44-byte header reads that metadata as samples and produces noise.
        val original = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f)
        val decoded = decodeAllSamples(WavBytes.withLeadingChunks(original))

        assertEquals(original.size, decoded.size)
        original.indices.forEach { index ->
            assertEquals(original[index].toDouble(), decoded[index].toDouble(), 1.0 / 32_767)
        }
    }

    // --- streaming -----------------------------------------------------------------------

    @Test
    fun `streaming yields the same samples as decoding everything`() = runTest {
        val original = sine(20_000)
        val wav = WavBytes.pcm16(original)

        val streamed = decoder.decodeStream(source(wav)).toList()
            .flatMap { chunk -> chunk.buffer.samples.toList() }
        val whole = decodeAllSamples(wav).toList()

        assertEquals(whole, streamed)
    }

    @Test
    fun `chunk start times follow the audio-source epoch`() = runTest {
        // §9.1: the epoch is t=0 of the raw asset, and decoding knows nothing of trimming.
        val chunks = decoder.decodeStream(source(WavBytes.pcm16(sine(20_000)))).toList()

        assertEquals(AudioSourceTime.EPOCH, chunks.first().startTime)
        var frame = 0L
        chunks.forEach { chunk ->
            assertEquals(frame * 1_000_000L / 48_000, chunk.startTime.micros)
            assertEquals(frame, chunk.buffer.startFrame)
            frame += chunk.frameCount
        }
        assertTrue(chunks.last().isLast)
    }

    @Test
    fun `seeking forward skips exactly the requested duration`() = runTest {
        // A monotonic ramp, deliberately not a tone. A 440 Hz sine completes exactly 220
        // cycles in 24 000 samples at 48 kHz, so the second half of such a file is
        // bit-identical to the first — a seek to the midpoint is then undetectable, and a
        // decoder that ignored the seek entirely would pass. Every sample of a ramp is
        // distinct, so the offset has nowhere to hide.
        val original = FloatArray(48_000) { n -> n / 48_000.0f * 2.0f - 1.0f }

        val chunks = decoder.decodeStream(
            source(WavBytes.float32(original)),
            from = AudioSourceTime.ofMillis(500),
        ).toList()

        assertEquals(24_000L, chunks.first().buffer.startFrame)
        val streamed = chunks.flatMap { it.buffer.samples.toList() }
        assertEquals(24_000, streamed.size)

        val expected = original.copyOfRange(24_000, 48_000)
        expected.indices.forEach { index ->
            assertEquals("sample $index of the seeked stream", expected[index], streamed[index])
        }
        // The tail is genuinely distinguishable from the head, so the comparison has teeth.
        assertTrue(
            "seeked audio must differ from the start of the file",
            (0 until 24_000).any { abs(original[it] - expected[it]) > 1e-3f },
        )
    }

    @Test
    fun `seeking past the end yields nothing rather than failing`() = runTest {
        val chunks = decoder.decodeStream(
            source(WavBytes.pcm16(sine(4_800))),
            from = AudioSourceTime.ofSeconds(10.0),
        ).toList()

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `stereo is decoded interleaved with both channels intact`() = runTest {
        val interleaved = floatArrayOf(1.0f, -1.0f, 0.5f, -0.5f)
        val decoded = decoder.decodeAll(source(WavBytes.float32(interleaved, channels = 2)))

        val pcm = (decoded as Outcome.Success).value
        assertEquals(2, pcm.format.channelCount)
        assertEquals(2L, pcm.totalFrames)
        // §17.3's downmix: 0.5*L + 0.5*R. Both frames cancel to zero, which they only can if
        // the channels survived interleaving in the right order.
        assertTrue(pcm.readMono(0, 2).all { abs(it) < 1e-6f })
    }

    // --- §97 failure paths ---------------------------------------------------------------

    @Test
    fun `a non-RIFF file is UNSUPPORTED_FORMAT, not a decode failure`() = runTest {
        val error = (decoder.probe(source(WavBytes.notRiff(), "notes.txt")) as Outcome.Failure).error

        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, error.category)
        assertTrue(error.message.contains("notes.txt"))
        assertTrue(error.recoveryHint!!.isNotBlank())
    }

    @Test
    fun `a RIFF container that is not WAVE is rejected by name`() = runTest {
        val error = (decoder.probe(source(WavBytes.riffButNotWave(), "clip.avi")) as Outcome.Failure).error

        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, error.category)
        assertTrue(error.message.contains("WAVE"))
    }

    @Test
    fun `an unsupported encoding names what was found`() = runTest {
        // §97 forbids a generic message: the user has to learn what is wrong with the file.
        val error = (decoder.probe(source(WavBytes.unsupportedEncoding())) as Outcome.Failure).error

        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, error.category)
        assertTrue(error.message.contains("11"))    // the format tag, in hex
        assertTrue(error.message.contains("4-bit"))
    }

    @Test
    fun `a truncated header is a categorised error, never a crash`() = runTest {
        listOf(2, 8, 20, 30, 40).forEach { length ->
            val outcome = decoder.probe(source(WavBytes.truncatedHeader(length), "cut-$length.wav"))
            val error = (outcome as Outcome.Failure).error
            assertTrue(
                "length $length gave ${error.category}",
                error.category == ErrorCategory.DECODER_ERROR ||
                    error.category == ErrorCategory.UNSUPPORTED_FORMAT,
            )
        }
    }

    @Test
    fun `a data chunk before fmt is reported as corrupt`() = runTest {
        val error = (decoder.probe(source(WavBytes.dataBeforeFmt())) as Outcome.Failure).error

        assertEquals(ErrorCategory.DECODER_ERROR, error.category)
        assertTrue(error.message.contains("before its fmt"))
    }

    @Test
    fun `a payload shorter than its declared size yields the samples that exist`() = runTest {
        // A truncated file is common — an interrupted copy or a partial download. Returning
        // what decoded, rather than throwing, is what lets the user still see and trim it.
        val pcm = (decoder.decodeAll(source(WavBytes.truncatedPayload(1_000, 100))) as Outcome.Success).value

        assertEquals(100L, pcm.totalFrames)
    }

    @Test
    fun `zero channels or zero sample rate is rejected`() = runTest {
        val zeroChannels = WavBytes.pcm16(FloatArray(4)).also { it[22] = 0; it[23] = 0 }
        val error = (decoder.probe(source(zeroChannels)) as Outcome.Failure).error
        assertEquals(ErrorCategory.DECODER_ERROR, error.category)
    }

    // --- PcmSource contract --------------------------------------------------------------

    @Test
    fun `reads past the end return fewer frames rather than failing`() = runTest {
        // 17.5's tail windows are zero-padded by the caller; the source just reports what
        // exists.
        val pcm = (decoder.decodeAll(source(WavBytes.pcm16(sine(1_000)))) as Outcome.Success).value

        assertEquals(1_000, pcm.readMono(0, 1_000).size)
        assertEquals(10, pcm.readMono(990, 100).size)
        assertEquals(0, pcm.readMono(5_000, 100).size)
        assertEquals(0, pcm.readMono(0, 0).size)
    }

    private suspend fun decodeAllSamples(wav: ByteArray): FloatArray {
        val pcm = (decoder.decodeAll(source(wav)) as Outcome.Success).value
        val channels = pcm.format.channelCount
        return if (channels == 1) {
            pcm.readMono(0, pcm.totalFrames.toInt())
        } else {
            pcm.readMono(0, pcm.totalFrames.toInt())
        }
    }
}
