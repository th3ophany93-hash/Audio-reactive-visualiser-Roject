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
    const val FORMAT_VERSION: Int = 1

    private const val MAX_FEATURES = 256
    private const val MAX_FRAMES = 1L shl 32

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
            val stages = staged.values.distinct()
            out.writeInt(stages.size)
            stages.forEach { stage ->
                out.writeUTF(stage.name)
                out.writeLong(entry.progressOf(stage).highestCompleteIndex)
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
