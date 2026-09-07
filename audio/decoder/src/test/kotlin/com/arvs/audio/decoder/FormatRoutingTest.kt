package com.arvs.audio.decoder

import com.arvs.core.model.ErrorCategory
import com.arvs.core.model.Outcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins §15's format set, content-based identification, and the routing failures §97 governs.
 */
class FormatRoutingTest {

    private val router = DecoderRouter.uncompressedOnly()

    private fun source(bytes: ByteArray, name: String = "input") = ByteArrayDecodeSource(bytes, name)

    private fun prefix(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    // --- §15's five formats --------------------------------------------------------------

    @Test
    fun `the five formats section 15 requires are all declared`() {
        assertEquals(
            listOf(
                AudioContainerFormat.WAV,
                AudioContainerFormat.MP3,
                AudioContainerFormat.M4A_AAC,
                AudioContainerFormat.FLAC,
                AudioContainerFormat.OGG,
            ),
            AudioContainerFormat.entries.toList(),
        )
    }

    @Test
    fun `only WAV decodes without a platform codec`() {
        // The distinction the CI tier rests on: hardware decoders are not bit-exact, so
        // golden vectors come only from the uncompressed path.
        assertEquals(
            listOf(AudioContainerFormat.WAV),
            AudioContainerFormat.entries.filter { it.decodableWithoutPlatformCodec },
        )
    }

    // --- sniffing --------------------------------------------------------------------------

    @Test
    fun `WAV is identified by RIFF plus WAVE, not by RIFF alone`() {
        // RIFF also fronts AVI. Accepting it on the magic alone would route a video file to
        // the audio decoder and report a decode failure instead of an unsupported format.
        assertEquals(AudioContainerFormat.WAV, FormatSniffer.sniff(WavBytes.pcm16(FloatArray(4))))
        assertNull(FormatSniffer.sniff(WavBytes.riffButNotWave()))
    }

    @Test
    fun `FLAC and Ogg are identified by their magic`() {
        assertEquals(AudioContainerFormat.FLAC, FormatSniffer.sniff("fLaC".toByteArray() + ByteArray(8)))
        assertEquals(AudioContainerFormat.OGG, FormatSniffer.sniff("OggS".toByteArray() + ByteArray(8)))
    }

    @Test
    fun `M4A is identified by the ISO-BMFF ftyp box`() {
        val bmff = prefix(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()
        assertEquals(AudioContainerFormat.M4A_AAC, FormatSniffer.sniff(bmff))
    }

    @Test
    fun `MP3 is identified behind an ID3 tag or as a bare frame header`() {
        assertEquals(AudioContainerFormat.MP3, FormatSniffer.sniff("ID3".toByteArray() + ByteArray(9)))
        // 11 set sync bits.
        assertEquals(AudioContainerFormat.MP3, FormatSniffer.sniff(prefix(0xFF, 0xFB, 0x90, 0x00)))
        assertEquals(AudioContainerFormat.MP3, FormatSniffer.sniff(prefix(0xFF, 0xE0, 0x00, 0x00)))
    }

    @Test
    fun `a near-miss sync word is not mistaken for MP3`() {
        assertNull(FormatSniffer.sniff(prefix(0xFF, 0xC0, 0x00, 0x00))) // only 10 sync bits
        assertNull(FormatSniffer.sniff(prefix(0xFE, 0xFB, 0x00, 0x00)))
    }

    @Test
    fun `unrecognised content sniffs to null rather than guessing`() {
        assertNull(FormatSniffer.sniff("plain text file".toByteArray()))
        assertNull(FormatSniffer.sniff(ByteArray(0)))
        assertNull(FormatSniffer.sniff(prefix(1)))
    }

    @Test
    fun `identification is by content, never by file extension`() {
        // A SAF document URI often has no extension, and one that does may be lying.
        val wavBytesNamedMp3 = source(WavBytes.pcm16(FloatArray(8)), "actually-a-wav.mp3")
        assertEquals(AudioContainerFormat.WAV, router.detectFormat(wavBytesNamedMp3))

        val textNamedWav = source("not audio at all".toByteArray(), "lies.wav")
        assertNull(router.detectFormat(textNamedWav))
    }

    // --- routing ---------------------------------------------------------------------------

    @Test
    fun `a WAV routes to the uncompressed decoder`() = runTest {
        val outcome = router.probe(source(WavBytes.pcm16(FloatArray(48_000))))
        assertEquals(48_000, (outcome as Outcome.Success).value.sampleRateHz)
    }

    @Test
    fun `unrecognised content is UNSUPPORTED_FORMAT naming the supported set`() = runTest {
        val error = (router.probe(source("hello".toByteArray(), "notes.txt")) as Outcome.Failure).error

        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, error.category)
        assertTrue(error.message.contains("notes.txt"))
        listOf("WAV", "MP3", "M4A/AAC", "FLAC", "Ogg").forEach { format ->
            assertTrue("message should name $format: ${error.message}", error.message.contains(format))
        }
    }

    @Test
    fun `a recognised format with no configured decoder says so specifically`() = runTest {
        // On a JVM there is no MediaCodec, so an MP3 is recognised but unroutable. The
        // message distinguishes that from "this is not audio" — a different problem with a
        // different remedy.
        val mp3 = source("ID3".toByteArray() + ByteArray(64), "track.mp3")
        val error = (router.probe(mp3) as Outcome.Failure).error

        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, error.category)
        assertTrue(error.message.contains("MP3"))
        assertTrue(error.message.contains("no configured decoder"))
    }

    @Test
    fun `a routing failure throws into the stream rather than emitting nothing`() = runTest {
        // A Flow cannot carry an Outcome. Completing empty would be indistinguishable from a
        // valid zero-length file, so the categorised error is thrown instead.
        val thrown = assertThrows(DecoderRoutingException::class.java) {
            kotlinx.coroutines.runBlocking {
                router.decodeStream(source("nonsense".toByteArray(), "x.bin")).toList()
            }
        }
        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, thrown.error.category)
    }

    @Test
    fun `the router reports the union of its decoders formats`() {
        assertEquals(setOf(AudioContainerFormat.WAV), router.supportedFormats)
        assertEquals(emptySet<AudioContainerFormat>(), DecoderRouter(emptyList()).supportedFormats)
    }

    @Test
    fun `an unreadable source detects no format instead of throwing`() {
        val exploding = object : DecodeSource {
            override val identity = "unreadable"
            override fun openStream() = throw java.io.IOException("gone")
        }
        assertNull(router.detectFormat(exploding))
    }
}
