package com.arvs.audio.cache

import com.arvs.core.model.AnalysisCacheKey
import com.arvs.core.model.AnalysisConfigHash
import com.arvs.core.model.AnalysisStage
import com.arvs.core.model.AssetHash
import com.arvs.core.model.FeatureId
import com.arvs.core.time.AnalysisFraming
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The `.acache` on-disk format.
 *
 * Hand-written rather than delegated to a serialization library, for the reason §18.3 makes
 * binding: the layout is versioned, persistent, and must survive app, Kotlin and JVM upgrades. A
 * library's format changing under a dependency bump would silently invalidate every cache — the
 * same reasoning as `AnalysisConfig.canonicalForm()` and §82.1's tier-2 format.
 *
 * §18.3's rule is enforced literally: **a reader meeting an unknown or newer [FORMAT_VERSION]
 * rejects the file outright, deletes it, and regenerates — never a partial or best-effort parse.**
 *
 * The header carries §17.6 [T-7]'s track peak-energy reference as immutable metadata, per §17.7,
 * and the per-stage markers so a partially-analysed entry reloads with its progress intact.
 */
internal object AnalysisCacheFormat {

    /** `ARVSACHE`. Checked first, so a foreign file is rejected rather than parsed. */
    const val MAGIC: Long = 0x4152565341434845L

    /** §18.3's `formatVersion`, independent of `analysisConfigHash`. */
    /**
     * §18.3's `formatVersion`.
     *
     * | Version | Change |
     * |---|---|
     * | 1 | Initial layout. |
     * | 2 | §17.4's retained spectrum added (Step 10), stored as linear magnitudes. |
     * | 3 | §17.4.1 [D-7]: the spectrum payload is **dBFS**, not linear magnitude. |
     *
     * Version 3 is a **semantic** change to an unchanged layout, which is the dangerous kind: a
     * version-2 file parses perfectly as version 3 and yields magnitudes near −160 where the
     * reader expects values near 0…1. Nothing about the byte structure would catch it. The
     * version check is therefore the *only* thing standing between a stale file and silently
     * corrupt analysis, which is why §18.3's rule is enforced literally — an unknown or
     * non-matching version is rejected, deleted and regenerated, with no speculative migration.
     */
    const val FORMAT_VERSION: Int = 3

    private const val MAX_FEATURES = 256
    private const val MAX_FRAMES = 1L shl 32
    private const val MAX_SPECTRUM_BINS = 1 shl 16
    private const val MAX_SPECTRUM_VALUES = 1L shl 34

    fun write(file: File, entry: AnalysisCacheEntry) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeLong(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(entry.key.assetHash.hex)
            out.writeUTF(entry.key.analysisConfigHash.hex)

            out.writeInt(entry.framing.sampleRateHz)
            out.writeInt(entry.framing.windowSamples)
            out.writeInt(entry.framing.hopSamples)
            out.writeInt(entry.sourceSampleRateHz)
            out.writeInt(entry.sourceChannelCount)
            out.writeLong(entry.frameCount)
            // §17.6 [T-7] / §17.7: immutable cache metadata.
            out.writeFloat(entry.trackPeakEnergy)

            val staged = entry.stagedFeatures()
            // The spectrum's stage must be in the marker set even when it has no scalar features
            // of its own. Deriving the set from `stagedFeatures()` alone loses it: stage 3 stores
            // a spectrum and, on its own, no features — so its marker was never written, and the
            // reloaded entry treated a fully persisted spectrum as unpublished and refused to
            // read it back. Silent, and invisible to any test that did not persist and reload.
            val stages = (staged.values + listOfNotNull(entry.spectrumStageOrNull())).distinct()
            out.writeInt(stages.size)
            stages.forEach { stage ->
                out.writeUTF(stage.name)
                out.writeLong(entry.progressOf(stage).highestCompleteIndex)
            }

            // §17.4.1's retained spectrum, as raw binary16 bit patterns of dBFS values.
            val spectrum = entry.rawSpectrum()
            if (spectrum == null) {
                out.writeInt(0)
            } else {
                out.writeInt(entry.spectrumBinCount)
                out.writeUTF(entry.spectrumStageOrNull()!!.name)
                for (half in spectrum) out.writeShort(half.toInt())
            }

            out.writeInt(staged.size)
            staged.forEach { (featureId, stage) ->
                val values = entry.rawValues(featureId) ?: FloatArray(0)
                out.writeUTF(featureId.name)
                out.writeUTF(stage.name)
                out.writeInt(values.size)
                for (value in values) out.writeFloat(value)
            }
        }
    }

    fun read(file: File): AnalysisCacheEntry {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readLong() != MAGIC) {
                throw CacheFormatRejectedException("not an analysis cache file")
            }
            val version = input.readInt()
            if (version != FORMAT_VERSION) {
                throw CacheFormatRejectedException(
                    "cache format version $version, expected $FORMAT_VERSION",
                )
            }

            val assetHash = AssetHash(input.readUTF())
            val configHash = AnalysisConfigHash(input.readUTF())
            val framing = AnalysisFraming(
                sampleRateHz = input.readInt(),
                windowSamples = input.readInt(),
                hopSamples = input.readInt(),
            )
            val sourceRate = input.readInt()
            val sourceChannels = input.readInt()
            val frameCount = input.readLong()
            if (frameCount < 0 || frameCount > MAX_FRAMES) {
                throw CacheFormatRejectedException("implausible frame count $frameCount")
            }
            val trackPeakEnergy = input.readFloat()

            val entry = AnalysisCacheEntry(
                key = AnalysisCacheKey(assetHash, configHash),
                framing = framing,
                frameCount = frameCount,
                trackPeakEnergy = trackPeakEnergy,
                sourceSampleRateHz = sourceRate,
                sourceChannelCount = sourceChannels,
            )

            val stageCount = input.readInt()
            if (stageCount < 0 || stageCount > AnalysisStage.entries.size) {
                throw CacheFormatRejectedException("implausible stage count $stageCount")
            }
            val markers = LinkedHashMap<AnalysisStage, Long>()
            repeat(stageCount) {
                // The name is read into a local *before* the search. Calling readUTF() inside the
                // predicate would invoke it once per enum constant, consuming a field per
                // candidate and desynchronising the stream — which fails far downstream, as a
                // malformed string rather than as an unknown stage.
                val stageName = input.readUTF()
                val stage = AnalysisStage.entries.firstOrNull { it.name == stageName }
                    ?: throw CacheFormatRejectedException("unknown analysis stage '$stageName' in header")
                markers[stage] = input.readLong()
            }

            val spectrumBinCount = input.readInt()
            if (spectrumBinCount < 0 || spectrumBinCount > MAX_SPECTRUM_BINS) {
                throw CacheFormatRejectedException("implausible spectrum bin count $spectrumBinCount")
            }
            if (spectrumBinCount > 0) {
                val stageName = input.readUTF()
                val stage = AnalysisStage.entries.firstOrNull { it.name == stageName }
                    ?: throw CacheFormatRejectedException("unknown spectrum stage '$stageName'")
                val total = frameCount * spectrumBinCount
                if (total > MAX_SPECTRUM_VALUES) {
                    throw CacheFormatRejectedException("implausible spectrum size $total")
                }
                val halves = ShortArray(total.toInt()) { input.readShort() }
                entry.stageSpectrum(stage, spectrumBinCount, halves)
            }

            val featureCount = input.readInt()
            if (featureCount < 0 || featureCount > MAX_FEATURES) {
                throw CacheFormatRejectedException("implausible feature count $featureCount")
            }
            repeat(featureCount) {
                val featureId = FeatureId(input.readUTF())
                val stageName = input.readUTF()
                val stage = AnalysisStage.entries.firstOrNull { it.name == stageName }
                    ?: throw CacheFormatRejectedException(
                        "unknown analysis stage '$stageName' for feature ${featureId.name}",
                    )
                val size = input.readInt()
                if (size.toLong() != frameCount) {
                    throw CacheFormatRejectedException(
                        "feature ${featureId.name} has $size frames, header says $frameCount",
                    )
                }
                entry.stageFeature(stage, featureId, FloatArray(size) { input.readFloat() })
            }

            // Restore the markers last, exactly as publication does: data first, marker after.
            markers.forEach { (stage, index) ->
                if (index == StageProgress.NOT_PUBLISHED) return@forEach
                if (stage.publishesAtomically) {
                    if (index != frameCount - 1) {
                        // §17.7 clause 5: an atomic stage cannot have an intermediate marker. A
                        // file claiming one was not written by this format's rules.
                        throw CacheFormatRejectedException(
                            "${stage.name} publishes atomically but its marker is $index",
                        )
                    }
                    entry.publishAtomic(stage)
                } else {
                    entry.advanceTo(stage, index)
                }
            }
            return entry
        }
    }
}

/** Raised when a cache file's magic, version or structure is rejected (§18.3). Never recovered. */
internal class CacheFormatRejectedException(message: String) : Exception(message)
