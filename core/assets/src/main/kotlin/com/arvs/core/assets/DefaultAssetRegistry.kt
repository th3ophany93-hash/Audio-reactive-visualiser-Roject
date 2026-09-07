package com.arvs.core.assets

import com.arvs.core.diagnostics.ErrorReporter
import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.Subsystem
import com.arvs.core.diagnostics.TimingBreakdown
import com.arvs.core.diagnostics.TimingSpanRecorder
import com.arvs.core.model.ArvsError
import com.arvs.core.model.AssetHash
import com.arvs.core.model.AssetId
import com.arvs.core.model.AssetRef
import com.arvs.core.model.AssetType
import com.arvs.core.model.AssetUri
import com.arvs.core.model.ErrorCategory
import com.arvs.core.model.Outcome
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The §82 / §82.1 Asset Registry.
 *
 * Instrumented against `core:diagnostics` from the outset, which is what §100.1 means by
 * "each subsystem self-instruments with named timing spans from day one": import and hashing
 * are timed under [Subsystem.ASSETS], and every failure goes through [ErrorReporter] so it
 * lands in the §98 report with a §97 category attached.
 */
public class DefaultAssetRegistry(
    private val storage: AssetStorage,
    private val hasher: ContentHasher = ContentHasher(storage),
    logger: Logger,
    private val errorReporter: ErrorReporter = ErrorReporter(logger),
    private val timings: TimingSpanRecorder = TimingSpanRecorder(),
    private val idFactory: () -> AssetId = SequentialAssetIds(),
) : AssetRegistry {

    private val log = logger.forSubsystem(Subsystem.ASSETS)
    private val entries = ConcurrentHashMap<AssetId, AssetRef>()

    /**
     * Ids whose availability has already been confirmed.
     *
     * Only *successes* are cached. A failure is re-probed on every use, which costs one
     * existence check and means an asset that comes back — the user restores a file, or
     * re-grants access in system settings — is picked up automatically. Caching the failure
     * would produce the worst kind of bug report: "I fixed it and it still says missing."
     */
    private val validated = ConcurrentHashMap.newKeySet<AssetId>()

    override suspend fun import(uri: AssetUri, type: AssetType): Outcome<AssetRef> {
        val span = timings.begin(Subsystem.ASSETS, "import")
        try {
            val permissionTaken = runCatching { storage.takePersistablePermission(uri) }
                .getOrDefault(false)
            if (!permissionTaken) {
                // Not fatal by itself — some providers grant read access without a
                // persistable claim — but it is exactly the condition that turns into a
                // missing asset after a reboot, so it is recorded now rather than diagnosed
                // later from a confused bug report.
                log.warn(
                    key = "import-permission-not-persisted",
                    message = "Persistable read permission was not granted for an imported asset; " +
                        "it may require relinking after restart (§82.1)",
                )
            }

            val hash = timings.record(Subsystem.ASSETS, "hash") { hasher.hash(uri) }

            val ref = AssetRef(
                id = idFactory(),
                uri = uri,
                type = type,
                hash = hash,
                persistedPermission = permissionTaken,
                lastKnownHash = hash,
                displayName = runCatching { storage.displayName(uri) }.getOrNull(),
            )
            entries[ref.id] = ref
            validated += ref.id
            log.info("import", "Imported asset ${ref.id} (${ref.displayName ?: "unnamed"})")
            return Outcome.success(ref)
        } catch (cancellation: CancellationException) {
            // An import the user cancelled is not a failure to report; propagate it intact.
            throw cancellation
        } catch (failure: Throwable) {
            val error = failure.toImportError(uri)
            errorReporter.report(Subsystem.ASSETS, "import", error)
            return Outcome.Failure(error)
        } finally {
            span.close()
        }
    }

    override fun resolve(id: AssetId): AssetRef? = entries[id]

    override suspend fun validate(id: AssetId): AssetAvailability {
        val ref = entries[id] ?: return AssetAvailability.Unknown(id)

        if (id in validated) return AssetAvailability.Available(ref)

        val availability = timings.record(Subsystem.ASSETS, "validate") { probe(ref) }
        when (availability) {
            is AssetAvailability.Available -> validated += id
            is AssetAvailability.MissingRelinkRequired ->
                errorReporter.report(Subsystem.ASSETS, "missing-asset", availability.reason.toError(ref))
            is AssetAvailability.Unknown -> Unit // probe() cannot produce this
        }
        return availability
    }

    override fun contentHash(id: AssetId): AssetHash? = entries[id]?.hash

    override suspend fun relink(id: AssetId, newUri: AssetUri): Outcome<RelinkResult> {
        val existing = entries[id]
            ?: return Outcome.failure(
                ErrorCategory.IMPORT_ERROR,
                "Cannot relink unknown asset '$id'",
                recoveryHint = "Import the file instead of relinking",
            )

        val span = timings.begin(Subsystem.ASSETS, "relink")
        try {
            val permissionTaken = runCatching { storage.takePersistablePermission(newUri) }
                .getOrDefault(false)
            val newHash = hasher.hash(newUri)

            val relinked = existing.copy(
                uri = newUri,
                hash = newHash,
                persistedPermission = permissionTaken,
                lastKnownHash = newHash,
                displayName = runCatching { storage.displayName(newUri) }.getOrNull()
                    ?: existing.displayName,
            )
            entries[id] = relinked
            validated += id

            return if (newHash == existing.hash) {
                log.info("relink", "Relinked asset $id to identical content")
                Outcome.success(RelinkResult.ExactMatch(relinked))
            } else {
                log.warn(
                    key = "relink-content-changed",
                    message = "Asset $id was relinked to different content; analysis will be " +
                        "recomputed against the new audio (§82.1)",
                )
                Outcome.success(RelinkResult.ContentChanged(relinked, previousHash = existing.hash))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val error = failure.toImportError(newUri)
            errorReporter.report(Subsystem.ASSETS, "relink", error)
            return Outcome.Failure(error)
        } finally {
            span.close()
        }
    }

    override fun all(): List<AssetRef> = entries.values.sortedBy { it.id.value }

    /** Registers an already-known reference — used when a project is loaded (Phase 9, §81). */
    public fun register(ref: AssetRef) {
        entries[ref.id] = ref
        // Deliberately NOT marked validated: §82.1 requires assets restored from a project
        // to be validated lazily, on first actual use. Trusting a persisted reference is the
        // eager validation §82.1 rules out, one asset at a time.
        validated.remove(ref.id)
    }

    /** Forces the next [validate] of [id] to re-probe storage. */
    public fun invalidate(id: AssetId) {
        validated.remove(id)
    }

    /** Named timing spans recorded so far (§100.1). */
    public fun timingBreakdown(): TimingBreakdown = timings.snapshot()

    private fun probe(ref: AssetRef): AssetAvailability {
        val permitted = runCatching { storage.hasPersistedPermission(ref.uri) }.getOrDefault(false)
        if (!permitted) {
            return AssetAvailability.MissingRelinkRequired(ref, MissingReason.PERMISSION_REVOKED)
        }
        val present = runCatching { storage.exists(ref.uri) }.getOrDefault(false)
        if (!present) {
            return AssetAvailability.MissingRelinkRequired(ref, MissingReason.FILE_NOT_FOUND)
        }
        return AssetAvailability.Available(ref)
    }
}

/** Default id source: stable within a session and never mistaken for a content hash. */
public class SequentialAssetIds(private val prefix: String = "asset") : () -> AssetId {
    private val next = AtomicLong(1)
    override fun invoke(): AssetId = AssetId("$prefix-${next.getAndIncrement()}")
}

/** Maps a storage failure onto §97's taxonomy. §97 forbids a generic message anywhere. */
private fun Throwable.toImportError(uri: AssetUri): ArvsError = when {
    this is AssetStorageException && kind == AssetStorageException.Kind.PERMISSION_DENIED ->
        ArvsError(
            category = ErrorCategory.PERMISSION_ERROR,
            message = "Read permission for '$uri' was denied or revoked",
            recoveryHint = "Relink the file to grant access again",
            cause = this,
        )

    this is AssetStorageException && kind == AssetStorageException.Kind.NOT_FOUND ->
        ArvsError(
            category = ErrorCategory.IMPORT_ERROR,
            message = "The file '$uri' could not be found",
            recoveryHint = "Relink the file, or import it again from its new location",
            cause = this,
        )

    this is OutOfMemoryError ->
        ArvsError(
            category = ErrorCategory.OUT_OF_MEMORY,
            message = "Ran out of memory importing '$uri'",
            recoveryHint = "Close other apps and try again",
            cause = this,
        )

    else ->
        ArvsError(
            category = ErrorCategory.IMPORT_ERROR,
            message = "Could not read '$uri': ${message ?: this::class.java.simpleName}",
            recoveryHint = "Check that the file is complete and readable, then try again",
            cause = this,
        )
}

/** Maps a §82.1 missing-asset reason onto §97's taxonomy. */
private fun MissingReason.toError(ref: AssetRef): ArvsError = when (this) {
    MissingReason.PERMISSION_REVOKED -> ArvsError(
        category = ErrorCategory.PERMISSION_ERROR,
        message = "Access to '${ref.displayName ?: ref.uri}' was revoked",
        recoveryHint = "Relink the file to restore access",
    )

    MissingReason.FILE_NOT_FOUND -> ArvsError(
        category = ErrorCategory.IMPORT_ERROR,
        message = "'${ref.displayName ?: ref.uri}' was moved or deleted",
        recoveryHint = "Relink the file from its new location",
    )

    MissingReason.UNREADABLE -> ArvsError(
        category = ErrorCategory.IMPORT_ERROR,
        message = "'${ref.displayName ?: ref.uri}' could not be read",
        recoveryHint = "Check the file is complete, then relink it",
    )
}
