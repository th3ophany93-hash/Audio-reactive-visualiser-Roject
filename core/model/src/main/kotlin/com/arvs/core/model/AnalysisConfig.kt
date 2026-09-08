package com.arvs.core.model

import com.arvs.core.time.AnalysisFraming
import java.security.MessageDigest

/**
 * Everything that determines the *numbers* an analysis run produces — and nothing that
 * merely determines how those numbers are later consumed.
 *
 * This type is the executable form of MASTER_SPECIFICATION_v3.0 §18.2, whose governing test
 * is: *if changing an input changes a number stored in the cache, it is in the hash; if it
 * only changes how an already-stored number is consumed, it is not.*
 *
 * Getting the membership wrong is expensive in both directions, which is why §18.2 states
 * it normatively and why [AnalysisConfigMembershipTest] pins it:
 *  - too broad → a slider nudge triggers a full re-analysis of the track;
 *  - too narrow → stale cache entries are served against changed audio, which is exactly
 *    the correctness defect §27.2's cache-key rules exist to prevent.
 *
 * **Deliberately absent** (§18.2's normative exclusions): every §23 reactive/mapping
 * parameter (gain, offset, curve, threshold, deadZone, attack, release, smoothing, invert,
 * falloff, clamp, combineOp, beatMultiplier, phaseOffset); master Sensitivity and master
 * Smoothing (§106); user-defined custom bands (§20); trim in/out, gain, fades and mute
 * (§16); preview quality and render settings (§86); and keyframes (§41).
 */
public data class AnalysisConfig(
    /** §18.2 items 1, 3, 5, 6 — canonical sample rate, FFT size, hop, frame rate (§17.5). */
    public val framing: AnalysisFraming = AnalysisFraming.CANONICAL,
    /** §18.2 item 2 — channel policy (§17.3). */
    public val channelPolicy: ChannelPolicy = ChannelPolicy.CANONICAL_MONO,
    /** §18.2 item 4 — FFT window function. */
    public val windowFunction: WindowFunction = WindowFunction.HANN,
    /** §18.2 item 7 — the **default** band set, i.e. the bands whose values are stored. */
    public val defaultBands: List<FrequencyBand> = FrequencyBand.DEFAULTS,
    /** §18.2 item 8 — normalisation algorithm and configuration (§19). */
    public val normalization: NormalizationConfig = NormalizationConfig(),
    /** §18.2 item 9 — beat-analysis configuration (§21). */
    public val beat: BeatConfig = BeatConfig(),
    /** §18.2 item 10 — analysis-quality level (§106). */
    public val quality: AnalysisQuality = AnalysisQuality.STANDARD,
    /**
     * §18.2 item 11 — analysis algorithm/schema version.
     *
     * Covers resampler coefficients, window-function implementation, and any DSP change that
     * alters output for identical input. Bumping this is how a corrected algorithm
     * invalidates every entry it would otherwise silently disagree with.
     */
    public val algorithmVersion: Int = ANALYSIS_ALGORITHM_VERSION,
) {
    /**
     * The exact bytes that are hashed.
     *
     * Written by hand rather than derived from `hashCode()` or a serialisation library
     * because this hash is a **persistent identity**: it names files on disk that must still
     * be found after an app update, a Kotlin upgrade, or a JVM change. `hashCode()` offers no
     * such stability guarantee, and a silently-changed hash would orphan every cache entry
     * on the device — invisibly, and only for users who already had a cache.
     *
     * Field order and formatting are therefore part of the contract. Locale-independent
     * formatting matters for the same reason: a device set to a locale using decimal commas
     * must produce byte-identical output.
     */
    public fun canonicalForm(): String = buildString {
        appendField("analysisAlgorithmVersion", algorithmVersion.toString())
        appendField("canonicalSampleRateHz", framing.sampleRateHz.toString())
        appendField("channelPolicy", channelPolicy.name)
        appendField("fftSizeSamples", framing.windowSamples.toString())
        appendField("fftWindowFunction", windowFunction.name)
        appendField("analysisHopSamples", framing.hopSamples.toString())
        appendField("analysisFrameRateHz", formatDouble(framing.frameRateHz))
        appendField(
            "defaultBands",
            defaultBands.joinToString(";") { band ->
                "${band.name}:${formatDouble(band.lowHz)}-${formatDouble(band.highHz)}"
            },
        )
        // §17.6 [T-8] / §18.2 item 8: no loudness target participates in the hash.
        appendField("normalization", "enabled=${normalization.enabled}")
        appendField(
            "beat",
            "enabled=${beat.enabled}," +
                "minTempoBpm=${formatDouble(beat.minTempoBpm)}," +
                "maxTempoBpm=${formatDouble(beat.maxTempoBpm)}",
        )
        appendField("analysisQuality", quality.name)
    }

    /** SHA-256 of [canonicalForm], lowercase hex. Half of the §18.1 cache key. */
    public fun hash(): AnalysisConfigHash {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalForm().toByteArray(Charsets.UTF_8))
        return AnalysisConfigHash(digest.joinToString("") { byte -> "%02x".format(byte) })
    }

    /** Builds the full §18.1 cache key for an asset. */
    public fun cacheKeyFor(assetHash: AssetHash): AnalysisCacheKey =
        AnalysisCacheKey(assetHash = assetHash, analysisConfigHash = hash())

    public companion object {
        /**
         * Bump whenever the DSP changes such that identical input yields different output.
         *
         * Version 1 is the initial ratified pipeline: §17.3's canonical mono at 48 kHz, and
         * §17.5's 2048-sample window with a 480-sample hop.
         */
        public const val ANALYSIS_ALGORITHM_VERSION: Int = 1

        /**
         * The field names that make up the hash, in order — the §18.2 inclusion list.
         *
         * Exposed so the membership can be asserted by test rather than reviewed by eye.
         */
        public val HASHED_FIELD_NAMES: List<String> = listOf(
            "analysisAlgorithmVersion",
            "canonicalSampleRateHz",
            "channelPolicy",
            "fftSizeSamples",
            "fftWindowFunction",
            "analysisHopSamples",
            "analysisFrameRateHz",
            "defaultBands",
            "normalization",
            "beat",
            "analysisQuality",
        )
    }
}

private fun StringBuilder.appendField(name: String, value: String) {
    append(name).append('=').append(value).append('\n')
}

/** Locale-independent, fixed-precision formatting, so the hash is stable across devices. */
private fun formatDouble(value: Double): String = String.format(java.util.Locale.ROOT, "%.6f", value)
