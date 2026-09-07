package com.arvs.core.assets

import com.arvs.core.model.AssetUri
import java.io.InputStream

/**
 * The storage port the Asset Registry works through — §82's "Use Android Storage Access
 * Framework appropriately", expressed as a boundary rather than as a dependency.
 *
 * Everything the registry actually needs from the platform is here, and it is a short list:
 * open a stream, check existence, check and take a persistable permission, read a display
 * name and a size. `SafAssetStorage` implements it against `ContentResolver`.
 *
 * Doing it this way is what makes §82.1's rules — lazy validation, the missing-asset path,
 * relink — testable on the JVM against a fake, rather than only on a device holding a file
 * you must then delete by hand to exercise the failure branch. The failure branches are the
 * ones §82.1 cares about, so they are the ones that most need to be cheap to test.
 */
public interface AssetStorage {

    /** Whether the document still exists and is readable. */
    public fun exists(uri: AssetUri): Boolean

    /**
     * Whether a *persisted* read permission is currently held (§82.1's
     * `persistedPermissionTaken`).
     *
     * Distinct from [exists]: a revoked permission and a deleted file are different failures
     * with different remedies, and §97 requires them reported as different categories.
     */
    public fun hasPersistedPermission(uri: AssetUri): Boolean

    /**
     * Takes a persistable read permission. Returns false if the grant could not be persisted
     * — a real outcome on SAF, not an exceptional one.
     */
    public fun takePersistablePermission(uri: AssetUri): Boolean

    /** Opens the asset's bytes. The caller closes the stream. */
    public fun openInputStream(uri: AssetUri): InputStream

    /** Display name, if the provider offers one. Never used for identity (§82). */
    public fun displayName(uri: AssetUri): String?

    /** Size in bytes if known, else null. Used for progress and diagnostics only. */
    public fun sizeBytes(uri: AssetUri): Long?
}

/**
 * Raised by an [AssetStorage] when a document cannot be opened.
 *
 * The registry catches this and converts it to a §97-categorised outcome; it never escapes
 * to a caller as a bare exception, because §82.1 requires a missing asset to be recoverable
 * and the project to remain editable.
 */
public class AssetStorageException(
    message: String,
    public val kind: Kind,
    cause: Throwable? = null,
) : Exception(message, cause) {

    public enum class Kind {
        /** The document is gone, moved, or was never there. */
        NOT_FOUND,

        /** The document exists but this app may no longer read it (SAF grant revoked). */
        PERMISSION_DENIED,

        /** I/O failure while reading. */
        IO_FAILURE,
    }
}
