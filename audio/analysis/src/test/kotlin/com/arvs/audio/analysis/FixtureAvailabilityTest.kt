package com.arvs.audio.analysis

import com.arvs.testing.audio.Fixtures
import com.arvs.testing.audio.SignalGenerators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies ratified decision **D-2**: `audio:analysis` reaches §119's fixtures from its test
 * configuration, and only from there.
 *
 * This is the module the exception exists for. Step 8's DSP tests and the §9.1 golden vectors
 * are all defined against the §119 catalogue, and before D-2 the guard treated
 * `testImplementation` exactly like `implementation` — so `:testing:audio` compiled but no
 * module could consume it.
 *
 * The half of D-2 that cannot be tested from inside a test — that a *production*
 * configuration may not acquire this edge — is enforced by the guard in the root build script
 * and negative-tested there.
 *
 * Deliberately not DSP: no analysis stage is implemented yet. This asserts reachability and
 * §17.3's downmix, which `core:model` already owns.
 */
class FixtureAvailabilityTest {

    @Test
    fun `the section 119 catalogue is reachable from this module's tests`() {
        val fixtures = Fixtures.all(frames = 480)
        assertTrue(fixtures.isNotEmpty())
        assertTrue(fixtures.all { it.frameCount > 0 })
    }

    @Test
    fun `the stereo fixture downmixes to the ratified canonical mono signal`() {
        // §17.3 makes this fixture mandatory and states exactly what it must prove:
        // L/R-differing input produces 0.5*L + 0.5*R, repeatably.
        val stereo = Fixtures.stereo(frames = 1_000)
        val buffer = stereo.toPcmBuffer()
        assertEquals(2, buffer.channelCount)

        val mono = buffer.toCanonicalMono()
        assertEquals(1_000, mono.size)

        for (frame in 0 until 1_000) {
            val left = stereo.samples[frame * 2]
            val right = stereo.samples[frame * 2 + 1]
            assertEquals(0.5f * left + 0.5f * right, mono[frame], 1e-7f)
        }
    }

    @Test
    fun `the stereo downmix is not merely one channel passed through`() {
        // The reason §17.3 requires differing channels: an implementation that drops a
        // channel would satisfy a fixture whose channels were identical.
        val stereo = Fixtures.stereo(frames = 1_000)
        val mono = stereo.toPcmBuffer().toCanonicalMono()

        val left = FloatArray(1_000) { stereo.samples[it * 2] }
        val right = FloatArray(1_000) { stereo.samples[it * 2 + 1] }

        assertTrue("downmix must differ from left", (0 until 1_000).any { abs(mono[it] - left[it]) > 1e-4f })
        assertTrue("downmix must differ from right", (0 until 1_000).any { abs(mono[it] - right[it]) > 1e-4f })
    }

    @Test
    fun `fixtures are deterministic across independent generations`() {
        // What every golden vector depends on.
        assertEquals(Fixtures.whiteNoise(480), Fixtures.whiteNoise(480))
        assertNotEquals(Fixtures.whiteNoise(480), Fixtures.sine440(480))
        assertTrue(
            SignalGenerators.impulse(480).contentEquals(SignalGenerators.impulse(480)),
        )
    }
}
