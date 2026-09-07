package com.arvs.core.assets

import com.arvs.core.model.AssetUri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory [AssetStorage] that records every call.
 *
 * The call log is not incidental. §82.1's laziness requirement — "validated **lazily** (on
 * first actual use, not eagerly for the whole project)" — is a statement about calls that
 * must *not* happen, and the only way to assert an absence is to observe the port.
 */
class FakeAssetStorage : AssetStorage {

    /**
     * A document's state.
     *
     * [permissionPersisted] and [readable] are separate on purpose, because §82.1 treats them
     * as separate: SAF providers routinely grant a *session* read permission while refusing to
     * persist it, so an asset can be perfectly readable today and require relinking after a
     * reboot. Collapsing the two into one flag makes that case impossible to express — and it
     * is the case that produces the confused bug reports.
     */
    data class Document(
        val bytes: ByteArray,
        /** The document exists in storage. */
        var exists: Boolean = true,
        /** A durable grant is held — what `hasPersistedPermission` reports. */
        var permissionPersisted: Boolean = true,
        /** Whether the provider will actually hand over a stream right now. */
        var readable: Boolean = true,
        /** Whether the provider is willing to persist a grant when asked. */
        var permissionGrantable: Boolean = true,
        val displayName: String? = null,
        var readFailure: AssetStorageException? = null,
    )

    private val documents = linkedMapOf<String, Document>()

    /** Every call made to this storage, in order. */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    fun put(
        uri: String,
        bytes: ByteArray,
        displayName: String? = null,
        permissionGrantable: Boolean = true,
    ): AssetUri {
        documents[uri] = Document(
            bytes = bytes,
            displayName = displayName,
            permissionGrantable = permissionGrantable,
        )
        return AssetUri(uri)
    }

    fun document(uri: AssetUri): Document = documents.getValue(uri.value)

    /** Simulates the file being moved or deleted outside the app (§82.1). */
    fun deleteExternally(uri: AssetUri) {
        document(uri).exists = false
    }

    /**
     * Simulates the SAF grant being revoked (§82.1) — both the durable grant and the ability
     * to read, which is what revocation actually means.
     */
    fun revokePermission(uri: AssetUri) {
        document(uri).permissionPersisted = false
        document(uri).readable = false
    }

    fun clearCalls() = calls.clear()

    override fun exists(uri: AssetUri): Boolean {
        calls += "exists(${uri.value})"
        return documents[uri.value]?.exists == true
    }

    override fun hasPersistedPermission(uri: AssetUri): Boolean {
        calls += "hasPersistedPermission(${uri.value})"
        return documents[uri.value]?.permissionPersisted == true
    }

    override fun takePersistablePermission(uri: AssetUri): Boolean {
        calls += "takePersistablePermission(${uri.value})"
        val document = documents[uri.value] ?: return false
        // A provider that refuses to persist a grant does not thereby become unreadable;
        // only the durable claim is affected.
        document.permissionPersisted = document.permissionGrantable
        return document.permissionGrantable
    }

    override fun openInputStream(uri: AssetUri): InputStream {
        calls += "openInputStream(${uri.value})"
        val document = documents[uri.value]
            ?: throw AssetStorageException("no such document", AssetStorageException.Kind.NOT_FOUND)
        document.readFailure?.let { throw it }
        if (!document.exists) {
            throw AssetStorageException("deleted", AssetStorageException.Kind.NOT_FOUND)
        }
        if (!document.readable) {
            throw AssetStorageException("revoked", AssetStorageException.Kind.PERMISSION_DENIED)
        }
        return ByteArrayInputStream(document.bytes)
    }

    override fun displayName(uri: AssetUri): String? {
        calls += "displayName(${uri.value})"
        return documents[uri.value]?.displayName
    }

    override fun sizeBytes(uri: AssetUri): Long? {
        calls += "sizeBytes(${uri.value})"
        return documents[uri.value]?.bytes?.size?.toLong()
    }
}

/** A stream that yields one byte at a time, to prove the hasher is buffering-independent. */
class DripFeedInputStream(private val bytes: ByteArray) : InputStream() {
    private var position = 0
    override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xFF
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        buffer[offset] = bytes[position++]
        return 1
    }
}
