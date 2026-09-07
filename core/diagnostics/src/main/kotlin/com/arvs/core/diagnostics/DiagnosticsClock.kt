package com.arvs.core.diagnostics

/**
 * The clock diagnostics measures with.
 *
 * Deliberately **not** `core:time`'s [com.arvs.core.time.TimeSpan] /
 * [com.arvs.core.time.AudioSourceTime]. Those types model *media* time — positions inside
 * an audio asset or a timeline — and §9.1 fixes their epoch at `t = 0` of the raw asset.
 * Wall-clock and elapsed-CPU time have nothing to do with that epoch, and letting the two
 * meet in one type is exactly the conflation `core:time` splits into separate types to
 * prevent. A span that took 12 ms to compute is not "12 ms into the track".
 *
 * Injected rather than reached for, so timing and rate-limiting behaviour is deterministic
 * under test (§157.1's rule applied to time). Production wiring uses [SystemDiagnosticsClock].
 */
public interface DiagnosticsClock {
    /**
     * A monotonically non-decreasing nanosecond counter, for **durations only**.
     *
     * Has no defined zero point and no relationship to wall time; only differences are
     * meaningful. Monotonic because a wall-clock adjustment mid-span (NTP, timezone, user)
     * would otherwise produce negative or wildly inflated durations.
     */
    public fun monotonicNanos(): Long

    /** Wall-clock milliseconds since the Unix epoch, for **timestamps only**. */
    public fun wallTimeMillis(): Long
}

/** The production clock. */
public object SystemDiagnosticsClock : DiagnosticsClock {
    override fun monotonicNanos(): Long = System.nanoTime()
    override fun wallTimeMillis(): Long = System.currentTimeMillis()
}

/**
 * A manually advanced clock for tests.
 *
 * Lives in `main`, not `test`, so that every module downstream of diagnostics can write
 * deterministic tests against rate limiting and timing spans without each one re-inventing
 * a fake. A test that has to invent its own clock usually ends up sleeping instead.
 */
public class ManualDiagnosticsClock(
    private var monotonicNanos: Long = 0L,
    private var wallTimeMillis: Long = 0L,
) : DiagnosticsClock {
    override fun monotonicNanos(): Long = monotonicNanos
    override fun wallTimeMillis(): Long = wallTimeMillis

    /** Advances both the monotonic counter and wall time by [nanos]. */
    public fun advanceNanos(nanos: Long) {
        require(nanos >= 0) { "a monotonic clock cannot move backwards: $nanos" }
        monotonicNanos += nanos
        wallTimeMillis += nanos / 1_000_000L
    }

    public fun advanceMillis(millis: Long): Unit = advanceNanos(millis * 1_000_000L)
}
