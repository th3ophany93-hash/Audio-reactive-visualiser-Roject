package com.arvs.audio.cache

import com.arvs.core.model.AnalysisCacheKey
import com.arvs.core.model.AnalysisStage
import com.arvs.core.model.FeatureId
import com.arvs.core.time.AnalysisFraming
import com.arvs.core.time.AudioSourceTime
import java.util.concurrent.atomic.AtomicLongArray

/**
 * One published `(assetHash, analysisConfigHash)` entry, in memory.
 *
 * ### §18.1's concurrency contract, concretely
 *
 * Feature arrays are written **once**, before the stage's marker is stored, and are never mutated
 * afterwards. Readers acquire-load the marker before touching any array. The marker store is
 * therefore the single linearization point (§17.7 clause 5): a reader that observes a published
 * marker is guaranteed by the memory model to observe every array write behind it.
 *
 * That is what makes reads lock-free and non-blocking — there is no lock to contend for, because
 * there is nothing to protect. Immutability does the work a lock would otherwise do.
 *
 * [trackPeakEnergy] is §17.6 [T-7]'s reference, carried as immutable metadata per §17.7 and never
 * updated after construction.
 */
public class AnalysisCacheEntry(
    public val key: AnalysisCacheKey,
    public val framing: AnalysisFraming,
    public val frameCount: Long,
    /** §17.6 [T-7]'s track reference. Immutable cache metadata (§17.7 clause 1). */
    public val trackPeakEnergy: Float,
    /** Source properties, preserved per §17.3. */
    public val sourceSampleRateHz: Int,
    public val sourceChannelCount: Int,
) {
    private val features = LinkedHashMap<FeatureId, FloatArray>()
    private val featureStage = LinkedHashMap<FeatureId, AnalysisStage>()

    /**
     * Per-stage markers (§17.7 clause 5) — one slot per [AnalysisStage], `AtomicLongArray` giving
     * each the volatile read/write semantics the publication protocol needs.
     */
    private val markers = AtomicLongArray(AnalysisStage.entries.size).also { array ->
        for (index in 0 until array.length()) array.set(index, StageProgress.NOT_PUBLISHED)
    }

    /**
     * Stages a feature's array **before** publication. Not visible to readers until [publish].
     *
     * Deliberately not called "add": nothing is added to what a reader can see. §17.7 clause 2
     * requires all of a stage's features to become visible in one transition, so writing and
     * publishing are separate operations.
     */
    public fun stageFeature(stage: AnalysisStage, featureId: FeatureId, values: FloatArray) {
        require(values.size.toLong() == frameCount) {
            "feature $featureId has ${values.size} frames, entry has $frameCount"
        }
        check(markers.get(stage.ordinal) == StageProgress.NOT_PUBLISHED) {
            "${stage.name} is already published; §18.1 is immutable-once-written and §17.7 " +
                "clause 7 forbids revising a published scalar"
        }
        features[featureId] = values
        featureStage[featureId] = stage
    }

    /**
     * Publishes [stage] atomically — §17.7 clause 2 and clause 5.
     *
     * The marker moves from `NOT_PUBLISHED` straight to `frameCount − 1` in a single store, never
     * through an intermediate value.
     */
    public fun publishAtomic(stage: AnalysisStage) {
        require(stage.publishesAtomically) {
            "${stage.name} does not publish atomically (§17.7 clause 4); use advanceTo instead"
        }
        check(markers.get(stage.ordinal) == StageProgress.NOT_PUBLISHED) {
            "${stage.name} is already published; republishing would revise it (§17.7 clause 7)"
        }
        // Last write, and the only one a reader synchronises on.
        markers.set(stage.ordinal, frameCount - 1)
    }

    /** Advances an incrementally-published stage (§17.7 clause 4). Monotonic; never rewinds. */
    public fun advanceTo(stage: AnalysisStage, highestCompleteIndex: Long) {
        require(!stage.publishesAtomically) {
            "${stage.name} publishes atomically (§17.7 clause 4); use publishAtomic instead"
        }
        require(highestCompleteIndex in 0 until frameCount) {
            "index $highestCompleteIndex outside 0 until $frameCount"
        }
        val current = markers.get(stage.ordinal)
        require(highestCompleteIndex > current) {
            "the marker is monotonic: cannot move from $current back to $highestCompleteIndex"
        }
        markers.set(stage.ordinal, highestCompleteIndex)
    }

    public fun progressOf(stage: AnalysisStage): StageProgress =
        StageProgress(stage, markers.get(stage.ordinal), frameCount)

    public fun isPublished(stage: AnalysisStage): Boolean =
        markers.get(stage.ordinal) > StageProgress.NOT_PUBLISHED

    /** Features staged so far, with the stage that owns each. For persistence and diagnostics. */
    public fun stagedFeatures(): Map<FeatureId, AnalysisStage> = LinkedHashMap(featureStage)

    internal fun rawValues(featureId: FeatureId): FloatArray? = features[featureId]

    /**
     * Reads [featureId] at [time], applying §17.2's linear interpolation.
     *
     * Wait-free: one volatile marker read, then plain array reads. Nothing here can block, which
     * is §18.1's binding requirement for the render thread.
     */
    public fun read(featureId: FeatureId, time: AudioSourceTime): FeatureRead {
        val stage = featureStage[featureId] ?: return FeatureRead.NotAnalyzed
        val marker = markers.get(stage.ordinal)          // acquire
        if (marker == StageProgress.NOT_PUBLISHED) return FeatureRead.NotAnalyzed
        val values = features[featureId] ?: return FeatureRead.NotAnalyzed
        if (values.isEmpty()) return FeatureRead.NotAnalyzed

        val interpolation = framing.interpolationAt(time)
        val lastFrame = (values.size - 1).toLong()

        // §17.2 interpolates between two *adjacent stored* samples. At the final frame there is
        // no next one, so the upper index is clamped to it and the blend degenerates to that
        // sample. Without this clamp, a fully published atomic stage reports Pending at its own
        // last frame — the marker is `lastFrame`, but the requested upper index is `lastFrame+1`.
        val lower = interpolation.lowerFrame.coerceIn(0L, lastFrame)
        val upper = interpolation.upperFrame.coerceIn(0L, lastFrame)

        if (lower > marker) {
            // Nothing at or before this timestamp has been analysed yet. §18.1's fallback is the
            // nearest available sample — never a stall, never a fabricated value.
            return FeatureRead.Pending(values[marker.toInt()], marker)
        }
        if (upper > marker) {
            // The lower sample exists but its neighbour does not; the nearest available is lower.
            return FeatureRead.Pending(values[lower.toInt()], lower)
        }

        return FeatureRead.Available(interpolation.blend(values[lower.toInt()], values[upper.toInt()]))
    }
}
