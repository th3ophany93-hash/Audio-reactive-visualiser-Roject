package com.arvs.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the §9.1 / §14.1 time-domain contract.
 *
 * The property under test is the one §9.1 calls determinism: a value at a given time must
 * not depend on how the playhead got there. The type separation is the mechanism, and the
 * trim-independence tests below are the observable consequence.
 */
class TimeDomainsTest {

    @Test
    fun `toAudioTime applies exactly trimIn`() {
        // §14.1: audioT = T + trimIn. Stated as arithmetic so it cannot drift.
        val trim = TrimMapping(
            trimIn = AudioSourceTime.ofSeconds(30.0),
            trimOut = AudioSourceTime.ofSeconds(90.0),
        )

        assertEquals(
            AudioSourceTime.ofSeconds(30.0),
            trim.toAudioTime(TimelineTime.ZERO),
        )
        assertEquals(
            AudioSourceTime.ofSeconds(45.0),
            trim.toAudioTime(TimelineTime.ofSeconds(15.0)),
        )
    }

    @Test
    fun `timeline and audio-source conversions round-trip`() {
        val trim = TrimMapping(
            trimIn = AudioSourceTime.ofMillis(12_345),
            trimOut = AudioSourceTime.ofMillis(98_765),
        )
        for (millis in 0L until 5_000L step 137L) {
            val timelineTime = TimelineTime.ofMillis(millis)
            assertEquals(timelineTime, trim.toTimelineTime(trim.toAudioTime(timelineTime)))
        }
    }

    @Test
    fun `the epoch is the raw asset's t=0, not the trimmed timeline's`() {
        // §9.1's central rule. Timeline zero maps to trimIn, and only an untrimmed asset
        // has the two coincide.
        val trimmed = TrimMapping(
            trimIn = AudioSourceTime.ofSeconds(10.0),
            trimOut = AudioSourceTime.ofSeconds(20.0),
        )
        assertEquals(
            AudioSourceTime.ofSeconds(10.0),
            trimmed.toAudioTime(TimelineTime.ZERO),
        )

        val untrimmed = TrimMapping.full(TimeSpan.ofSeconds(20))
        assertEquals(AudioSourceTime.EPOCH, untrimmed.toAudioTime(TimelineTime.ZERO))
    }

    @Test
    fun `moving the trim handle changes the offset, never the audio-source instant`() {
        // §9.1's ratified consequence, and the reason trim is excluded from cache identity
        // (§18.2): the same musical moment keeps the same audio-source time regardless of
        // where the trim starts, so nothing cached is invalidated by dragging a handle.
        val musicalMoment = AudioSourceTime.ofSeconds(42.0)

        val earlyTrim = TrimMapping(AudioSourceTime.ofSeconds(10.0), AudioSourceTime.ofSeconds(60.0))
        val lateTrim = TrimMapping(AudioSourceTime.ofSeconds(35.0), AudioSourceTime.ofSeconds(60.0))

        // Same instant, different timeline positions...
        assertEquals(TimelineTime.ofSeconds(32.0), earlyTrim.toTimelineTime(musicalMoment))
        assertEquals(TimelineTime.ofSeconds(7.0), lateTrim.toTimelineTime(musicalMoment))

        // ...but it maps back to the identical audio-source instant either way.
        assertEquals(
            musicalMoment,
            earlyTrim.toAudioTime(earlyTrim.toTimelineTime(musicalMoment)),
        )
        assertEquals(
            musicalMoment,
            lateTrim.toAudioTime(lateTrim.toTimelineTime(musicalMoment)),
        )
    }

    @Test
    fun `trim duration is the selection length`() {
        val trim = TrimMapping(AudioSourceTime.ofSeconds(10.0), AudioSourceTime.ofSeconds(25.5))
        assertEquals(TimeSpan.ofMillis(15_500), trim.duration)
    }

    @Test
    fun `containment is half-open`() {
        val trim = TrimMapping(AudioSourceTime.ofSeconds(10.0), AudioSourceTime.ofSeconds(20.0))
        assertTrue(AudioSourceTime.ofSeconds(10.0) in trim)
        assertTrue(AudioSourceTime.ofSeconds(19.999) in trim)
        assertFalse(AudioSourceTime.ofSeconds(20.0) in trim) // exclusive upper bound
        assertFalse(AudioSourceTime.ofSeconds(9.999) in trim)
    }

    @Test
    fun `an inverted or empty trim range is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrimMapping(AudioSourceTime.ofSeconds(20.0), AudioSourceTime.ofSeconds(10.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrimMapping(AudioSourceTime.ofSeconds(10.0), AudioSourceTime.ofSeconds(10.0))
        }
    }

    @Test
    fun `spans arithmetic behaves`() {
        val start = AudioSourceTime.ofSeconds(5.0)
        val end = AudioSourceTime.ofSeconds(12.5)
        assertEquals(TimeSpan.ofMillis(7_500), end - start)
        assertEquals(end, start + TimeSpan.ofMillis(7_500))
        assertEquals(7.5, (end - start).seconds, 1e-9)
    }
}
