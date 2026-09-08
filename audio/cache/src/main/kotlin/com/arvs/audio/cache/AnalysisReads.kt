package com.arvs.audio.cache

import com.arvs.core.model.AnalysisStage
import com.arvs.core.model.FeatureId

/**
 * The outcome of reading a feature at a timestamp — §18.1's read contract, refined by §17.7.
 *
 * §18.1 requires the render thread never to block: "If analysis for time T is not yet available,
 * the renderer uses the nearest available cached sample and flags the frame in Diagnostics (§98)
 * as 'analysis pending'." §17.7 clause 3 then separates two situations that sentence conflates.
 */
public sealed interface FeatureRead {

    /** The value at the requested timestamp, interpolated per §17.2. */
    public data class Available(public val value: Float) : FeatureRead

    /**
     * The entry exists, but analysis has not reached this timestamp.
     *
     * Carries §18.1's nearest-available-sample fallback, so the renderer can draw *something*
     * correct-for-an-earlier-time rather than stall. [nearestFrame] is what §98 reports.
     */
    public data class Pending(
        public val nearestValue: Float,
        public val nearestFrame: Long,
    ) : FeatureRead

    /**
     * There is no entry for this `(assetHash, analysisConfigHash)` at all.
     *
     * Distinct from [Pending], and the distinction is not cosmetic: [Pending] has a nearest
     * sample to fall back to and this does not. A reader that treated them alike would fall back
     * to a sample that was never computed. §17.1's explicit "analyzing…" state is the correct
     * response here — never a value.
     */
    public data object NotAnalyzed : FeatureRead

    public val valueOrNull: Float?
        get() = when (this) {
            is Available -> value
            is Pending -> nearestValue
            NotAnalyzed -> null
        }
}

/**
 * A stage's publication state, backing §18.1's "highest-complete-index" marker as §17.7 refines it.
 */
public data class StageProgress(
    public val stage: AnalysisStage,
    /** Highest frame index complete, or [NOT_PUBLISHED]. */
    public val highestCompleteIndex: Long,
    public val frameCount: Long,
) {
    public val isPublished: Boolean get() = highestCompleteIndex > NOT_PUBLISHED
    public val isComplete: Boolean get() = frameCount > 0 && highestCompleteIndex == frameCount - 1

    init {
        require(highestCompleteIndex >= NOT_PUBLISHED) {
            "highestCompleteIndex must be >= $NOT_PUBLISHED: $highestCompleteIndex"
        }
        require(highestCompleteIndex < frameCount) {
            "highestCompleteIndex ($highestCompleteIndex) must be below frameCount ($frameCount)"
        }
        if (stage.publishesAtomically) {
            // §17.7 clause 5: an atomic stage's marker takes exactly two values and never an
            // intermediate one. Enforced at construction so an intermediate state is
            // unrepresentable rather than merely unexpected.
            require(highestCompleteIndex == NOT_PUBLISHED || highestCompleteIndex == frameCount - 1) {
                "${stage.name} publishes atomically (§17.7), so its marker may only be " +
                    "$NOT_PUBLISHED or ${frameCount - 1}, never $highestCompleteIndex"
            }
        }
    }

    public companion object {
        /** The marker's value before publication. */
        public const val NOT_PUBLISHED: Long = -1L
    }
}

/** Names the features a stage stores, so a reader can ask what an entry actually contains. */
public data class StageFeatures(
    public val stage: AnalysisStage,
    public val featureIds: List<FeatureId>,
)
