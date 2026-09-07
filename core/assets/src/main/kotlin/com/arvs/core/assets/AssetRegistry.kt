package com.arvs.core.assets

import com.arvs.core.model.AssetHash
import com.arvs.core.model.AssetId
import com.arvs.core.model.AssetRef
import com.arvs.core.model.AssetType
import com.arvs.core.model.AssetUri
import com.arvs.core.model.Outcome

/**
 * §82's Asset Registry: the one place a project-local [AssetId] becomes something you can
 * actually read.
 *
 * §27.2 is binding about the indirection and it is worth being blunt about why. Every
 * content-addressed cache key uses the asset's *content hash*. The only way to get that hash
 * is to resolve the id through this registry. If any caller ever substitutes the id — which
 * is stable across re-imports and identical for two different files — the cache stops being
 * content-addressed and starts serving one track's analysis for another. `AssetId` and
 * `AssetHash` are separate types in `core:model` so that substitution cannot compile; this
 * interface is the sanctioned bridge between them.
 */
public interface AssetRegistry {

    /**
     * Imports the asset at [uri]: takes a persistable permission, hashes the bytes, and
     * registers the result (§82).
     *
     * Failures come back as §97-categorised [Outcome]s rather than exceptions — §82.1 requires
     * an unreadable asset to leave the project editable.
     */
    public suspend fun import(uri: AssetUri, type: AssetType = AssetType.AUDIO): Outcome<AssetRef>

    /** The registered reference, or null if [id] was never imported. Does not touch storage. */
    public fun resolve(id: AssetId): AssetRef?

    /**
     * Validates [id] against storage (§82.1).
     *
     * §82.1 requires validation to be **lazy** — performed on first actual use, never eagerly
     * across a whole project on load. This method *is* that first use; nothing in the registry
     * calls it on its own behalf.
     */
    public suspend fun validate(id: AssetId): AssetAvailability

    /**
     * The content hash for [id] — §27.2's required indirection.
     *
     * Null only for an unknown id. A registered asset always has a hash, because hashing
     * happens during [import] and an import that could not hash does not register.
     */
    public fun contentHash(id: AssetId): AssetHash?

    /**
     * §82.1's relink: points an existing [id] at [newUri], preserving every project reference
     * to it.
     *
     * The id is deliberately kept. Layers, the audio track and the undo history all reference
     * assets by id; minting a new one on relink would turn a recovery into a rewrite of the
     * project.
     */
    public suspend fun relink(id: AssetId, newUri: AssetUri): Outcome<RelinkResult>

    /** All registered references, for the §98 diagnostic report and project persistence. */
    public fun all(): List<AssetRef>
}

/** Result of validating an asset (§82.1). */
public sealed interface AssetAvailability {

    public data class Available(public val ref: AssetRef) : AssetAvailability

    /**
     * §82.1's recoverable failure: the host shows a **"Missing Asset — Relink"** placeholder,
     * "never a crash, and the project remains otherwise editable".
     *
     * This is a value, not an exception, precisely so that "never a crash" is a property of
     * the type rather than a discipline the caller has to maintain.
     */
    public data class MissingRelinkRequired(
        public val ref: AssetRef,
        public val reason: MissingReason,
    ) : AssetAvailability

    /**
     * The id is not registered at all — a stale project reference, not a missing file.
     *
     * Kept as its own case rather than being folded into [MissingRelinkRequired]. Doing so
     * would require fabricating an [AssetRef], and an `AssetRef` needs an [AssetHash]; a
     * plausible-looking placeholder digest is exactly the sort of value that ends up in a
     * cache key and quietly addresses the wrong content. There is no honest hash for an
     * asset nobody ever imported, so this case does not pretend to have one.
     *
     * **Ratified decision D-1** (PHASE_1_IMPLEMENTATION_PLAN.md §18) — a deliberate
     * deviation from that plan's `Available | MissingRelinkRequired` API sketch. Neither an
     * `AssetRef` nor any hashable asset identity may be fabricated for an unregistered id.
     * Do not "simplify" this case away.
     */
    public data class Unknown(public val id: AssetId) : AssetAvailability

    public val isAvailable: Boolean get() = this is Available
}

/**
 * Why an asset could not be validated.
 *
 * §82.1 names two distinct causes — "SAF permission revoked, file moved/deleted externally"
 * — and they are kept distinct here because they map to different §97 categories and, more
 * importantly, to different things the user has to do. Telling someone their file is missing
 * when the app merely lost permission to read it sends them looking for a file that is
 * exactly where they left it.
 */
public enum class MissingReason {
    /** The SAF grant was revoked or never persisted. §97 `PERMISSION_ERROR`. */
    PERMISSION_REVOKED,

    /** The document was moved, deleted, or its provider is gone. §97 `IMPORT_ERROR`. */
    FILE_NOT_FOUND,

    /** Present and permitted, but unreadable. §97 `IMPORT_ERROR`. */
    UNREADABLE,
}

/**
 * Outcome of a successful relink.
 *
 * The two cases are reported separately rather than merged, and this is a deliberate refusal
 * to legislate. §82.1 specifies the relink *mechanism* and does not say whether relinking to
 * different bytes is permitted — a user who re-encoded their track has a legitimate reason to,
 * and a user who picked the wrong file from a crowded folder has not. The registry surfaces
 * the distinction and lets the caller decide; both caches are content-addressed, so a changed
 * hash re-keys them either way and nothing stale can be served.
 */
public sealed interface RelinkResult {
    public val ref: AssetRef

    /** The new file's bytes hash identically to the old. Every cache entry stays valid. */
    public data class ExactMatch(override val ref: AssetRef) : RelinkResult

    /**
     * The new file is readable but different. Not an error — but the caller should confirm
     * this was intended, because analysis will be recomputed against different audio.
     */
    public data class ContentChanged(
        override val ref: AssetRef,
        public val previousHash: AssetHash,
    ) : RelinkResult
}
