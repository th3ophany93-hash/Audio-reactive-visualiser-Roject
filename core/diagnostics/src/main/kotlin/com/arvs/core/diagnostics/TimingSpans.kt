package com.arvs.core.diagnostics

import java.util.concurrent.ConcurrentHashMap

/**
 * §100.1's named timing spans and the per-subsystem breakdown they feed.
 *
 * §100.1 is explicit about *why* this exists, and it is worth restating because it shapes
 * the API: CPU time must be attributed **per subsystem**, shown as separate bars, "not a
 * single aggregate CPU time number" — so that a regression is diagnosed against the correct
 * subsystem rather than "fixed" by degrading the wrong one. Accordingly [TimingBreakdown]
 * exposes [TimingBreakdown.bySubsystem] as its primary view; the grand total is available
 * but is not the headline.
 *
 * §100.1 also requires each subsystem to self-instrument "from day one". That is why this
 * lands in Phase 1 alongside the audio pipeline, and not later beside the renderer it will
 * eventually also measure.
 *
 * **Not encoded here:** §100.1's indicative split within the 16.67 ms budget (Reactive ≤2 ms,
 * RenderGraph ≤2 ms, GL submission ≤2 ms). Those numbers are provisional pending Appendix B
 * item **U-11**, which is OPEN and needs profiling data from a working renderer. Baking them
 * into code now would create a second, authoritative-looking source for an unresolved
 * decision. The mechanism is built; the thresholds are Phase 3+ once U-11 resolves.
 */
public class TimingSpanRecorder(private val clock: DiagnosticsClock = SystemDiagnosticsClock) {

    private val stats = ConcurrentHashMap<SpanId, Accumulator>()

    /**
     * Times [block], recording the elapsed nanoseconds against `(subsystem, name)`.
     *
     * Recorded in a `finally`, so a span whose body throws is still attributed. Losing the
     * measurement exactly when a stage fails would remove the data at the moment it is most
     * wanted.
     */
    public inline fun <T> record(subsystem: Subsystem, name: String, block: () -> T): T {
        val span = begin(subsystem, name)
        try {
            return block()
        } finally {
            span.close()
        }
    }

    /** Opens a span to be closed later — for work that does not fit a single lambda. */
    public fun begin(subsystem: Subsystem, name: String): OpenSpan {
        require(name.isNotBlank()) { "a timing span must be named (§100.1)" }
        return OpenSpan(this, SpanId(subsystem, name), clock.monotonicNanos())
    }

    internal fun record(id: SpanId, elapsedNanos: Long) {
        stats.computeIfAbsent(id) { Accumulator() }.add(elapsedNanos)
    }

    /** Keeps the clock itself private to the recorder while [OpenSpan] can still read it. */
    internal fun clockNanos(): Long = clock.monotonicNanos()

    /** An immutable snapshot. Safe to call from any thread while spans are being recorded. */
    public fun snapshot(): TimingBreakdown = TimingBreakdown(
        stats.entries
            .map { (id, accumulator) -> accumulator.toStats(id) }
            .sortedWith(compareBy({ it.subsystem.ordinal }, { it.name })),
    )

    public fun reset() {
        stats.clear()
    }

    private class Accumulator {
        private val lock = Any()
        private var count = 0L
        private var totalNanos = 0L
        private var minNanos = Long.MAX_VALUE
        private var maxNanos = Long.MIN_VALUE

        fun add(elapsedNanos: Long) = synchronized(lock) {
            count++
            totalNanos += elapsedNanos
            if (elapsedNanos < minNanos) minNanos = elapsedNanos
            if (elapsedNanos > maxNanos) maxNanos = elapsedNanos
        }

        fun toStats(id: SpanId): SpanStats = synchronized(lock) {
            SpanStats(
                subsystem = id.subsystem,
                name = id.name,
                count = count,
                totalNanos = totalNanos,
                minNanos = if (count == 0L) 0 else minNanos,
                maxNanos = if (count == 0L) 0 else maxNanos,
            )
        }
    }
}

/** Identity of a span: the subsystem it is attributed to, plus its name (§100.1). */
public data class SpanId(public val subsystem: Subsystem, public val name: String)

/** A span in progress. [close] is idempotent — a double close must not double-count. */
public class OpenSpan internal constructor(
    private val recorder: TimingSpanRecorder,
    private val id: SpanId,
    private val startNanos: Long,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        recorder.record(id, recorder.clockNanos() - startNanos)
    }
}

/** Aggregated measurements for one `(subsystem, name)` pair. */
public data class SpanStats(
    public val subsystem: Subsystem,
    public val name: String,
    public val count: Long,
    public val totalNanos: Long,
    public val minNanos: Long,
    public val maxNanos: Long,
) {
    public val meanNanos: Long get() = if (count == 0L) 0 else totalNanos / count
    public val totalMillis: Double get() = totalNanos / 1_000_000.0
    public val meanMillis: Double get() = meanNanos / 1_000_000.0
}

/**
 * A snapshot of all recorded spans.
 *
 * [bySubsystem] is the view §100.1 mandates: separate per-subsystem figures, never a single
 * aggregate presented as "CPU time".
 */
public data class TimingBreakdown(public val spans: List<SpanStats>) {

    /** Total nanoseconds per subsystem — the §100.1 bars. Subsystems with no spans are absent. */
    public fun bySubsystem(): Map<Subsystem, Long> =
        spans.groupingBy { it.subsystem }.fold(0L) { acc, stats -> acc + stats.totalNanos }

    public fun forSubsystem(subsystem: Subsystem): List<SpanStats> =
        spans.filter { it.subsystem == subsystem }

    public fun span(subsystem: Subsystem, name: String): SpanStats? =
        spans.firstOrNull { it.subsystem == subsystem && it.name == name }

    /**
     * Grand total. Present for percentage computation only — §100.1 forbids *displaying*
     * this in place of the per-subsystem breakdown.
     */
    public val totalNanos: Long get() = spans.sumOf { it.totalNanos }

    public val isEmpty: Boolean get() = spans.isEmpty()
}
