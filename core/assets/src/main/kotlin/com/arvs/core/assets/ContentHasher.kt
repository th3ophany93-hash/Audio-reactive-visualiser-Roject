package com.arvs.core.assets

import com.arvs.core.model.AssetHash
import com.arvs.core.model.AssetUri
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * SHA-256 over an asset's byte stream — the `hash` field of §82, and the `assetHash` that
 * §27.2 makes binding for every content-addressed cache key.
 *
 * Three properties matter here, and each is asserted by a test:
 *
 *  - **Streaming.** Hashed in fixed-size chunks, so a 300 MB FLAC costs a buffer rather than
 *    300 MB of heap. This module runs on an API-35 phone, not a build server.
 *  - **Cancellable.** Import is user-initiated and cancellable; the loop checks the
 *    coroutine's state every chunk, so cancelling an import stops reading rather than
 *    finishing the file first and discarding the answer.
 *  - **Chunk-size independent.** The digest of a stream must not depend on how it was
 *    buffered, or two imports of the same file could produce different cache identities.
 */
public class ContentHasher(
    private val storage: AssetStorage,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    init { require(bufferSize > 0) { "bufferSize must be positive: $bufferSize" } }

    /**
     * Hashes the asset at [uri].
     *
     * @throws AssetStorageException if the document cannot be read; the registry converts
     *   that into a §97-categorised outcome.
     */
    public suspend fun hash(uri: AssetUri): AssetHash {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)

        storage.openInputStream(uri).use { stream ->
            while (true) {
                coroutineContext.ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }

        return AssetHash(digest.digest().toHexString())
    }

    public companion object {
        /** 64 KiB: large enough to amortise syscalls, small enough to stay off the heap's radar. */
        public const val DEFAULT_BUFFER_SIZE: Int = 64 * 1024
    }
}

internal fun ByteArray.toHexString(): String {
    val hexDigits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (index in indices) {
        val byte = this[index].toInt() and 0xFF
        out[index * 2] = hexDigits[byte ushr 4]
        out[index * 2 + 1] = hexDigits[byte and 0x0F]
    }
    return String(out)
}
