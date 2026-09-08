package com.arvs.audio.cache

import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.Subsystem
import com.arvs.core.model.AnalysisCacheKey
import com.arvs.core.model.AssetHash
import com.arvs.core.model.FeatureId
import com.arvs.core.time.AudioSourceTime
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * §18's `AudioAnalysisCache` — tier 3 of §82.1's three tiers.
 *
 * Content-addressed by `(assetHash, analysisConfigHash)` (§18.1), stored outside the portable
 * project file, disposable and regenerable. Losing it costs time, never work (§18.3).
 *
 * ### What this type guarantees
 *
 * - **Reads never block** (§18.1). A read consults an in-memory [AnalysisCacheEntry] whose arrays
 *   are immutable after publication; there is no lock anywhere on the read path.
 * - **Publication is atomic on disk** — written to a temporary file and renamed — so a process
 *   killed mid-write leaves the previous entry or none, never a partial one (§17.7 clause 6).
 * - **An unknown `formatVersion` is rejected, deleted and regenerated** (§18.3), never partially
 *   parsed.
 * - **Nothing is visible before publication** (§17.7 clause 3): a read for an unknown key returns
 *   [FeatureRead.NotAnalyzed], and no partial file is ever written for a later run to find.
 */
public class AudioAnalysisCache(
    private val rootDirectory: File,
    logger: Logger,
    private val budget: CacheBudget = CacheBudget.DEFAULT,
    /**
     * Assets belonging to a currently-open project.
     *
     * §18.3: "The entry for any audio asset referenced by a currently-open project is never
     * evicted while that project is open." Supplied as a function rather than a set so the answer
     * is asked at eviction time, and so this module needs no edge to `core:project` — which
     * §116.1 does not grant it.
     */
    private val pinnedAssets: () -> Set<AssetHash> = { emptySet() },
) {
    private val log = logger.forSubsystem(Subsystem.ANALYZER)
    private val loaded = ConcurrentHashMap<AnalysisCacheKey, AnalysisCacheEntry>()

    /** The in-memory entry for [key], loading it from disk on first use. Null if there is none. */
    public fun entry(key: AnalysisCacheKey): AnalysisCacheEntry? {
        loaded[key]?.let { return it }
        val file = fileFor(key)
        if (!file.isFile) return null

        return try {
            val entry = AnalysisCacheFormat.read(file)
            touch(file)
            loaded.putIfAbsent(key, entry) ?: entry
        } catch (rejected: CacheFormatRejectedException) {
            // §18.3: reject outright, delete, regenerate. Never salvage.
            log.info(
                "cache-format-rejected",
                "Discarding analysis cache for ${key.assetHash.hex.take(12)}…: ${rejected.message}",
            )
            file.delete()
            null
        } catch (failure: IOException) {
            log.warn("cache-read-failed", "Could not read analysis cache: ${failure.message}")
            file.delete()
            null
        }
    }

    /**
     * Reads a feature at a timestamp.
     *
     * Returns [FeatureRead.NotAnalyzed] when there is no entry — §17.7 clause 3's distinction from
     * [FeatureRead.Pending], which has a nearest sample and this does not.
     */
    public fun read(key: AnalysisCacheKey, featureId: FeatureId, time: AudioSourceTime): FeatureRead =
        entry(key)?.read(featureId, time) ?: FeatureRead.NotAnalyzed

    /**
     * Persists [entry] atomically.
     *
     * Called after the stage has been published in memory. Writing an unpublished entry is
     * refused: it would put a file on disk that a later run would load as authoritative, which is
     * the durable form of the partial visibility §17.7 clause 3 forbids.
     */
    public fun persist(entry: AnalysisCacheEntry) {
        require(entry.stagedFeatures().values.distinct().any { entry.isPublished(it) }) {
            "refusing to persist an entry with no published stage (§17.7 clause 3)"
        }
        val target = fileFor(entry.key)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            AnalysisCacheFormat.write(temporary, entry)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    throw IOException("could not move the analysis cache into place at ${target.path}")
                }
            }
            loaded[entry.key] = entry
            log.info(
                "cache-published",
                "Published analysis cache for ${entry.key.assetHash.hex.take(12)}… " +
                    "(${entry.frameCount} frames)",
            )
        } finally {
            temporary.delete()
        }
        evictIfOverBudget()
    }

    /** Drops an entry. The cache is regenerable, so nothing is lost (§18.1, §18.3). */
    public fun invalidate(key: AnalysisCacheKey) {
        loaded.remove(key)
        fileFor(key).delete()
    }

    public fun sizeOnDiskBytes(): Long = entryFiles().sumOf { it.length() }

    /**
     * §18.3's deterministic eviction.
     *
     * LRU by last-access time, **whole entries only** — "partial eviction is forbidden: a
     * half-present entry would violate §18.1's immutability and completeness contract" — and never
     * an asset belonging to a currently-open project.
     */
    public fun evictIfOverBudget(): List<AnalysisCacheKey> = evictToFit(budget.bytes)

    /**
     * Evicts until the store fits in [targetBytes], returning what was removed.
     *
     * Separated from [evictIfOverBudget] so the policy is directly exercisable: §18.3's floor is
     * 256 MB, and a test that had to write a quarter of a gigabyte to observe an eviction would
     * be slow enough that nobody would run it.
     */
    internal fun evictToFit(targetBytes: Long): List<AnalysisCacheKey> {
        val files = entryFiles()
        var total = files.sumOf { it.length() }
        if (total <= targetBytes) return emptyList()

        val pinned = pinnedAssets()
        val evicted = mutableListOf<AnalysisCacheKey>()

        files
            .filter { keyOf(it)?.assetHash !in pinned }
            .sortedBy { it.lastModified() }   // least recently accessed first
            .forEach { file ->
                if (total <= targetBytes) return@forEach
                val key = keyOf(file) ?: return@forEach
                val size = file.length()
                if (file.delete()) {
                    loaded.remove(key)
                    total -= size
                    evicted += key
                }
            }

        if (evicted.isNotEmpty()) {
            log.info(
                "cache-evicted",
                "Evicted ${evicted.size} analysis cache entr(ies) to stay within " +
                    "${budget.bytes / (1024 * 1024)} MB",
            )
        }
        return evicted
    }

    internal fun fileFor(key: AnalysisCacheKey): File = File(rootDirectory, key.relativePath())

    private fun entryFiles(): List<File> =
        rootDirectory.walkTopDown().filter { it.isFile && it.name.endsWith(ENTRY_SUFFIX) }.toList()

    private fun keyOf(file: File): AnalysisCacheKey? {
        val assetHex = file.parentFile?.name ?: return null
        val configHex = file.name.removeSuffix(ENTRY_SUFFIX)
        return runCatching { AnalysisCacheKey(AssetHash(assetHex), com.arvs.core.model.AnalysisConfigHash(configHex)) }
            .getOrNull()
    }

    /**
     * Records an access for LRU ordering.
     *
     * Touches filesystem metadata, never the entry's content — §18.1's immutability governs what
     * the cache *holds*, and an access timestamp is not part of it. Done on load rather than on
     * every read, because reads are served from memory.
     */
    private fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    private companion object {
        const val ENTRY_SUFFIX = ".acache"
    }
}

/**
 * §18.3's bounded disk budget.
 *
 * "Default **1 GB**, user-configurable **256 MB – 8 GB**. Derived from §17.4's measured
 * ≈12.4 MB per track-minute: 1 GB ≈ 80 track-minutes ≈ 16 five-minute tracks."
 */
public data class CacheBudget(public val bytes: Long) {
    init {
        require(bytes in MINIMUM_BYTES..MAXIMUM_BYTES) {
            "cache budget must be within 256 MB – 8 GB (§18.3): $bytes bytes"
        }
    }

    public companion object {
        public const val MINIMUM_BYTES: Long = 256L * 1024 * 1024
        public const val MAXIMUM_BYTES: Long = 8L * 1024 * 1024 * 1024
        public const val DEFAULT_BYTES: Long = 1024L * 1024 * 1024

        public val DEFAULT: CacheBudget = CacheBudget(DEFAULT_BYTES)
    }
}
