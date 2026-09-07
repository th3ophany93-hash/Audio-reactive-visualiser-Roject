package com.arvs.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the rate-limiter behaviour §99 depends on, including its memory bound. */
class LogRateLimiterTest {

    private fun limiter(
        clock: ManualDiagnosticsClock,
        max: Int = 3,
        windowNanos: Long = 1_000_000_000L,
        maxKeys: Int = 256,
    ) = LogRateLimiter(RateLimitPolicy(max, windowNanos, maxKeys), clock)

    @Test
    fun `admits up to the budget then suppresses within a window`() {
        val clock = ManualDiagnosticsClock()
        val limiter = limiter(clock)

        repeat(3) { assertTrue(limiter.acquire("k") is RateLimitDecision.Allow) }
        assertEquals(RateLimitDecision.Suppress, limiter.acquire("k"))
    }

    @Test
    fun `the budget refreshes when the window elapses`() {
        val clock = ManualDiagnosticsClock()
        val limiter = limiter(clock)

        repeat(3) { limiter.acquire("k") }
        assertEquals(RateLimitDecision.Suppress, limiter.acquire("k"))

        clock.advanceNanos(1_000_000_000L)
        assertTrue(limiter.acquire("k") is RateLimitDecision.Allow)
    }

    @Test
    fun `the window does not refresh one nanosecond early`() {
        val clock = ManualDiagnosticsClock()
        val limiter = limiter(clock)

        repeat(3) { limiter.acquire("k") }
        clock.advanceNanos(999_999_999L)
        assertEquals(RateLimitDecision.Suppress, limiter.acquire("k"))
    }

    @Test
    fun `the suppressed count rides on the next admitted event and then resets`() {
        val clock = ManualDiagnosticsClock()
        val limiter = limiter(clock)

        repeat(3) { limiter.acquire("k") }
        repeat(7) { limiter.acquire("k") } // suppressed

        clock.advanceNanos(1_000_000_000L)
        assertEquals(7, (limiter.acquire("k") as RateLimitDecision.Allow).suppressedSinceLastAdmitted)
        // Not double-reported on the following event.
        assertEquals(0, (limiter.acquire("k") as RateLimitDecision.Allow).suppressedSinceLastAdmitted)
    }

    @Test
    fun `keys are limited independently`() {
        val clock = ManualDiagnosticsClock()
        val limiter = limiter(clock)

        repeat(10) { limiter.acquire("noisy") }
        assertTrue(limiter.acquire("other") is RateLimitDecision.Allow)
    }

    @Test
    fun `tracked keys are bounded, so the limiter cannot leak`() {
        // A limiter whose job is production hygiene must not itself grow without bound.
        val clock = ManualDiagnosticsClock()
        val limiter = limiter(clock, maxKeys = 16)

        repeat(1_000) { index -> limiter.acquire("key-$index") }

        assertEquals(16, limiter.trackedKeyCount())
    }

    @Test
    fun `a zero budget is rejected at construction`() {
        // Would suppress the first report of a novel failure — worse than no limiter.
        assertThrows(IllegalArgumentException::class.java) {
            RateLimitPolicy(maxEventsPerWindow = 0, windowNanos = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RateLimitPolicy(maxEventsPerWindow = 1, windowNanos = 0)
        }
    }

    @Test
    fun `the production policy is five per key per second`() {
        assertEquals(5, RateLimitPolicy.PRODUCTION.maxEventsPerWindow)
        assertEquals(1_000_000_000L, RateLimitPolicy.PRODUCTION.windowNanos)
    }
}
