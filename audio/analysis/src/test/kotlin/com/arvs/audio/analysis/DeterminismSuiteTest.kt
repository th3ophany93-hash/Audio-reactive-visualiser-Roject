package com.arvs.audio.analysis

import com.arvs.audio.cache.AnalysisCacheEntry
import com.arvs.audio.cache.AudioAnalysisCache
import com.arvs.audio.cache.FeatureRead
import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.LoggingPolicy
import com.arvs.core.diagnostics.ManualDiagnosticsClock
import com.arvs.core.model.AnalysisConfig
import com.arvs.core.model.AnalysisStage
import com.arvs.core.model.AssetHash
import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.FeatureId
import com.arvs.core.time.AnalysisFraming
import com.arvs.core.time.AudioSourceTime
import com.arvs.core.time.TimeSpan
import com.arvs.core.time.TrimMapping
import com.arvs.testing.audio.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * The §9.1 determinism suite — plan §12's table, made executable.
 *
 * §9.1's contract is that rendering the frame at time T is a pure function of
 * `(ProjectState, T, AudioAnalysisCache[0..T])` and "never a function of how many times a frame
 * has been requested, playback order, wall-clock render invocation count, or any other
 * ambient/ephemeral counter". Phase 3 inherits that contract; it is only as strong as what is
 * proven here.
 */
class DeterminismSuiteTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock())
    private val assetHash = AssetHash("a".repeat(64))
    private val config = AnalysisConfig()

    private fun analyse(samples: FloatArray): ScalarEnvelope = ScalarEnvelope.compute(
        AnalysisFrames(
            CanonicalSignal.ofCanonicalMono(
                samples,
                AudioFormatInfo(48_000, 1, TimeSpan(samples.size.toLong() * 1_000_000L / 48_000)),
            ),
        ),
    )

    private fun entryFrom(envelope: ScalarEnvelope): AnalysisCacheEntry {
        val entry = AnalysisCacheEntry(
            key = config.cacheKeyFor(assetHash),
            framing = AnalysisFraming.CANONICAL,
            frameCount = envelope.frameCount.toLong(),
            trackPeakEnergy = envelope.trackPeakEnergy,
            sourceSampleRateHz = 48_000,
            sourceChannelCount = 1,
        )
        entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.RMS, envelope.rms)
        entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.PEAK, envelope.peak)
        entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.ENERGY, envelope.energy)
        entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.NORMALIZED_ENERGY, envelope.normalizedEnergy)
        entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.LOUDNESS, envelope.loudnessDb)
        entry.publishAtomic(AnalysisStage.SCALAR_ENVELOPE)
        return entry
    }

    // 1 — Repeat-analysis identity -------------------------------------------------------

    @Test
    fun `analysing the same PCM twice produces bit-identical cache bytes`() {
        val samples = Fixtures.drums(96_000).samples

        val first = temporaryFolder.newFolder("first")
        val second = temporaryFolder.newFolder("second")
        AudioAnalysisCache(first, logger).persist(entryFrom(analyse(samples)))
        AudioAnalysisCache(second, logger).persist(entryFrom(analyse(samples)))

        val a = first.walkTopDown().first { it.isFile }.readBytes()
        val b = second.walkTopDown().first { it.isFile }.readBytes()
        assertTrue("cache bytes must be identical across runs", a.contentEquals(b))
    }

    // 2 — Order independence ---------------------------------------------------------------

    @Test
    fun `reading timestamps in random order equals reading them ascending`() {
        // §9.1: playback order cannot matter.
        val entry = entryFrom(analyse(Fixtures.drums(96_000).samples))
        val times = (0 until 190).map { AudioSourceTime(it * 10_000L + 4_321) }

        val ascending = times.map { (entry.read(FeatureId.RMS, it) as FeatureRead.Available).value }
        val shuffled = times.shuffled(Random(20260908))
        val outOfOrder = shuffled.associateWith { (entry.read(FeatureId.RMS, it) as FeatureRead.Available).value }

        times.forEachIndexed { index, time ->
            assertEquals("at $time", ascending[index], outOfOrder.getValue(time))
        }
    }

    @Test
    fun `repeated reads of the same timestamp never drift`() {
        // Rules out any hidden per-read state — §9.1's "render invocation count" clause.
        val entry = entryFrom(analyse(Fixtures.whiteNoise(48_000).samples))
        val time = AudioSourceTime(333_333)
        val first = (entry.read(FeatureId.RMS, time) as FeatureRead.Available).value
        repeat(1_000) {
            assertEquals(first, (entry.read(FeatureId.RMS, time) as FeatureRead.Available).value)
        }
    }

    // 3 — Cache-path equivalence -------------------------------------------------------------

    @Test
    fun `values read back from disk equal a fresh analysis exactly`() {
        val envelope = analyse(Fixtures.drums(96_000).samples)
        val cache = AudioAnalysisCache(temporaryFolder.root, logger)
        cache.persist(entryFrom(envelope))

        val reloaded = AudioAnalysisCache(temporaryFolder.root, logger).entry(config.cacheKeyFor(assetHash))!!

        assertEquals(envelope.trackPeakEnergy, reloaded.trackPeakEnergy)
        (0 until envelope.frameCount).forEach { frame ->
            val time = AudioSourceTime(frame * 10_000L)
            assertEquals(
                "frame $frame",
                envelope.rms[frame],
                (reloaded.read(FeatureId.RMS, time) as FeatureRead.Available).value,
            )
            assertEquals(
                envelope.normalizedEnergy[frame],
                (reloaded.read(FeatureId.NORMALIZED_ENERGY, time) as FeatureRead.Available).value,
            )
            assertEquals(
                envelope.loudnessDb[frame],
                (reloaded.read(FeatureId.LOUDNESS, time) as FeatureRead.Available).value,
            )
        }
    }

    // 4 — Trim independence -------------------------------------------------------------------

    @Test
    fun `changing trim alters no cached value and no cache key`() {
        // §9.1's ratified consequence: the epoch is t=0 of the raw asset, so trim changes the
        // offset used to query, never anything stored. This is what makes real-time trim-handle
        // dragging affordable (§16).
        val envelope = analyse(Fixtures.drums(96_000).samples)
        val entry = entryFrom(envelope)
        val keyBefore = config.cacheKeyFor(assetHash)

        val trims = listOf(
            TrimMapping(AudioSourceTime.ofSeconds(0.0), AudioSourceTime.ofSeconds(2.0)),
            TrimMapping(AudioSourceTime.ofSeconds(0.5), AudioSourceTime.ofSeconds(1.5)),
            TrimMapping(AudioSourceTime.ofSeconds(1.0), AudioSourceTime.ofSeconds(2.0)),
        )

        trims.forEach { trim ->
            // The cache key does not mention trim at all (§18.2's normative exclusion).
            assertEquals(keyBefore, config.cacheKeyFor(assetHash))
            // And a given audio-source time reads the same whatever the trim is.
            (0 until 150).forEach { frame ->
                val audioTime = AudioSourceTime(frame * 10_000L)
                assertEquals(
                    "trim ${trim.trimIn} frame $frame",
                    envelope.rms[frame],
                    (entry.read(FeatureId.RMS, audioTime) as FeatureRead.Available).value,
                )
            }
        }
    }

    @Test
    fun `the same timeline time under different trims reads different audio, as it must`() {
        // The complement of the previous test: trim genuinely changes *which* audio-source time a
        // timeline time maps to. If it did not, the two domains would be interchangeable and the
        // trim-independence test above would be vacuous.
        val envelope = analyse(Fixtures.drums(96_000).samples)
        val entry = entryFrom(envelope)

        val early = TrimMapping(AudioSourceTime.ofSeconds(0.0), AudioSourceTime.ofSeconds(2.0))
        val late = TrimMapping(AudioSourceTime.ofSeconds(1.0), AudioSourceTime.ofSeconds(2.0))
        val timelineTime = com.arvs.core.time.TimelineTime(500_000)

        val fromEarly = entry.read(FeatureId.RMS, early.toAudioTime(timelineTime))
        val fromLate = entry.read(FeatureId.RMS, late.toAudioTime(timelineTime))

        assertNotEquals(fromEarly, fromLate)
    }

    // 5 — Interpolation determinism -----------------------------------------------------------

    @Test
    fun `arbitrary-timestamp reads follow the linear rule and are repeatable`() {
        val envelope = analyse(Fixtures.drums(48_000).samples)
        val entry = entryFrom(envelope)

        // Halfway between frames 30 and 31.
        val halfway = AudioSourceTime(305_000)
        val expected = (envelope.rms[30] + envelope.rms[31]) / 2.0f
        val read = (entry.read(FeatureId.RMS, halfway) as FeatureRead.Available).value
        assertEquals(expected.toDouble(), read.toDouble(), 1e-6)

        repeat(100) {
            assertEquals(read, (entry.read(FeatureId.RMS, halfway) as FeatureRead.Available).value)
        }
    }

    @Test
    fun `interpolation is exact on frame boundaries`() {
        val envelope = analyse(Fixtures.drums(48_000).samples)
        val entry = entryFrom(envelope)

        (0 until envelope.frameCount).forEach { frame ->
            assertEquals(
                envelope.rms[frame],
                (entry.read(FeatureId.RMS, AudioSourceTime(frame * 10_000L)) as FeatureRead.Available).value,
            )
        }
    }

    // 6 — Epoch stability -----------------------------------------------------------------------

    @Test
    fun `feature values are identical regardless of where reading started`() {
        // §9.1: never a function of playback order or of how many frames preceded this one.
        val entry = entryFrom(analyse(Fixtures.drums(96_000).samples))
        val target = AudioSourceTime(1_234_567)
        val expected = (entry.read(FeatureId.RMS, target) as FeatureRead.Available).value

        // Each pass performs a different sequence of reads *before* reading the target — from
        // earlier, from later, and in a scattered order. §9.1 requires the target's value to be
        // untouched by any of it.
        //
        // An earlier version of this test walked in 10 ms steps and stopped at 1 230 000 without
        // ever reading the target, so it compared two different timestamps; and for a start point
        // beyond the target the loop never ran at all. The preceding reads are now clearly a
        // preamble, and the assertion is on the target itself.
        val preambles: List<List<Long>> = listOf(
            emptyList(),
            (0L until 1_234_567L step 10_000L).toList(),
            (1_900_000L downTo 1_240_000L step 10_000L).toList(),
            List(500) { Random(it).nextLong(0L, 1_900_000L) },
        )

        preambles.forEachIndexed { index, preamble ->
            preamble.forEach { entry.read(FeatureId.RMS, AudioSourceTime(it)) }
            val actual = (entry.read(FeatureId.RMS, target) as FeatureRead.Available).value
            assertEquals("after preamble $index (${preamble.size} reads)", expected, actual)
        }
    }

    // 7 — Cross-fixture sweep -------------------------------------------------------------------

    @Test
    fun `every section 119 fixture round-trips through the cache unchanged`() {
        Fixtures.all(frames = 9_600).forEachIndexed { index, fixture ->
            val canonical = CanonicalSignal.from(fixture.toPcmBuffer(), fixture.format)
            val envelope = ScalarEnvelope.compute(AnalysisFrames(canonical))
            val folder = temporaryFolder.newFolder("fixture-$index")

            val hash = AssetHash("%064x".format(index))
            val entry = AnalysisCacheEntry(
                key = config.cacheKeyFor(hash),
                framing = AnalysisFraming.CANONICAL,
                frameCount = envelope.frameCount.toLong(),
                trackPeakEnergy = envelope.trackPeakEnergy,
                sourceSampleRateHz = fixture.sampleRateHz,
                sourceChannelCount = fixture.channelCount,
            )
            entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.RMS, envelope.rms)
            entry.stageFeature(AnalysisStage.SCALAR_ENVELOPE, FeatureId.LOUDNESS, envelope.loudnessDb)
            entry.publishAtomic(AnalysisStage.SCALAR_ENVELOPE)

            AudioAnalysisCache(folder, logger).persist(entry)
            val reloaded = AudioAnalysisCache(folder, logger).entry(config.cacheKeyFor(hash))!!

            (0 until envelope.frameCount).forEach { frame ->
                val time = AudioSourceTime(frame * 10_000L)
                assertEquals(
                    "${fixture.name} frame $frame",
                    envelope.rms[frame],
                    (reloaded.read(FeatureId.RMS, time) as FeatureRead.Available).value,
                )
            }
        }
    }
}
