package com.arvs.audio.analysis

import com.arvs.audio.beat.BeatDetector
import com.arvs.core.model.BeatConfig
import com.arvs.testing.audio.AudioFixture
import com.arvs.testing.audio.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §17.1 stage 4 end-to-end, over the §119 fixtures. */
class BeatStageTest {

    private val spectrumStage = SpectrumStage()
    private val stage = BeatStage()

    /**
     * The real source length in canonical samples, which §21.1 [D-10] needs.
     *
     * `CanonicalSignal.frameCount` is the PCM sample count. Deriving it instead from the analysis
     * framing — `AnalysisFrames.frameCount × hop` — would round *up* to a whole number of hops
     * and overstate the source by up to `hop − 1` samples, which is exactly enough to make one
     * padded frame look eligible. The tail rule has to be fed the true length.
     */
    private fun sampleCountOf(fixture: AudioFixture): Long =
        CanonicalSignal.from(fixture.toPcmBuffer(), fixture.format).frameCount.toLong()

    /** Spectra → onset → beats for a whole fixture, with §21.1 [D-10]'s real source length. */
    private fun beatsOf(fixture: AudioFixture, using: BeatStage = stage) =
        using.detect(using.onsetSignal(spectraOf(fixture)), sampleCountOf(fixture))

    private fun spectraOf(fixture: AudioFixture): List<FloatArray> {
        val canonical = CanonicalSignal.from(fixture.toPcmBuffer(), fixture.format)
        val frames = AnalysisFrames(canonical)
        val scratch = spectrumStage.newScratch()
        val buffer = frames.newFrameBuffer()
        return (0 until frames.frameCount).map { index ->
            frames.readFrame(index, buffer)
            spectrumStage.newSpectrumBuffer().also { spectrumStage.spectrumOf(buffer, scratch, it) }
        }
    }

    @Test
    fun `the onset signal is the same flux stage 5 stores`() {
        // §21.1 defines the onset signal as half-wave rectified flux, which §17 already lists as
        // a stored feature. Computing it twice from two definitions is how the two drift apart.
        val spectra = spectraOf(Fixtures.drums(9_600))
        val onset = stage.onsetSignal(spectra)

        assertEquals(spectra.size, onset.size)
        assertEquals("frame 0 has no predecessor", 0.0f, onset[0])
        for (index in 1 until spectra.size) {
            assertEquals(
                "frame $index",
                SpectralDescriptors.flux(spectra[index], spectra[index - 1]),
                onset[index],
            )
        }
    }

    @Test
    fun `the onset signal is non-negative everywhere`() {
        // Half-wave rectification: energy appearing marks an attack, energy decaying does not.
        Fixtures.all(frames = 4_800).forEach { fixture ->
            stage.onsetSignal(spectraOf(fixture)).forEach {
                assertTrue("${fixture.name}: negative onset $it", it >= 0.0f)
            }
        }
    }

    @Test
    fun `a percussive fixture yields beats near its generated tempo`() {
        // §119's drums fixture is generated at 120 BPM, so the answer is the generator's own
        // parameter rather than a figure read off a run.
        val fixture = Fixtures.drums(48_000 * 8)
        val onset = stage.onsetSignal(spectraOf(fixture))
        val beats = stage.detect(onset, sampleCountOf(fixture))
        assertTrue("no beats found", beats.isNotEmpty())

        val tempo = stage.estimateTempo(onset)
        assertTrue("tempo ${tempo.bpm} not near 120", tempo.isPresent && tempo.bpm in 110.0..130.0)
    }

    @Test
    fun `silence produces no beats at all`() {
        // Flux is identically zero, so median and MAD are zero, so the threshold is zero, and
        // `flux > threshold` is false everywhere. Nothing to hold a beat up.
        val beats = beatsOf(Fixtures.silence(48_000 * 2))
        assertTrue("silence produced ${beats.size} beats", beats.isEmpty())
    }

    @Test
    fun `a steady tone's residual beats are separated from real ones by confidence and strength`() {
        // §21.1's threshold is `median + k·MAD` — purely *relative*, with no absolute onset floor,
        // because §21.1 specifies none and inventing one would change every cached beat. A
        // sustained sine therefore still produces candidates: the hop is 480 samples, which is
        // 4.4 cycles at 440 Hz, so successive windows are not identical and leakage jitters by a
        // few parts in 10⁵. Against a window of values that small, some are outliers.
        //
        // That is what §21's confidence channel exists for — "when beatConfidence is below a
        // documented threshold for a sustained window … the resolved beat-driven modulator output
        // holds its last stable value". So the property to assert is not that the tone produces
        // nothing, but that what it produces is unmistakably separated from a real beat. Measured:
        // strength 9.81e-05 and confidence 0.011 for the tone, versus 4.16 and 1.000 for drums —
        // a factor of ~42 000 in strength.
        val tone = beatsOf(Fixtures.sine440(48_000 * 2, 0.5f))
        val drums = beatsOf(Fixtures.drums(48_000 * 2))
        assertTrue("no drum beats to compare against", drums.isNotEmpty())

        // Only the opening frames are excluded here, and for a stated reason: the signal does
        // start, and that is a genuine attack. The zero-padded tail no longer needs excluding by
        // hand — §21.1 [D-10] makes those frames ineligible, so the detector never sees them.
        val sustained = tone.filter { it.frameIndex > 10 }
        assertTrue("no sustained candidates to characterise", sustained.isNotEmpty())

        val loudestJitter = sustained.maxOf { it.strength }
        val quietestBeat = drums.minOf { it.strength }
        assertTrue(
            "jitter $loudestJitter is not clearly below a real beat $quietestBeat",
            loudestJitter * 1_000 < quietestBeat,
        )
        assertTrue(
            "jitter confidence ${sustained.maxOf { it.confidence }} is not low",
            sustained.all { it.confidence < 0.1f },
        )
        assertTrue(drums.all { it.confidence > 0.5f })
    }

    @Test
    fun `every beat lands on a frame the framing can address`() {
        val fixture = Fixtures.drums(48_000 * 4)
        val spectra = spectraOf(fixture)
        val beats = beatsOf(fixture)
        beats.forEach { beat ->
            assertTrue(beat.frameIndex in 0 until spectra.size.toLong())
            assertEquals(stage.framing.frameStartTime(beat.frameIndex), beat.timestamp)
        }
    }

    @Test
    fun `stage 4 is deterministic across runs`() {
        // §9.1, and §21.1's ban on randomisation and wall-clock dependence, end to end.
        val fixture = Fixtures.drums(48_000 * 4)
        val samples = sampleCountOf(fixture)
        val spectra = spectraOf(fixture)
        val first = stage.detect(stage.onsetSignal(spectra), samples)
        repeat(3) {
            assertEquals(first, BeatStage().detect(BeatStage().onsetSignal(spectra), samples))
        }
    }

    @Test
    fun `disabling beat analysis in the config disables the stage`() {
        val disabled = BeatStage(config = BeatConfig(enabled = false))
        assertTrue(beatsOf(Fixtures.drums(48_000 * 4), using = disabled).isEmpty())
    }

    @Test
    fun `no beat is reported in the zero-padded tail of a real fixture`() {
        // §21.1 [D-10] end to end, on the fixture that produced the measurement behind the
        // decision: a sustained sine whose final windows are zero-padded, giving flux 0.653 on
        // frame 197 of 200 against a 9.8e-05 baseline. The onset signal still contains that
        // spike — §17.5's padding is deliberately unchanged — but no beat may come from it.
        val fixture = Fixtures.sine440(48_000 * 2, amplitude = 0.5f)
        val samples = sampleCountOf(fixture)
        val onset = stage.onsetSignal(spectraOf(fixture))
        val lastEligible = BeatDetector.lastEligibleFrame(samples, stage.framing)

        // The artefact is present in the signal, and is the largest value in it.
        val artefactFrame = onset.indices.maxByOrNull { onset[it] }!!
        assertTrue(
            "expected the artefact past frame $lastEligible, found it at $artefactFrame",
            artefactFrame > lastEligible,
        )

        // And no beat comes from it, or from anywhere else in the tail.
        stage.detect(onset, samples).forEach {
            assertTrue("beat at ${it.frameIndex} is past $lastEligible", it.frameIndex <= lastEligible)
        }
    }

    @Test
    fun `beats in a percussive track are unaffected by the tail rule`() {
        // Clause 6 at the stage level: suppressing the tail must not cost a real beat. Every beat
        // found with the true source length must also have been found without the rule.
        val fixture = Fixtures.drums(48_000 * 4)
        val samples = sampleCountOf(fixture)
        val onset = stage.onsetSignal(spectraOf(fixture))
        val lastEligible = BeatDetector.lastEligibleFrame(samples, stage.framing)

        val guarded = stage.detect(onset, samples).map { it.frameIndex }
        val unguarded = BeatDetector
            .detect(onset, (onset.size - 1L) * stage.framing.hopSamples + stage.framing.windowSamples)
            .map { it.frameIndex }

        assertEquals(unguarded.filter { it <= lastEligible }, guarded)
    }

    @Test
    fun `an empty track produces an empty onset signal`() {
        assertEquals(0, stage.onsetSignal(emptyList()).size)
        assertTrue(stage.detect(FloatArray(0), 0).isEmpty())
    }
}
