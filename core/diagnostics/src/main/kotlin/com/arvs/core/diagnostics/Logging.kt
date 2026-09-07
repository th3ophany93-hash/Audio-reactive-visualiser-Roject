package com.arvs.core.diagnostics

import com.arvs.core.model.ArvsError
import java.util.concurrent.CopyOnWriteArrayList

/** Severity, ordered. [ordinal] is the comparison key, so declaration order is significant. */
public enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/**
 * One structured log record (§99).
 *
 * Structured, not a formatted string: the subsystem, level, key and error category stay
 * machine-readable all the way to the sink. §98's diagnostic report and §100.1's per-
 * subsystem attribution both need to *group* log events, which string formatting destroys.
 */
public data class LogEvent(
    public val level: LogLevel,
    public val subsystem: Subsystem,
    /** Stable call-site identifier — the rate-limiting key. See [Logger]. */
    public val key: String,
    public val message: String,
    /** Set when this event reports a §97-categorised failure. */
    public val error: ArvsError? = null,
    public val throwable: Throwable? = null,
    public val wallTimeMillis: Long = 0L,
    /** §99: events dropped by the rate limiter since the previous admitted one. */
    public val suppressedSincePrevious: Int = 0,
) {
    /** Human-readable single line, used by text sinks and the §98 report. */
    public fun format(): String = buildString {
        append(level.name).append('/').append(subsystem.tag).append(' ')
        append('[').append(key).append("] ")
        append(message)
        error?.let { append(" — ").append(it) }
        if (suppressedSincePrevious > 0) {
            append(" (+").append(suppressedSincePrevious).append(" suppressed)")
        }
    }
}

/**
 * Where log events go. The Android `android.util.Log` binding is a sink installed by `:app`
 * at the composition root, which is why this module stays pure Kotlin (§116.1).
 */
public fun interface LogSink {
    public fun write(event: LogEvent)
}

/** Minimum level and throttling. §99 requires the rate limit in production builds. */
public data class LoggingPolicy(
    public val minimumLevel: LogLevel,
    public val rateLimit: RateLimitPolicy?,
) {
    public companion object {
        /** Release: INFO and above, rate-limited per §99. */
        public val PRODUCTION: LoggingPolicy = LoggingPolicy(LogLevel.INFO, RateLimitPolicy.PRODUCTION)

        /** Debug builds: everything, unthrottled — §99 scopes the limit to production. */
        public val DEBUG: LoggingPolicy = LoggingPolicy(LogLevel.VERBOSE, rateLimit = null)
    }
}

/**
 * The structured logger of §99.
 *
 * **Concurrency contract.** §101 runs UI, Audio, Analysis, Decode, Render and Export on
 * separate threads and §99 requires all of them to log, so this object is reached from many
 * threads by design. §101.1 forbids *ad hoc* cross-thread sharing; this is the opposite of
 * ad hoc, and it is stated here rather than assumed:
 *
 *  - Sinks live in a [CopyOnWriteArrayList]: registration is expected at startup, and
 *    emission — the hot path — never blocks and never allocates an iterator snapshot.
 *  - Rate-limiter state is guarded by a single lock inside [LogRateLimiter].
 *  - No domain state passes through here. A [LogEvent] is an immutable value; the logger
 *    holds nothing a subsystem could mutate through, which is what §101.1's prohibition on
 *    "shared mutable maps, direct object calls across threads" is protecting.
 *
 * **Why [key] is a required parameter and not defaulted to the message.** Rate limiting is
 * per key. If the key defaulted to the message, then a call site logging
 * `"decode failed for $uri"` inside a loop would produce a distinct key every iteration and
 * silently bypass the limiter entirely — a limiter that appears to work and does not. An
 * explicit, low-cardinality key is the difference.
 */
public class Logger(
    private val policy: LoggingPolicy,
    private val clock: DiagnosticsClock = SystemDiagnosticsClock,
) {
    private val sinks = CopyOnWriteArrayList<LogSink>()
    private val rateLimiter: LogRateLimiter? =
        policy.rateLimit?.let { LogRateLimiter(it, clock) }

    public fun addSink(sink: LogSink) {
        sinks.add(sink)
    }

    public fun removeSink(sink: LogSink) {
        sinks.remove(sink)
    }

    /** A pre-tagged view for one subsystem, so callers cannot mis-tag their own events. */
    public fun forSubsystem(subsystem: Subsystem): SubsystemLogger = SubsystemLogger(this, subsystem)

    public fun log(
        level: LogLevel,
        subsystem: Subsystem,
        key: String,
        message: String,
        error: ArvsError? = null,
        throwable: Throwable? = null,
    ) {
        if (level < policy.minimumLevel) return

        var suppressedSincePrevious = 0
        rateLimiter?.let { limiter ->
            when (val decision = limiter.acquire("${subsystem.name}:$key")) {
                is RateLimitDecision.Allow -> suppressedSincePrevious = decision.suppressedSinceLastAdmitted
                RateLimitDecision.Suppress -> return
            }
        }

        val event = LogEvent(
            level = level,
            subsystem = subsystem,
            key = key,
            message = message,
            error = error,
            throwable = throwable,
            wallTimeMillis = clock.wallTimeMillis(),
            suppressedSincePrevious = suppressedSincePrevious,
        )
        // A sink that throws must not take down the subsystem that was merely logging.
        for (sink in sinks) {
            runCatching { sink.write(event) }
        }
    }
}

/** [Logger] pre-tagged with one [Subsystem] (§99). */
public class SubsystemLogger internal constructor(
    private val logger: Logger,
    private val subsystem: Subsystem,
) {
    public fun verbose(key: String, message: String): Unit =
        logger.log(LogLevel.VERBOSE, subsystem, key, message)

    public fun debug(key: String, message: String): Unit =
        logger.log(LogLevel.DEBUG, subsystem, key, message)

    public fun info(key: String, message: String): Unit =
        logger.log(LogLevel.INFO, subsystem, key, message)

    public fun warn(key: String, message: String, throwable: Throwable? = null): Unit =
        logger.log(LogLevel.WARN, subsystem, key, message, throwable = throwable)

    /**
     * Reports a §97-categorised failure.
     *
     * There is deliberately no `error(key, message)` overload taking a bare string: §97
     * requires every failure to carry a category, and an API that makes the uncategorised
     * form convenient is how "Something went wrong" gets back in.
     */
    public fun error(key: String, error: ArvsError): Unit =
        logger.log(LogLevel.ERROR, subsystem, key, error.message, error = error, throwable = error.cause)
}

/** Collects events in memory. Used by tests and by §98's report to carry recent history. */
public class RecordingLogSink(private val capacity: Int = 256) : LogSink {
    private val lock = Any()
    private val events = ArrayDeque<LogEvent>()

    override fun write(event: LogEvent) {
        synchronized(lock) {
            events.addLast(event)
            while (events.size > capacity) events.removeFirst()
        }
    }

    public fun snapshot(): List<LogEvent> = synchronized(lock) { events.toList() }

    public fun clear(): Unit = synchronized(lock) { events.clear() }
}
