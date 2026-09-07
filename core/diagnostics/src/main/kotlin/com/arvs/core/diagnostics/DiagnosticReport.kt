package com.arvs.core.diagnostics

import java.util.concurrent.CopyOnWriteArrayList

/**
 * §98's exportable diagnostic report.
 *
 * §98 enumerates what the report must cover: device, Android version, GPU, RAM, storage,
 * renderer, codec, project complexity, layers, effects, plugins, audio duration, analysis
 * cache, FPS, GPU time, CPU time, memory. Those live in different modules — several of which
 * do not exist until Phase 2 — so the report is *assembled from contributors* rather than
 * built by one omniscient class that would have to depend on everything.
 *
 * The design decision worth stating: [DiagnosticSectionId] declares the required sections up
 * front, and a section with no contributor is rendered as **"unavailable"**, not silently
 * omitted. A report that quietly lacks the GPU section looks the same as a report from a
 * device with no GPU problem, and that ambiguity defeats the purpose of having a report at
 * all. In Phase 1 several sections are legitimately unavailable — no renderer exists — and
 * the report says so.
 *
 * **Privacy (§114).** The report is assembled locally and returned to the caller as text.
 * There is no upload path in this module, and the §98 telemetry hook that could one day
 * carry one is off by default and has no transport (see [EventReportingHook]). Contributors
 * should report asset *properties* — duration, sample rate, channel count — rather than file
 * names or SAF URIs, which identify the user's library rather than the fault.
 */
public class DiagnosticReportAssembler(
    private val clock: DiagnosticsClock = SystemDiagnosticsClock,
) {
    private val contributors = CopyOnWriteArrayList<Pair<DiagnosticSectionId, DiagnosticContributor>>()

    /** Registers the provider for one §98 section. A later registration replaces an earlier one. */
    public fun register(id: DiagnosticSectionId, contributor: DiagnosticContributor) {
        contributors.removeIf { it.first == id }
        contributors.add(id to contributor)
    }

    public fun unregister(id: DiagnosticSectionId) {
        contributors.removeIf { it.first == id }
    }

    /**
     * Builds the report.
     *
     * A contributor that throws yields a section marked as failed rather than aborting the
     * report. A diagnostic report that cannot be produced because something is broken is
     * useless precisely when something is broken.
     */
    public fun assemble(): DiagnosticReport {
        val byId = contributors.associate { it.first to it.second }
        val sections = DiagnosticSectionId.entries.map { id ->
            val contributor = byId[id] ?: return@map DiagnosticSection.unavailable(id)
            runCatching { DiagnosticSection(id, contributor.contribute()) }
                .getOrElse { failure -> DiagnosticSection.failed(id, failure) }
        }
        return DiagnosticReport(generatedAtWallMillis = clock.wallTimeMillis(), sections = sections)
    }
}

/**
 * The sections §98 requires. Declared as a closed set so a missing one is *detectable*
 * rather than invisible.
 */
public enum class DiagnosticSectionId(public val title: String) {
    PLATFORM("Device & Platform"),
    HARDWARE("Hardware & Storage"),
    RENDERER("Renderer & GPU"),
    CODECS("Codecs"),
    PROJECT("Project Complexity"),
    AUDIO("Audio & Analysis"),
    PERFORMANCE("Performance"),
    MEMORY("Memory"),
    ERRORS("Errors This Session"),
}

/** Supplies the fields for one §98 section. Implemented in whichever module owns the data. */
public fun interface DiagnosticContributor {
    public fun contribute(): List<DiagnosticField>
}

public data class DiagnosticField(public val label: String, public val value: String)

public data class DiagnosticSection(
    public val id: DiagnosticSectionId,
    public val fields: List<DiagnosticField>,
    public val availability: Availability = Availability.AVAILABLE,
) {
    public enum class Availability { AVAILABLE, UNAVAILABLE, FAILED }

    public companion object {
        /** No contributor registered — e.g. Phase 1 has no renderer to describe. */
        public fun unavailable(id: DiagnosticSectionId): DiagnosticSection =
            DiagnosticSection(id, emptyList(), Availability.UNAVAILABLE)

        public fun failed(id: DiagnosticSectionId, cause: Throwable): DiagnosticSection =
            DiagnosticSection(
                id = id,
                fields = listOf(
                    DiagnosticField(
                        "collection failed",
                        cause.message ?: cause::class.java.simpleName,
                    ),
                ),
                availability = Availability.FAILED,
            )
    }
}

/** An assembled §98 report. [toPlainText] is the exportable form. */
public data class DiagnosticReport(
    public val generatedAtWallMillis: Long,
    public val sections: List<DiagnosticSection>,
) {
    /** Sections §98 requires that no module supplied. Empty is the Phase 10 goal, not Phase 1's. */
    public fun unavailableSections(): List<DiagnosticSectionId> =
        sections.filter { it.availability == DiagnosticSection.Availability.UNAVAILABLE }.map { it.id }

    public fun failedSections(): List<DiagnosticSectionId> =
        sections.filter { it.availability == DiagnosticSection.Availability.FAILED }.map { it.id }

    public fun section(id: DiagnosticSectionId): DiagnosticSection? = sections.firstOrNull { it.id == id }

    public fun toPlainText(): String = buildString {
        appendLine("=== Audio Reactive Video Studio — Diagnostic Report (§98) ===")
        appendLine("Generated at (epoch ms): $generatedAtWallMillis")
        for (section in sections) {
            appendLine()
            append("-- ").append(section.id.title)
            when (section.availability) {
                DiagnosticSection.Availability.UNAVAILABLE -> appendLine(" — unavailable in this build")
                DiagnosticSection.Availability.FAILED -> appendLine(" — collection FAILED")
                DiagnosticSection.Availability.AVAILABLE -> appendLine()
            }
            for (field in section.fields) {
                append("   ").append(field.label).append(": ").appendLine(field.value)
            }
        }
    }
}
