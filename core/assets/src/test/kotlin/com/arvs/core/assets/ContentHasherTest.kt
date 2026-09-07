package com.arvs.core.assets

import com.arvs.core.model.AssetUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * Pins the content hash that §27.2 makes binding for every cache key.
 */
class ContentHasherTest {

    private val storage = FakeAssetStorage()
    private val hasher = ContentHasher(storage)

    @Test
    fun `matches the published SHA-256 vectors`() {
        // Pinned against known-good values rather than against our own output, so a change
        // of algorithm or encoding cannot be "confirmed" by the implementation it broke.
        runTest {
            val empty = storage.put("content://empty", ByteArray(0))
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hasher.hash(empty).hex,
            )

            val abc = storage.put("content://abc", "abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                hasher.hash(abc).hex,
            )
        }
    }

    @Test
    fun `the digest does not depend on how the stream was buffered`() = runTest {
        // Two imports of the same file must produce the same cache identity regardless of
        // chunking, or the analysis cache silently splits into duplicate entries.
        val bytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
        val uri = storage.put("content://track.flac", bytes)

        val hashes = listOf(1, 7, 4096, 65_536, 1_000_000)
            .map { size -> ContentHasher(storage, bufferSize = size).hash(uri).hex }

        assertEquals(1, hashes.distinct().size)
    }

    @Test
    fun `a stream that returns one byte at a time hashes identically`() = runTest {
        // A provider is free to return short reads; the digest must not notice.
        val bytes = "the quick brown fox".toByteArray()
        val normal = FakeAssetStorage().also { it.put("content://a", bytes) }
        val dripping = object : AssetStorage by normal {
            override fun openInputStream(uri: AssetUri): InputStream = DripFeedInputStream(bytes)
        }

        assertEquals(
            ContentHasher(normal).hash(AssetUri("content://a")).hex,
            ContentHasher(dripping).hash(AssetUri("content://a")).hex,
        )
    }

    @Test
    fun `different content hashes differently`() = runTest {
        val a = storage.put("content://a", byteArrayOf(1, 2, 3))
        val b = storage.put("content://b", byteArrayOf(1, 2, 4))
        assertNotEquals(hasher.hash(a).hex, hasher.hash(b).hex)
    }

    @Test
    fun `hashing checks cancellation before every chunk`() {
        // Import is user-initiated and cancellable. The check has to be *inside* the read
        // loop: a hasher that only checks at the end would still read the whole file and
        // then throw away the answer, which is the cost the cancellation exists to avoid.
        val target = storage.put("content://big", ByteArray(1_000_000))
        val job = Job()
        var chunksRead = 0

        val cancellingStorage = object : AssetStorage by storage {
            override fun openInputStream(uri: AssetUri): InputStream {
                val delegate = storage.openInputStream(uri)
                return object : InputStream() {
                    override fun read(): Int = delegate.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        chunksRead++
                        if (chunksRead == 3) job.cancel()
                        return delegate.read(b, off, len)
                    }
                }
            }
        }

        assertThrows(CancellationException::class.java) {
            runBlocking(job) { ContentHasher(cancellingStorage, bufferSize = 1024).hash(target) }
        }

        // Stopped on the iteration after the cancel, not after all 977 chunks.
        assertEquals(3, chunksRead)
    }

    @Test
    fun `a zero buffer size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ContentHasher(storage, bufferSize = 0) }
    }

    @Test
    fun `a storage failure propagates as AssetStorageException`() {
        val uri = storage.put("content://gone", byteArrayOf(1))
        storage.deleteExternally(uri)

        val thrown = assertThrows(AssetStorageException::class.java) {
            runBlocking { hasher.hash(uri) }
        }
        assertEquals(AssetStorageException.Kind.NOT_FOUND, thrown.kind)
    }

    @Test
    fun `hashes are lowercase hex of SHA-256 length`() = runTest {
        val uri = storage.put("content://x", byteArrayOf(9, 9, 9))
        val hex = hasher.hash(uri).hex
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
