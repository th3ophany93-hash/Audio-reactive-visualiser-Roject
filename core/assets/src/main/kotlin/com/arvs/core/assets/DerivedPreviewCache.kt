package com.arvs.core.assets

import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.Subsystem
import com.arvs.core.diagnostics.TimingSpanRecorder
import com.arvs.core.model.ArvsError
import com.arvs.core.model.AssetHash
import com.arvs.core.model.ErrorCategory
import com.arvs.core.model.Outcome
import com.arvs.core.model.PcmSource
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Tier 2 of §82.1's three-tier cache: the **derived preview cache**.
 *
 * §82.1 requires the three tiers to have independent invalidation rules and to be "never
 * conflated into a single cache". Tier 2's rule, per the plan's cache table, is
 * `(assetHash, peakFormatVersion)` → *new asset or new peak format produces a new key*. It is
 * content-addressed like tier 3, so a changed asset re-keys automatically and nothing stale
 * can be found, let alone served.
 *
 * ### The §16 contract this type exists to satisfy
 *
 * §16 is binding: dragging a trim handle must be fed from these peaks, **never** by re-opening
 * the SAF-backed asset per redraw or scrub frame. [cachedPeaks] is that read path — it touches
 * only the cache directory and can never reach an [AssetStorage], because it holds none. That
 * is deliberate: the requirement is enforced by what this class is unable to do rather than by
 * a rule a future caller has to remember.
 *
 * ### Format version
 *
 * §18.3 fixes the discipline for the analysis cache and it applies here for the same reason: a
 * reader meeting an unknown or newer [PEAK_FORMAT_VERSION] **rejects the file outright,
 * deletes it and regenerates** — never a partial or best-effort parse. Peaks are cheap to
 * rebuild and a misparsed pyramid draws a confidently wrong waveform.
 *
 * ### Atomicity
 *
 * Writes go to a temporary file and are renamed into place. A process killed mid-write leaves
 * either the previous file or no file, never a half one — the same crash-safety the plan's
 * cache suite requires of tier 3.
 */
public class DerivedPreviewCache(
    private val rootDirectory: File,
    logger: Logger,
    private val builder: PeakBuilder = PeakBuilder(),
    private val timings: TimingSpanRecorder = TimingSpanRecorder(),
) {
    private val log = logger.forSubsystem(Subsystem.ASSETS)

    /**
     * Peaks for [assetHash] if they are already cached and readable.
     *
     * The §16 redraw path. Returns null rather than computing, because computing here would
     * put a decode on a scrub frame — exactly what §16 forbids.
     */
    public fun cachedPeaks(assetHash: AssetHash): WaveformPeaks? {
        val file = fileFor(assetHash)
        if (!file.isFile) return null
        return timings.record(Subsystem.ASSETS, "peaks-read") {
            try {
                PeakCacheFormat.read(file)
            } catch (rejected: PeakFormatRejectedException) {
                // §18.3's discipline: reject outright, delete, regenerate. Never salvage.
                log.info(
                    "peaks-format-rejected",
                    "Discarding waveform peaks for ${assetHash.hex.take(12)}…: ${rejected.message}",
                )
                file.delete()
                null
            } catch (failure: IOException) {
                log.warn("peaks-read-failed", "Could not read cached peaks: ${failure.message}")
                file.delete()
                null
            }
        }
    }

    /**
     * Returns cached peaks, or builds and stores them from [source].
     *
     * Called at import (§17.1's stage 1), not during interaction.
     */
    public suspend fun peaksFor(assetHash: AssetHash, source: PcmSource): Outcome<WaveformPeaks> {
        cachedPeaks(assetHash)?.let { return Outcome.success(it) }

        val span = timings.begin(Subsystem.ASSETS, "peaks-build")
        return try {
            val peaks = builder.build(source)
            store(assetHash, peaks)
            log.info(
                "peaks-built",
                "Built ${peaks.levels.size}-level waveform pyramid for ${assetHash.hex.take(12)}…",
            )
            Outcome.success(peaks)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Outcome.Failure(
                ArvsError(
                    category = if (failure is OutOfMemoryError) ErrorCategory.OUT_OF_MEMORY
                    else ErrorCategory.IMPORT_ERROR,
                    message = "Could not build a waveform for this track: " +
                        (failure.message ?: failure::class.java.simpleName),
                    recoveryHint = "Try importing the file again",
                    cause = failure as? Exception,
                ),
            )
        } finally {
            span.close()
        }
    }

    /** Writes [peaks] atomically. A crash leaves the old file or none, never a half one. */
    public fun store(assetHash: AssetHash, peaks: WaveformPeaks) {
        val target = fileFor(assetHash)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            PeakCacheFormat.write(temporary, peaks)
            if (!temporary.renameTo(target)) {
                // Rename can fail if the target exists on some filesystems; replacing is still
                // atomic from a reader's point of view because the delete and rename are both
                // metadata operations and a reader either finds the old file or none.
                target.delete()
                if (!temporary.renameTo(target)) {
                    throw IOException("could not move peaks into place at ${target.path}")
                }
            }
        } finally {
            temporary.delete()
        }
    }

    /** Drops the cached pyramid for [assetHash]. Peaks are regenerable; nothing is lost. */
    public fun invalidate(assetHash: AssetHash) {
        fileFor(assetHash).delete()
    }

    /** Bytes currently occupied by tier 2. Reported in the §98 diagnostic report. */
    public fun sizeOnDiskBytes(): Long =
        rootDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal fun fileFor(assetHash: AssetHash): File =
        File(rootDirectory, "${assetHash.hex}/${PeakCacheFormat.PEAK_FORMAT_VERSION}.peaks")

    public companion object {
        /** Exposed so callers can name the tier-2 key without reaching into the format. */
        public const val PEAK_FORMAT_VERSION: Int = PeakCacheFormat.PEAK_FORMAT_VERSION
    }
}

/** Raised when a cache file's version or magic does not match. Never recovered from (§18.3). */
internal class PeakFormatRejectedException(message: String) : Exception(message)

/**
 * The tier-2 on-disk format.
 *
 * Deliberately hand-written rather than delegating to a serialization library: this is a
 * persistent format whose layout must stay stable across app, Kotlin and JVM upgrades, and it
 * carries its own version so a change is a deliberate act rather than an incidental
 * consequence of a dependency bump. Exactly the reasoning behind `AnalysisConfig`'s
 * hand-written canonical form.
 */
internal object PeakCacheFormat {

    /** `ARVSPEAK` — checked before anything else, so a foreign file is rejected, not parsed. */
    const val MAGIC: Long = 0x415256535045414BL

    const val PEAK_FORMAT_VERSION: Int = 1

    fun write(file: File, peaks: WaveformPeaks) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeLong(MAGIC)
            out.writeInt(PEAK_FORMAT_VERSION)
            out.writeInt(peaks.sampleRateHz)
            out.writeLong(peaks.totalFrames)
            out.writeInt(peaks.levels.size)
            for (level in peaks.levels) {
                out.writeInt(level.framesPerBucket)
                out.writeInt(level.bucketCount)
                for (index in 0 until level.bucketCount) out.writeFloat(level.minimumAt(index))
                for (index in 0 until level.bucketCount) out.writeFloat(level.maximumAt(index))
            }
        }
    }

    fun read(file: File): WaveformPeaks {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = input.readLong()
            if (magic != MAGIC) {
                throw PeakFormatRejectedException("not a waveform peak file")
            }
            val version = input.readInt()
            if (version != PEAK_FORMAT_VERSION) {
                throw PeakFormatRejectedException(
                    "peak format version $version, expected $PEAK_FORMAT_VERSION",
                )
            }
            val sampleRateHz = input.readInt()
            val totalFrames = input.readLong()
            val levelCount = input.readInt()
            if (levelCount <= 0 || levelCount > MAX_LEVELS) {
                throw PeakFormatRejectedException("implausible level count $levelCount")
            }

            val levels = ArrayList<PeakLevel>(levelCount)
            repeat(levelCount) {
                val framesPerBucket = input.readInt()
                val bucketCount = input.readInt()
                if (framesPerBucket <= 0 || bucketCount < 0) {
                    throw PeakFormatRejectedException(
                        "implausible level: $framesPerBucket frames/bucket, $bucketCount buckets",
                    )
                }
                val minimums = FloatArray(bucketCount) { input.readFloat() }
                val maximums = FloatArray(bucketCount) { input.readFloat() }
                levels += PeakLevel(framesPerBucket, minimums, maximums)
            }
            return WaveformPeaks(sampleRateHz, totalFrames, levels)
        }
    }

    /** A sanity bound, so a corrupt count cannot make the reader allocate wildly. */
    private const val MAX_LEVELS = 32
}
