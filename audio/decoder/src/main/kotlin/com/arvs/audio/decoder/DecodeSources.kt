package com.arvs.audio.decoder

import com.arvs.core.assets.AssetStorage
import com.arvs.core.model.AssetRef
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A [DecodeSource] that the platform decoder can hand to `MediaExtractor`.
 *
 * `MediaExtractor` cannot consume an arbitrary [InputStream]; it needs a content URI or a file
 * descriptor. Rather than widen [DecodeSource] with a platform concept every implementation
 * would have to carry, sources that *can* offer one advertise it here, and the platform
 * decoder requires it. A source that cannot is simply not routable to `MediaCodec`, which is
 * the honest outcome.
 */
public interface ContentUriSource : DecodeSource {
    public val contentUri: String
}

/** Decodes the bytes behind a registered [AssetRef] (§82). */
public class AssetDecodeSource(
    public val assetRef: AssetRef,
    private val storage: AssetStorage,
) : ContentUriSource {

    override val identity: String get() = assetRef.displayName ?: assetRef.uri.value

    override val contentUri: String get() = assetRef.uri.value

    override fun openStream(): InputStream = storage.openInputStream(assetRef.uri)

    override val sizeBytes: Long? get() = storage.sizeBytes(assetRef.uri)
}

/** Decodes bytes already in memory. Used by fixtures and by format sniffing. */
public class ByteArrayDecodeSource(
    private val bytes: ByteArray,
    override val identity: String,
) : DecodeSource {
    override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    override val sizeBytes: Long get() = bytes.size.toLong()
}
