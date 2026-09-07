package com.arvs.core.assets

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.arvs.core.model.AssetUri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * The Storage Access Framework implementation of [AssetStorage] — §82's *"Use Android Storage
 * Access Framework appropriately"*.
 *
 * This is the **only** file in `core:assets` that references Android. Everything §82.1 actually
 * specifies — lazy validation, the missing-asset outcome, relink, the three-tier cache
 * boundary — lives behind [AssetStorage] and is exercised on the JVM against a fake. That is
 * deliberate: the branches worth testing here are the failure branches, and reproducing
 * "the user revoked a SAF grant three weeks ago" on a device is not something you want
 * standing between a regression and its test.
 *
 * The class is thin on purpose. Every method either translates a platform failure into an
 * [AssetStorageException] with the right [AssetStorageException.Kind], or returns a plain
 * value. No policy lives here.
 */
public class SafAssetStorage(
    private val contentResolver: ContentResolver,
) : AssetStorage {

    override fun exists(uri: AssetUri): Boolean = try {
        contentResolver.openAssetFileDescriptor(uri.toPlatformUri(), "r")?.use { true } ?: false
    } catch (_: FileNotFoundException) {
        false
    } catch (_: SecurityException) {
        // Existence is unknowable without permission. hasPersistedPermission() is what
        // distinguishes the two cases; reporting "does not exist" here would send the user
        // looking for a file that never moved.
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    override fun hasPersistedPermission(uri: AssetUri): Boolean {
        val target = uri.toPlatformUri()
        return contentResolver.persistedUriPermissions.any { it.uri == target && it.isReadPermission }
    }

    override fun takePersistablePermission(uri: AssetUri): Boolean = try {
        contentResolver.takePersistableUriPermission(
            uri.toPlatformUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (_: SecurityException) {
        // A provider that offers no persistable grant is a normal outcome, not an error:
        // the asset works this session and will need relinking later (§82.1).
        false
    }

    override fun openInputStream(uri: AssetUri): InputStream = try {
        contentResolver.openInputStream(uri.toPlatformUri())
            ?: throw AssetStorageException(
                "Provider returned no stream for '$uri'",
                AssetStorageException.Kind.NOT_FOUND,
            )
    } catch (cause: FileNotFoundException) {
        throw AssetStorageException("'$uri' was moved or deleted", AssetStorageException.Kind.NOT_FOUND, cause)
    } catch (cause: SecurityException) {
        throw AssetStorageException(
            "Read permission for '$uri' was revoked",
            AssetStorageException.Kind.PERMISSION_DENIED,
            cause,
        )
    } catch (cause: IOException) {
        throw AssetStorageException("Could not read '$uri'", AssetStorageException.Kind.IO_FAILURE, cause)
    }

    override fun displayName(uri: AssetUri): String? =
        queryColumn(uri, OpenableColumns.DISPLAY_NAME) { cursor, index -> cursor.getString(index) }

    override fun sizeBytes(uri: AssetUri): Long? =
        queryColumn(uri, OpenableColumns.SIZE) { cursor, index ->
            if (cursor.isNull(index)) null else cursor.getLong(index)
        }

    private fun <T> queryColumn(
        uri: AssetUri,
        column: String,
        read: (Cursor, Int) -> T?,
    ): T? = try {
        contentResolver.query(uri.toPlatformUri(), arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val index = cursor.getColumnIndex(column)
            if (index < 0) null else read(cursor, index)
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun AssetUri.toPlatformUri(): Uri = Uri.parse(value)
}
