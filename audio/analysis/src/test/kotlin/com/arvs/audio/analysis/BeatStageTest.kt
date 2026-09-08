package com.arvs.audio.analysis

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
        val onset = stage.onsetSignal(spectraOf(Fixtures.drums(48_000 * 8)))
        val beats = stage.detect(onset)
        assertTrue("no beats found", beats.isNotEmpty())

        val tempo = stage.estimateTempo(onset)
        assertTrue("tempo ${tempo.bpm} not near 120", tempo.isPresent && tempo.bpm in 110.0..130.0)
    }

    @Test
    fun `silence produces no beats at all`() {
        // Flux is identically zero, so median and MAD are zero, so the threshold is zero, and
        // `flux > threshold` is false everywhere. Nothing to hold a beat up.
        val beats = stage.detect(stage.onsetSignal(spectraOf(Fixtures.silence(48_000 * 2))))
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
        val tone = stage.detect(stage.onsetSignal(spectraOf(Fixtures.sine440(48_000 * 2, 0.5f))))
        val drums = stage.detect(stage.onsetSignal(spectraOf(Fixtures.drums(48_000 * 2))))
        assertTrue("no drum beats to compare against", drums.isNotEmpty())

        // Two regions are excluded, both for stated reasons rather than to make a number fit.
        //
        // The opening frames are a genuine attack — the signal does start, and that is a real
        // onset. The closing frames are §17.5's zero-padded tail: a frame whose window runs past
        // the end of the signal contains a hard truncation, which smears energy across the
        // spectrum and reads as a large positive flux. Measured here at 0.653 on frame 197 of
        // 200, against 9.8e-05 through the sustained portion.
        //
        // The tail artefact is a real, user-visible consequence of §17.5's framing meeting
        // §21.1's flux — a phantom beat at the end of every track — and it is recorded as
        // obligation T-16 rather than suppressed here, because excluding tail frames from beat
        // detection is a normative decision §21.1 does not make.
        val samples = 48_000L * 2
        val lastFullFrame = (samples - stage.framing.windowSamples) / stage.framing.hopSamples
        val sustained = tone.filter { it.frameIndex > 10 && it.frameIndex <= lastFullFrame }
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
        val spectra = spectraOf(Fixtures.drums(48_000 * 4))
        val beats = stage.detect(stage.onsetSignal(spectra))
        beats.forEach { beat ->
            assertTrue(beat.frameIndex in 0 until spectra.size.toLong())
            assertEquals(stage.framing.frameStartTime(beat.frameIndex), beat.timestamp)
        }
    }

    @Test
    fun `stage 4 is deterministic across runs`() {
        // §9.1, and §21.1's ban on randomisation and wall-clock dependence, end to end.
        val spectra = spectraOf(Fixtures.drums(48_000 * 4))
        val first = stage.detect(stage.onsetSignal(spectra))
        repeat(3) { assertEquals(first, BeatStage().detect(BeatStage().onsetSignal(spectra))) }
    }

    @Test
    fun `disabling beat analysis in the config disables the stage`() {
        val spectra = spectraOf(Fixtures.drums(48_000 * 4))
        val disabled = BeatStage(config = BeatConfig(enabled = false))
        assertTrue(disabled.detect(disabled.onsetSignal(spectra)).isEmpty())
    }

    @Test
    fun `an empty track produces an empty onset signal`() {
        assertEquals(0, stage.onsetSignal(emptyList()).size)
        assertTrue(stage.detect(FloatArray(0)).isEmpty())
    }
}
