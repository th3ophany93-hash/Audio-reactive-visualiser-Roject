package com.arvs.core.model

/**
 * The asset identity types of MASTER_SPECIFICATION_v3.0 §82 / §82.1, and §10's binding
 * `AssetRef` schema.
 *
 * **Why these live in `core:model` and not `core:assets`.** §10 puts `assets: [AssetRef]`
 * inside `Project`, and `core:project` — which owns `ProjectState` — has no edge to
 * `core:assets` in §116.1's enforced graph. So the *data* has to sit here while the
 * *registry behaviour* sits in `core:assets`. That is not an accident of layering: it is the
 * module graph enforcing §82's actual point, which is that a project references assets by
 * stable identity and knows nothing about how they are resolved.
 */

/**
 * A persistable reference to where an asset lives — in practice a SAF document URI.
 *
 * Held as a string rather than `android.net.Uri` on purpose. This value is written into the
 * project file (Phase 9, §81) and read back on another day, possibly another OS version; a
 * platform object has no business in a portable document. Keeping it a string also leaves
 * `core:model` free of Android, which §116.1 requires.
 *
 * §82: *"Never depend permanently on raw filesystem paths."* This is a resolvable reference,
 * and [AssetRef.id] — not this — is what the rest of the project refers to.
 */
@JvmInline
public value class AssetUri(public val value: String) {
    init { require(value.isNotBlank()) { "AssetUri must not be blank" } }
    override fun toString(): String = value
}

/**
 * §82's `type` field.
 *
 * Declared as the full set even though v1 imports only [AUDIO] (§1, §15: exactly one audio
 * asset per project). §82 names `dimensions` among an asset's properties and §34A anticipates
 * video proxies, so the other two are reserved rather than invented later — same reasoning as
 * §97's error taxonomy: a closed set forces a deliberate decision instead of a new string.
 */
public enum class AssetType { AUDIO, IMAGE, VIDEO }

/**
 * §10's binding `AssetRef {id, uri, type, hash, persistedPermission}`, extended by §82.1
 * with `lastKnownHash`.
 *
 * **On [hash] versus [lastKnownHash].** They are equal at import and stay equal for the
 * ordinary life of an asset. They exist separately because §82.1's relink flow needs to
 * answer "is the file the user just picked the same audio I analysed?", and answering it
 * requires remembering what was there before. [hash] is the identity every content-addressed
 * cache is keyed on (§27.2, §18.1); [lastKnownHash] is the hash observed at the most recent
 * successful validation, which is what a relink is checked against.
 *
 * Note that §10 declares its schema "complete and normative" while §82.1 says `AssetRef`
 * *additionally* tracks `lastKnownHash` — a contradiction flagged for ratification rather
 * than resolved here. Modelling both fields satisfies §82.1's mechanism without removing
 * anything §10 requires.
 */
public data class AssetRef(
    /** Stable, project-local identity. The only thing the rest of the project refers to (§82). */
    public val id: AssetId,
    public val uri: AssetUri,
    public val type: AssetType,
    /** SHA-256 of the asset's bytes. The §27.2 cache identity — never [id]. */
    public val hash: AssetHash,
    /** §82.1's `persistedPermissionTaken`; §10 names the same field `persistedPermission`. */
    public val persistedPermission: Boolean,
    /** §82.1: the hash seen at the last successful validation. Equals [hash] at import. */
    public val lastKnownHash: AssetHash = hash,
    /** Human-readable name for display and diagnostics. Never used for identity. */
    public val displayName: String? = null,
) {
    /**
     * Whether the content has changed since the reference was last validated.
     *
     * True only after a relink to different bytes. Because both caches are content-addressed,
     * a changed hash re-keys them automatically — nothing needs to be invalidated by hand.
     */
    public val contentChangedSinceLastValidation: Boolean get() = hash != lastKnownHash
}
