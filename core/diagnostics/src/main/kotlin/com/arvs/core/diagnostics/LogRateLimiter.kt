package com.arvs.core.diagnostics

/**
 * §99's rate limit: *"Production logging must be rate-limited."*
 *
 * The design point that makes this useful rather than merely compliant: a suppressed event
 * is **counted, not forgotten**. When a key becomes loggable again the next admitted event
 * carries the number suppressed since the previous one. "Decode underrun (×8 412 suppressed)"
 * is a far better diagnostic than either eight thousand identical lines or silence — and
 * silence is what a naive limiter produces precisely when something is going badly wrong.
 *
 * The first occurrence of any key is always admitted. A limiter that can swallow the first
 * report of a novel failure is worse than no limiter.
 */
public class LogRateLimiter(
    private val policy: RateLimitPolicy,
    private val clock: DiagnosticsClock,
) {
    private val lock = Any()

    /**
     * Bounded, access-ordered so the least recently used key is evicted first.
     *
     * The bound matters: keys arrive from call sites across the whole app, and an unbounded
     * map here would make the component whose job is production hygiene into a slow leak.
     * Eviction restarts a key's window, so a flood *can* briefly get through after its key
     * is evicted — acceptable, because eviction only happens once more than
     * [RateLimitPolicy.maxTrackedKeys] distinct keys are contending, and a leak is not.
     */
    private val windows = object : LinkedHashMap<String, KeyWindow>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeyWindow>): Boolean =
            size > policy.maxTrackedKeys
    }

    /** Decides whether an event tagged [key] may be emitted now. */
    public fun acquire(key: String): RateLimitDecision {
        val now = clock.monotonicNanos()
        synchronized(lock) {
            val window = windows.getOrPut(key) { KeyWindow(startNanos = now) }

            if (now - window.startNanos >= policy.windowNanos) {
                window.startNanos = now
                window.admitted = 0
            }

            return if (window.admitted < policy.maxEventsPerWindow) {
                window.admitted++
                val suppressed = window.suppressedSinceLastAdmitted
                window.suppressedSinceLastAdmitted = 0
                RateLimitDecision.Allow(suppressed)
            } else {
                window.suppressedSinceLastAdmitted++
                RateLimitDecision.Suppress
            }
        }
    }

    /** Number of keys currently tracked. Exposed so the bound is assertable. */
    public fun trackedKeyCount(): Int = synchronized(lock) { windows.size }

    public fun reset(): Unit = synchronized(lock) { windows.clear() }

    private class KeyWindow(
        var startNanos: Long,
        var admitted: Int = 0,
        var suppressedSinceLastAdmitted: Int = 0,
    )
}

/**
 * How aggressively logging is throttled.
 *
 * Per-key, not global: one chatty call site must not be able to starve every other
 * subsystem's logging, which is what a single global budget would allow.
 */
public data class RateLimitPolicy(
    public val maxEventsPerWindow: Int,
    public val windowNanos: Long,
    public val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
) {
    init {
        require(maxEventsPerWindow > 0) {
            "maxEventsPerWindow must be positive — a zero budget would suppress the first " +
                "report of a novel failure: $maxEventsPerWindow"
        }
        require(windowNanos > 0) { "windowNanos must be positive: $windowNanos" }
        require(maxTrackedKeys > 0) { "maxTrackedKeys must be positive: $maxTrackedKeys" }
    }

    public companion object {
        public const val DEFAULT_MAX_TRACKED_KEYS: Int = 256

        /** The §99 production default: at most 5 events per key per second. */
        public val PRODUCTION: RateLimitPolicy = RateLimitPolicy(
            maxEventsPerWindow = 5,
            windowNanos = 1_000_000_000L,
        )
    }
}

public sealed interface RateLimitDecision {
    /**
     * Emit the event. [suppressedSinceLastAdmitted] is how many events with the same key
     * were dropped since the previous admitted one — attach it to the message so the count
     * survives (§99).
     */
    public data class Allow(public val suppressedSinceLastAdmitted: Int) : RateLimitDecision

    /** Drop the event; its occurrence is counted and reported with the next admitted one. */
    public data object Suppress : RateLimitDecision
}
