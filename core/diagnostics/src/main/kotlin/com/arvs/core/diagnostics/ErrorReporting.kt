package com.arvs.core.diagnostics

import com.arvs.core.model.ArvsError
import com.arvs.core.model.ErrorCategory

/**
 * The single route by which a §97 failure becomes visible.
 *
 * §97: *"Never display only 'Something went wrong.'"* and no failure path may bypass the
 * taxonomy. `core:model`'s [ArvsError] already makes an uncategorised or blank-message
 * failure unconstructible; this type completes the path by ensuring a categorised failure is
 * logged against its subsystem (§99), counted for the §98 diagnostic report, and offered to
 * the reserved — and inert — event hook (§98/U-7).
 *
 * Every subsystem reports through here rather than logging errors ad hoc, so that "how many
 * DECODER_ERRORs has this session seen" is answerable at all.
 */
public class ErrorReporter(
    private val logger: Logger,
    private val eventReporting: EventReportingHook = DisabledEventReporting,
    private val historyCapacity: Int = DEFAULT_HISTORY_CAPACITY,
) {
    private val lock = Any()
    private val recent = ArrayDeque<ReportedError>()
    private val countsByCategory = linkedMapOf<ErrorCategory, Int>()

    /**
     * Reports [error] as originating in [subsystem].
     *
     * [key] is the rate-limiting key (see [Logger]) — a stable call-site identifier, not the
     * message. Note that the history and per-category counts are updated **before** the log
     * call, so a rate-limited flood is still counted accurately even while most of its log
     * lines are suppressed. Losing the count as well as the lines would leave nothing.
     */
    public fun report(subsystem: Subsystem, key: String, error: ArvsError) {
        synchronized(lock) {
            recent.addLast(ReportedError(subsystem, error))
            while (recent.size > historyCapacity) recent.removeFirst()
            countsByCategory[error.category] = (countsByCategory[error.category] ?: 0) + 1
        }
        logger.forSubsystem(subsystem).error(key, error)
        runCatching { eventReporting.onError(subsystem, error) }
    }

    /** Most recent errors, oldest first. Feeds the §98 diagnostic report. */
    public fun recentErrors(): List<ReportedError> = synchronized(lock) { recent.toList() }

    /** Session totals per §97 category, including errors whose log lines were rate-limited. */
    public fun countsByCategory(): Map<ErrorCategory, Int> =
        synchronized(lock) { LinkedHashMap(countsByCategory) }

    public fun totalErrorCount(): Int = synchronized(lock) { countsByCategory.values.sum() }

    public fun clear(): Unit = synchronized(lock) {
        recent.clear()
        countsByCategory.clear()
    }

    public companion object {
        public const val DEFAULT_HISTORY_CAPACITY: Int = 64
    }
}

public data class ReportedError(
    public val subsystem: Subsystem,
    public val error: ArvsError,
)
