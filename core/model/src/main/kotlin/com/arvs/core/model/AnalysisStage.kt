package com.arvs.core.model

/**
 * §17.1's mandatory, priority-ordered analysis stages.
 *
 * §17.1 fixes this order and the reason for it: "The Trim screen must be usable within ~1
 * second of import using only stage (1)+(2); deeper analysis continues in the background."
 * Declaration order **is** the priority order, so a scheduler cannot get it backwards by
 * sorting on the wrong thing — the same reasoning as `RegenerationCost` in `core:diagnostics`.
 *
 * **Placed in `core:model`, not `audio:analysis`.** §116.1's graph forces it: `audio:cache`
 * needs the stage taxonomy for §17.7's per-stage completion markers, and `audio:cache` has no
 * edge to `audio:analysis` — the analyser depends on the cache, not the reverse. The same
 * reasoning that put `AssetRef` here in Step 4.
 *
 * Only [WAVEFORM_PEAKS] and [SCALAR_ENVELOPE] are implemented. The rest are declared so the
 * ordering is complete and assertable now, and so a later stage cannot be added without
 * deciding where it belongs.
 */
public enum class AnalysisStage(public val displayName: String) {
    /**
     * Stage 1 — near-instant, feeds §16's Trim UI.
     *
     * Implemented in `core:assets` as tier 2 of §82.1's cache, not here: §82.1 assigns the
     * derived preview cache to the asset layer, and §116.1 makes `core:assets` and
     * `audio:analysis` deliberately disjoint. §17.1's stage numbering is a statement about
     * *scheduling priority*, not about which module owns the code.
     */
    WAVEFORM_PEAKS("Waveform peaks"),

    /** Stage 2 — RMS and peak envelope. */
    SCALAR_ENVELOPE("RMS / peak envelope"),

    /** Stage 3 — FFT magnitude, log spectrum, §20 frequency bands. Not implemented. */
    SPECTRUM("FFT spectrum and bands"),

    /** Stage 4 — onset, beat probability, beat phase, tempo (§21). Not implemented. */
    BEAT("Onset / beat / tempo"),

    /** Stage 5 — centroid, flux, rolloff, flatness, chroma. Highest cost. Not implemented. */
    SPECTRAL_DESCRIPTORS("Spectral descriptors and chroma"),
    ;

    /** Whether stage 1+2's "Trim screen usable within ~1 second" budget covers this stage. */
    public val isTrimCritical: Boolean get() = this == WAVEFORM_PEAKS || this == SCALAR_ENVELOPE

    /**
     * Whether this stage publishes **atomically** rather than frame by frame (§17.7 clause 4).
     *
     * The criterion is causality: a stage may publish incrementally if and only if every feature
     * it produces is computable from data at or before the frame being published. [SCALAR_ENVELOPE]
     * publishes atomically because §17.6 [T-7]'s Normalized Energy depends on a whole-track
     * reference. [WAVEFORM_PEAKS] is not stored in this cache at all — §82.1 assigns it to tier 2.
     *
     * Stages 3–5 are declared non-atomic provisionally; each is decided against the same criterion
     * when it is implemented. A stage-4 tempo estimate over a long window is the obvious candidate
     * to revisit.
     */
    public val publishesAtomically: Boolean get() = this == SCALAR_ENVELOPE

    public companion object {
        /** §17.1's order, most urgent first. */
        public val PRIORITY_ORDER: List<AnalysisStage> = entries.toList()

        /** The stages §17.1 requires before the Trim screen is usable. */
        public val TRIM_CRITICAL: List<AnalysisStage> = entries.filter { it.isTrimCritical }
    }
}
