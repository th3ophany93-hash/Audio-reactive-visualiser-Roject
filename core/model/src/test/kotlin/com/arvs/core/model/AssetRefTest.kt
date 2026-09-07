package com.arvs.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins §10's binding `AssetRef` schema and §82/§82.1's identity rules.
 *
 * These live in `core:model` rather than `core:assets` because §116.1's graph puts them
 * there: `core:project` owns `Project`, `Project` contains `assets: [AssetRef]`, and
 * `core:project` has no edge to `core:assets`.
 */
class AssetRefTest {

    private val hash = AssetHash("a".repeat(64))

    private fun ref(
        id: String = "asset-1",
        uri: String = "content://track.flac",
        hash: AssetHash = this.hash,
        lastKnownHash: AssetHash = hash,
    ) = AssetRef(
        id = AssetId(id),
        uri = AssetUri(uri),
        type = AssetType.AUDIO,
        hash = hash,
        persistedPermission = true,
        lastKnownHash = lastKnownHash,
    )

    @Test
    fun `carries every field of the section 10 binding schema`() {
        // §10: AssetRef {id, uri, type, hash, persistedPermission}, plus §82.1's lastKnownHash.
        val asset = ref()
        assertEquals(AssetId("asset-1"), asset.id)
        assertEquals(AssetUri("content://track.flac"), asset.uri)
        assertEquals(AssetType.AUDIO, asset.type)
        assertEquals(hash, asset.hash)
        assertTrue(asset.persistedPermission)
        assertEquals(hash, asset.lastKnownHash)
    }

    @Test
    fun `lastKnownHash defaults to hash, so an import is consistent by construction`() {
        assertEquals(hash, ref().lastKnownHash)
        assertFalse(ref().contentChangedSinceLastValidation)
    }

    @Test
    fun `a diverging lastKnownHash marks the content as changed`() {
        // The one case the two fields exist to distinguish: a relink to different bytes.
        val relinked = ref(hash = AssetHash("b".repeat(64)), lastKnownHash = AssetHash("b".repeat(64)))
        assertFalse(relinked.contentChangedSinceLastValidation)

        val mismatched = ref(hash = AssetHash("b".repeat(64)), lastKnownHash = AssetHash("c".repeat(64)))
        assertTrue(mismatched.contentChangedSinceLastValidation)
    }

    @Test
    fun `the project-local id is not a content hash and cannot be used as one`() {
        // §27.2: assetHash is obtained by resolving the ref through the Asset Registry to its
        // hash field, never from the id. The two are separate types, and an id-shaped string
        // is not even a valid hash.
        assertNotEquals(ref().id.value, ref().hash.hex)
        assertThrows(IllegalArgumentException::class.java) { AssetHash("asset-1") }
        assertThrows(IllegalArgumentException::class.java) { AssetHash("A".repeat(64)) } // must be lowercase
    }

    @Test
    fun `the uri is a portable string, not a platform object`() {
        // This value is written into the project file and read back on another day, possibly
        // another OS version. §116.1 also keeps core:model free of Android.
        val uri = AssetUri("content://com.android.providers.media.documents/document/audio%3A42")
        assertEquals("content://com.android.providers.media.documents/document/audio%3A42", uri.value)
        assertThrows(IllegalArgumentException::class.java) { AssetUri("  ") }
    }

    @Test
    fun `two references to the same content keep distinct ids`() {
        // Content-addressing collapses duplicates in the cache; the project keeps two
        // references. Conflating those is exactly what §27.2 forbids.
        val first = ref(id = "asset-1", uri = "content://one.wav")
        val second = ref(id = "asset-2", uri = "content://two.wav")
        assertEquals(first.hash, second.hash)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `asset types cover the reserved set`() {
        // v1 imports only AUDIO (§1, §15). IMAGE and VIDEO are reserved because §82 names
        // `dimensions` and §34A anticipates video proxies.
        assertEquals(
            listOf(AssetType.AUDIO, AssetType.IMAGE, AssetType.VIDEO),
            AssetType.entries.toList(),
        )
    }

    @Test
    fun `a blank id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AssetId("") }
    }
}
