package com.arvs.core.diagnostics

import com.arvs.core.model.ArvsError

/**
 * §98's reserved event-reporting hook (Ref AR-19.5), and Appendix B item **U-7**.
 *
 * §98 ratifies an **opt-in, off-by-default** hook for aggregate crash/plugin-failure
 * telemetry, "designed now so it is not a bolt-on retrofit later", collecting and
 * transmitting nothing unless a user explicitly opts in. §114 is the constraint it lives
 * under: nothing is uploaded without explicit future opt-in.
 *
 * What that means concretely for this file, and what a reader should be able to verify at a
 * glance:
 *
 *  - **There is no transport.** No HTTP client, no network permission, no serialization to
 *    a wire format, anywhere in this module. U-7 (timeline for activation, and the exact
 *    data-minimization and consent UX) is OPEN and owned by the Project Owner. Building a
 *    transport before that decision would be building the very thing §114 forbids and
 *    calling it inert.
 *  - **The default is [DisabledEventReporting]**, which does nothing at all.
 *  - **Consent is a gate, not a flag on a payload.** [ConsentGatedEventReporting] refuses to
 *    call its delegate until consent is granted, so an activated-but-unconsented build still
 *    transmits nothing.
 *
 * The shape is reserved. The behaviour is off.
 */
public fun interface EventReportingHook {

    /** Notified of a §97-categorised failure. Implementations must not block the caller. */
    public fun onError(subsystem: Subsystem, error: ArvsError)
}

/**
 * The default and, in v1, the only wiring: collects nothing, retains nothing, sends nothing.
 */
public object DisabledEventReporting : EventReportingHook {
    override fun onError(subsystem: Subsystem, error: ArvsError): Unit = Unit
}

/**
 * Wraps a hook behind an explicit consent check (§98, §114).
 *
 * [consentGranted] is queried on every event rather than cached, so revoking consent takes
 * effect immediately instead of at the next process start.
 */
public class ConsentGatedEventReporting(
    private val delegate: EventReportingHook,
    private val consentGranted: () -> Boolean,
) : EventReportingHook {

    override fun onError(subsystem: Subsystem, error: ArvsError) {
        if (!consentGranted()) return
        delegate.onError(subsystem, error)
    }

    public companion object {
        /**
         * The v1 wiring: a gate whose consent is never granted, around a hook that does
         * nothing. Both halves are deliberate — either one alone would leave the other as
         * the single point of failure.
         */
        public fun offByDefault(): EventReportingHook =
            ConsentGatedEventReporting(DisabledEventReporting) { false }
    }
}
