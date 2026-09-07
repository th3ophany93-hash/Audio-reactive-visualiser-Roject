package com.arvs.core.diagnostics

import java.util.concurrent.CopyOnWriteArrayList

/**
 * §98.1's memory-pressure response — a mandatory requirement, and one absent from v2.0.
 *
 * §98.1 states the policy precisely, and it has two halves:
 *
 *  1. Release **cheap-to-regenerate** things first — non-visible-layer GPU resources and
 *     prefetched-but-not-yet-needed analysis cache pages.
 *  2. **Never** release the active `ProjectState` or undo history — "cheap to keep,
 *     catastrophic to lose".
 *
 * The second half is the one that matters, and prose does not enforce it. So it is enforced
 * structurally instead: everything registered here must declare a [RegenerationCost], and
 * that enum has **no value meaning "cannot be regenerated"**. There is therefore no way to
 * express `ProjectState` as a registrable resource — not a rule someone must remember, but a
 * sentence the API cannot say. See [RegenerationCost].
 *
 * This module is pure Kotlin (§116.1), so `ComponentCallbacks2.onTrimMemory` is not
 * referenced here. `:app` translates Android's trim levels into [MemoryPressureLevel] at the
 * composition root and calls [dispatch]; see [MemoryPressureLevel] for the mapping contract.
 */
public class MemoryPressureDispatcher(
    private val logger: SubsystemLogger? = null,
) {
    private val registrations = CopyOnWriteArrayList<Registration>()

    /**
     * Registers a resource that may be released under memory pressure.
     *
     * Returns a handle; call [Registration.unregister] when the owner is torn down, or the
     * dispatcher will keep it alive.
     */
    public fun register(resource: RegenerableResource): Registration {
        val registration = Registration(resource, this)
        registrations.add(registration)
        return registration
    }

    internal fun unregister(registration: Registration) {
        registrations.remove(registration)
    }

    /**
     * Releases resources proportionally to [level] (§98.1: "respond proportionally").
     *
     * Cheaper-to-regenerate resources are released first and at every level; more expensive
     * ones only as pressure rises. Returns a record of what was released, which feeds the
     * §98 diagnostic report's memory-trim history.
     */
    public fun dispatch(level: MemoryPressureLevel): MemoryTrimResult {
        val released = mutableListOf<ReleasedResource>()
        var totalBytes = 0L

        for (cost in RegenerationCost.entries) {
            if (!level.releases(cost)) continue
            for (registration in registrations) {
                if (registration.resource.regenerationCost != cost) continue
                val bytes = runCatching { registration.resource.releaseUnderPressure(level) }
                    .getOrElse { 0L }
                    .coerceAtLeast(0L)
                if (bytes > 0) {
                    released += ReleasedResource(registration.resource.resourceName, cost, bytes)
                    totalBytes += bytes
                }
            }
        }

        val result = MemoryTrimResult(level, released, totalBytes)
        logger?.info(
            key = "memory-trim",
            message = "Memory pressure $level released $totalBytes bytes across " +
                "${released.size} resource(s)",
        )
        return result
    }

    /** Number of currently registered resources. Exposed so registration leaks are testable. */
    public fun registeredCount(): Int = registrations.size

    public class Registration internal constructor(
        internal val resource: RegenerableResource,
        private val dispatcher: MemoryPressureDispatcher,
    ) {
        public fun unregister(): Unit = dispatcher.unregister(this)
    }
}

/**
 * How expensive it is to rebuild a resource after releasing it.
 *
 * Declaration order **is** the release order — [CHEAP] goes first. That is §98.1's rule
 * expressed as data rather than as a comparator someone can get backwards.
 *
 * There is deliberately no `IRRECOVERABLE` value. §98.1 forbids releasing the active
 * `ProjectState` and undo history under any level of pressure, and the cleanest enforcement
 * of "never" is to leave the API unable to describe such a resource at all: a thing that
 * cannot be regenerated cannot be given a [RegenerationCost], so it cannot be registered,
 * so it cannot be released. Do not add a value here to accommodate one.
 */
public enum class RegenerationCost {
    /**
     * Rebuilt in milliseconds from data still in memory or already on disk — §98.1's
     * named examples: non-visible-layer GPU resources, prefetched analysis cache pages.
     */
    CHEAP,

    /** Rebuilt in well under a second, e.g. re-reading a cached analysis page from disk. */
    MODERATE,

    /**
     * Rebuilt only by redoing real work — a full re-decode or re-analysis. Released only
     * when the alternative is being killed by the OS.
     */
    EXPENSIVE,
}

/**
 * Severity of memory pressure, as reported by the platform.
 *
 * `:app` maps `ComponentCallbacks2` trim levels onto these at the Android boundary. The
 * mapping contract is that the *most severe* interpretation wins, because under-reacting to
 * memory pressure ends in the process being killed, whereas over-reacting only costs
 * regeneration work — which every registrable resource is, by construction, able to do.
 */
public enum class MemoryPressureLevel {
    /** Background trim, or a mild running-low signal. Shed only free-to-rebuild caches. */
    LOW,

    /** The system is under real pressure. Shed cheap and moderate caches. */
    MODERATE,

    /** Imminent risk of being killed. Shed everything releasable. */
    CRITICAL,
    ;

    /** Whether pressure at this level releases resources of the given [cost] (§98.1). */
    public fun releases(cost: RegenerationCost): Boolean = when (this) {
        LOW -> cost == RegenerationCost.CHEAP
        MODERATE -> cost == RegenerationCost.CHEAP || cost == RegenerationCost.MODERATE
        CRITICAL -> true
    }
}

/**
 * A cache or GPU resource that can be dropped and rebuilt.
 *
 * Implementing this type is an assertion that the resource *is* regenerable. §98.1's
 * protected state — active `ProjectState`, undo history — must not implement it.
 */
public interface RegenerableResource {
    /** Stable name, used in the §98 diagnostic report's memory-trim history. */
    public val resourceName: String

    public val regenerationCost: RegenerationCost

    /** Releases what is appropriate for [level]; returns the number of bytes freed. */
    public fun releaseUnderPressure(level: MemoryPressureLevel): Long
}

public data class ReleasedResource(
    public val resourceName: String,
    public val regenerationCost: RegenerationCost,
    public val bytesFreed: Long,
)

/** Outcome of one trim, retained for the §98 diagnostic report. */
public data class MemoryTrimResult(
    public val level: MemoryPressureLevel,
    public val released: List<ReleasedResource>,
    public val totalBytesFreed: Long,
)
