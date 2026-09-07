package com.arvs.core.model

import java.security.MessageDigest
import java.util.Locale

/** Stable, project-local identifier for an asset (§82). **Not** a content hash. */
@JvmInline
public value class AssetId(public val value: String) {
    init { require(value.isNotBlank()) { "AssetId must not be blank" } }
    override fun toString(): String = value
}

/**
 * Content hash of an asset's bytes (§82's `hash` field).
 *
 * §27.2 and §18.1 are emphatic about the distinction from [AssetId]: `assetHash` is
 * obtained by resolving `audio.assetRef` **through the Asset Registry** to that asset's
 * `hash` field. The project-local id must never be used as, or in place of, the content
 * hash — doing so would defeat content-addressing, so the two are separate types and cannot
 * be swapped by mistake.
 */
@JvmInline
public value class AssetHash(public val hex: String) {
    init {
        require(hex.isNotBlank()) { "AssetHash must not be blank" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "AssetHash must be lowercase hex: '$hex'"
        }
    }
    override fun toString(): String = hex
}

/** Identifier for a project (§10). */
@JvmInline
public value class ProjectId(public val value: String) {
    init { require(value.isNotBlank()) { "ProjectId must not be blank" } }
    override fun toString(): String = value
}

/** Identity of an analysis configuration — the §18.2 hash. */
@JvmInline
public value class AnalysisConfigHash(public val hex: String) {
    init { require(hex.isNotBlank()) { "AnalysisConfigHash must not be blank" } }
    override fun toString(): String = hex
}

/** Names one cached analysis feature (§18). */
@JvmInline
public value class FeatureId(public val name: String) {
    init { require(name.isNotBlank()) { "FeatureId must not be blank" } }
    override fun toString(): String = name

    public companion object {
        public val RMS: FeatureId = FeatureId("rms")
        public val PEAK: FeatureId = FeatureId("peak")
        public val ENERGY: FeatureId = FeatureId("energy")
        public val NORMALIZED_ENERGY: FeatureId = FeatureId("normalizedEnergy")
        public val LOUDNESS: FeatureId = FeatureId("loudness")
        public val SPECTRUM: FeatureId = FeatureId("spectrum")
        public val SPECTRAL_CENTROID: FeatureId = FeatureId("spectralCentroid")
        public val SPECTRAL_FLUX: FeatureId = FeatureId("spectralFlux")
        public val SPECTRAL_ROLLOFF: FeatureId = FeatureId("spectralRolloff")
        public val SPECTRAL_FLATNESS: FeatureId = FeatureId("spectralFlatness")
        public val CHROMA: FeatureId = FeatureId("chroma")
        public val ONSET_STRENGTH: FeatureId = FeatureId("onsetStrength")
        public val BEAT_PROBABILITY: FeatureId = FeatureId("beatProbability")
        public val BEAT_PHASE: FeatureId = FeatureId("beatPhase")
        public val TEMPO: FeatureId = FeatureId("tempo")
    }
}

/**
 * Cache identity for an analysis run (§18.1): `(assetHash, analysisConfigHash)`.
 *
 * Content-addressed by construction, which is what makes the cache disposable and safely
 * evictable (§18.3): a changed asset or a changed configuration simply produces a different
 * key, so a stale entry is never *found*, let alone served.
 */
public data class AnalysisCacheKey(
    public val assetHash: AssetHash,
    public val analysisConfigHash: AnalysisConfigHash,
) {
    /** Relative path used on disk (§18.3): `<assetHash>/<analysisConfigHash>.acache`. */
    public fun relativePath(): String = "${assetHash.hex}/${analysisConfigHash.hex}.acache"
}

/** How multi-channel input is reduced to the canonical analysis signal (§17.3). */
public enum class ChannelPolicy {
    /**
     * §17.3: the canonical mono analysis signal. Stereo is `0.5·L + 0.5·R`; mono passes
     * through; sources with more than two channels are downmixed by equal-weight averaging.
     *
     * This is an *analysis* domain only. §17.3 is explicit that playback (§14.1, §16) and
     * any future export (§136) use the original source audio, never this signal.
     */
    CANONICAL_MONO,
}

/** FFT analysis window function. Part of analysis identity (§18.2 item 4). */
public enum class WindowFunction {
    HANN,
    HAMMING,
    BLACKMAN_HARRIS,
}

/** Analysis quality tier (§106 "Analysis quality"). Part of analysis identity (§18.2 item 10). */
public enum class AnalysisQuality {
    STANDARD,
    HIGH,
}

/** A frequency band, in Hz (§20). */
public data class FrequencyBand(
    public val name: String,
    public val lowHz: Double,
    public val highHz: Double,
) {
    init {
        require(name.isNotBlank()) { "band name must not be blank" }
        require(lowHz >= 0.0) { "lowHz must not be negative: $lowHz" }
        require(highHz > lowHz) { "highHz ($highHz) must exceed lowHz ($lowHz)" }
    }

    public companion object {
        /**
         * §20's default band set — the bands whose values are **stored**, and therefore part
         * of analysis identity (§18.2 item 7).
         *
         * User-defined custom bands are deliberately *not* here and deliberately *not* in the
         * hash: §17.4 derives them from the retained spectrum at read time, so adding one
         * changes no stored value. §18.2 calls including them "the single most user-visible
         * way to get this wrong" — it would make every new band a full re-analysis.
         */
        public val DEFAULTS: List<FrequencyBand> = listOf(
            FrequencyBand("band_20_60", 20.0, 60.0),
            FrequencyBand("band_60_120", 60.0, 120.0),
            FrequencyBand("band_120_250", 120.0, 250.0),
            FrequencyBand("band_250_500", 250.0, 500.0),
            FrequencyBand("band_500_1000", 500.0, 1_000.0),
            FrequencyBand("band_1k_2k", 1_000.0, 2_000.0),
            FrequencyBand("band_2k_4k", 2_000.0, 4_000.0),
            FrequencyBand("band_4k_8k", 4_000.0, 8_000.0),
            FrequencyBand("band_8k_16k", 8_000.0, 16_000.0),
        )
    }
}

/** Loudness normalisation applied to the analysis signal (§19). §18.2 item 8. */
public data class NormalizationConfig(
    public val enabled: Boolean = true,
    public val targetLufs: Double = -23.0,
) {
    init { require(targetLufs < 0.0) { "targetLufs must be negative: $targetLufs" } }
}

/** Beat/onset detection configuration (§21). §18.2 item 9. */
public data class BeatConfig(
    public val enabled: Boolean = true,
    public val minTempoBpm: Double = 60.0,
    public val maxTempoBpm: Double = 200.0,
) {
    init {
        require(minTempoBpm > 0.0) { "minTempoBpm must be positive: $minTempoBpm" }
        require(maxTempoBpm > minTempoBpm) {
            "maxTempoBpm ($maxTempoBpm) must exceed minTempoBpm ($minTempoBpm)"
        }
    }
}
