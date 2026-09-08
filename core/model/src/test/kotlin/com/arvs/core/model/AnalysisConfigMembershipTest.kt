package com.arvs.core.model

import com.arvs.core.time.AnalysisFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards §18.2's normative `analysisConfigHash` membership.
 *
 * §18.2 exists because getting this wrong is costly in both directions — too broad and a
 * slider nudge re-analyses the whole track, too narrow and stale entries are served against
 * changed audio. Neither failure is loud, so the membership is asserted here rather than
 * left to review.
 *
 * The exclusion side is tested structurally: reactive parameters, master sensitivity and
 * smoothing, custom bands, trim, and keyframes cannot alter the hash because they are not
 * fields of [AnalysisConfig] at all. [hashed fields are exactly the §18.2 inclusion list]
 * is what stops one being added later.
 *
 * ---
 * **CARRIED-FORWARD TEST OBLIGATION — required before this file is next modified.**
 *
 * The structural argument above is sound but implicit: it holds only because nobody has yet
 * added an excluded concept as a field of [AnalysisConfig]. The inclusion half is asserted
 * directly; the exclusion half is not asserted at all, so the day someone adds
 * `masterSensitivity` to the config the inclusion test fails with a message about the
 * inclusion list rather than about the §18.2 exclusion it actually violates.
 *
 * Required addition: an explicit exclusion-half test that enumerates §18.2's named
 * exclusions — reactive mapping parameters, master sensitivity, master smoothing, custom
 * (non-default) band definitions, trim points, keyframes — and asserts that none of them
 * appears among [AnalysisConfig]'s declared properties, with the failure message naming
 * §18.2's exclusion rule. Recorded as a standing obligation by the project owner
 * (Phase 1, Step 3); see PHASE_1_IMPLEMENTATION_PLAN.md §18.
 * ---
 */
class AnalysisConfigMembershipTest {

    @Test
    fun `hashed fields are exactly the §18-2 inclusion list`() {
        // Fails if a field is added to or removed from the hash without a deliberate
        // decision — including, critically, if a consumption-side parameter is added.
        val fieldsInCanonicalForm = AnalysisConfig().canonicalForm()
            .lines()
            .filter { it.isNotBlank() }
            .map { it.substringBefore('=') }

        assertEquals(AnalysisConfig.HASHED_FIELD_NAMES, fieldsInCanonicalForm)
    }

    @Test
    fun `identical configurations hash identically`() {
        assertEquals(AnalysisConfig().hash(), AnalysisConfig().hash())
    }

    @Test
    fun `the hash is stable across repeated computation`() {
        // Persistent identity: this hash names files on disk that must still be found after
        // an app update. Nothing incidental — object identity, iteration order — may leak in.
        val config = AnalysisConfig()
        val first = config.hash()
        repeat(50) { assertEquals(first, config.hash()) }
    }

    // --- Every §18.2 inclusion must change the hash. ------------------------------------

    @Test
    fun `changing the canonical sample rate changes the hash`() {
        assertHashChanges(AnalysisConfig(framing = AnalysisFraming(sampleRateHz = 44_100)))
    }

    @Test
    fun `changing the FFT size changes the hash`() {
        assertHashChanges(AnalysisConfig(framing = AnalysisFraming(windowSamples = 4_096)))
    }

    @Test
    fun `changing the analysis hop changes the hash`() {
        // U-21's ratified value. A hop change alters every stored frame, so it must
        // invalidate — and it is a distinct input from FFT size and frame rate (§18.2 item 5).
        assertHashChanges(AnalysisConfig(framing = AnalysisFraming(hopSamples = 1_024)))
    }

    @Test
    fun `changing the window function changes the hash`() {
        assertHashChanges(AnalysisConfig(windowFunction = WindowFunction.BLACKMAN_HARRIS))
    }

    @Test
    fun `changing the default band set changes the hash`() {
        val extraBand = FrequencyBand.DEFAULTS + FrequencyBand("band_16k_20k", 16_000.0, 20_000.0)
        assertHashChanges(AnalysisConfig(defaultBands = extraBand))
    }

    @Test
    fun `changing normalization changes the hash`() {
        assertHashChanges(AnalysisConfig(normalization = NormalizationConfig(enabled = false)))
    }

    @Test
    fun `no loudness target participates in the hash`() {
        // §17.6 [T-8] and §18.2 item 8: the Loudness Approximation's parameters are fixed by
        // the specification, not configured, and no target level exists. A `targetLufs` field
        // was carried here in an earlier draft and was never in the specification; this pins
        // its absence so it cannot return by accident.
        val canonicalForm = AnalysisConfig().canonicalForm()
        assertTrue(
            "no loudness target may appear in the canonical form: $canonicalForm",
            !canonicalForm.contains("Lufs", ignoreCase = true) &&
                !canonicalForm.contains("target", ignoreCase = true),
        )
        assertEquals("enabled=true", canonicalForm.lines().first { it.startsWith("normalization=") }.substringAfter('='))
    }

    @Test
    fun `changing beat configuration changes the hash`() {
        // §21.1 [D-8] makes all five detector values normative *and* hash members: each decides
        // which frames are reported as beats, so §18.2's governing test puts them in. Every one
        // is asserted — a field that silently fell out of `canonicalForm()` would let two
        // genuinely different analyses share a cache key, which is the exact correctness defect
        // §27.2's cache-key rules exist to prevent.
        //
        // Values must differ from the ratified defaults (40 / 240 / 1.0 / 1.5 / 100) or the test
        // asserts nothing: an earlier version used maxTempoBpm = 240.0, which stopped being a
        // change the moment §21.1 ratified 240 as the default.
        assertHashChanges(AnalysisConfig(beat = BeatConfig(enabled = false)))
        assertHashChanges(AnalysisConfig(beat = BeatConfig(minTempoBpm = 50.0)))
        assertHashChanges(AnalysisConfig(beat = BeatConfig(maxTempoBpm = 200.0)))
        assertHashChanges(AnalysisConfig(beat = BeatConfig(thresholdWindowSeconds = 2.0)))
        assertHashChanges(AnalysisConfig(beat = BeatConfig(madMultiplier = 2.5)))
        assertHashChanges(AnalysisConfig(beat = BeatConfig(refractoryMs = 150.0)))
    }

    @Test
    fun `the ratified beat defaults are the ones that get hashed`() {
        // Pins the values themselves, not just that changing them matters. A silent edit to a
        // default would change every cache key on the device without any test noticing.
        val beat = AnalysisConfig().canonicalForm().lines()
            .first { it.startsWith("beat=") }.substringAfter('=')
        assertEquals(
            "enabled=true,minTempoBpm=40.000000,maxTempoBpm=240.000000," +
                "thresholdWindowSeconds=1.000000,madMultiplier=1.500000,refractoryMs=100.000000",
            beat,
        )
    }

    @Test
    fun `changing analysis quality changes the hash`() {
        assertHashChanges(AnalysisConfig(quality = AnalysisQuality.HIGH))
    }

    @Test
    fun `bumping the algorithm version changes the hash`() {
        // The escape hatch that lets a corrected DSP invalidate everything it would
        // otherwise silently disagree with (§18.2 item 11).
        assertHashChanges(AnalysisConfig(algorithmVersion = AnalysisConfig.ANALYSIS_ALGORITHM_VERSION + 1))
    }

    // --- Cache key composition (§18.1, §27.2). -----------------------------------------

    @Test
    fun `cache key combines asset hash with config hash`() {
        val config = AnalysisConfig()
        val assetHash = AssetHash("a".repeat(64))
        val key = config.cacheKeyFor(assetHash)

        assertEquals(assetHash, key.assetHash)
        assertEquals(config.hash(), key.analysisConfigHash)
        assertEquals("${assetHash.hex}/${config.hash().hex}.acache", key.relativePath())
    }

    @Test
    fun `different assets with identical config get different cache keys`() {
        val config = AnalysisConfig()
        val first = config.cacheKeyFor(AssetHash("a".repeat(64)))
        val second = config.cacheKeyFor(AssetHash("b".repeat(64)))
        assertNotEquals(first, second)
        assertNotEquals(first.relativePath(), second.relativePath())
    }

    @Test
    fun `asset id is not usable as an asset hash`() {
        // §27.2 forbids using the project-local assetRef in place of the content hash.
        // The type system is what enforces it; this documents the intent and rejects
        // a non-hex value that a project-local id would typically be.
        val rejected = runCatching { AssetHash("asset-42") }
        assertTrue("non-hex asset hash must be rejected", rejected.isFailure)
    }

    @Test
    fun `hash is lowercase hex of the expected length`() {
        val hex = AnalysisConfig().hash().hex
        assertEquals(64, hex.length) // SHA-256
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun assertHashChanges(modified: AnalysisConfig) {
        assertNotEquals(
            "configuration change must invalidate the analysis cache (§18.2)",
            AnalysisConfig().hash(),
            modified.hash(),
        )
    }
}
