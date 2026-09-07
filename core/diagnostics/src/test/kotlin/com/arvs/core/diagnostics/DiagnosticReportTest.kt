package com.arvs.core.diagnostics

import com.arvs.core.model.ArvsError
import com.arvs.core.model.ErrorCategory
import com.arvs.core.time.TimeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins §98's report assembly and §97's reporting path. */
class DiagnosticReportTest {

    @Test
    fun `a section with no contributor is marked unavailable, never omitted`() {
        // A silently missing GPU section is indistinguishable from a healthy one. Phase 1
        // has no renderer, so the report must say so rather than simply not mention it.
        val report = DiagnosticReportAssembler(ManualDiagnosticsClock()).assemble()

        assertEquals(DiagnosticSectionId.entries.size, report.sections.size)
        assertTrue(DiagnosticSectionId.RENDERER in report.unavailableSections())
        assertTrue(report.toPlainText().contains("Renderer & GPU — unavailable in this build"))
    }

    @Test
    fun `registered contributors supply their section`() {
        val assembler = DiagnosticReportAssembler(ManualDiagnosticsClock())
        assembler.register(DiagnosticSectionId.AUDIO) {
            listOf(
                DiagnosticField("Duration", TimeSpan.ofSeconds(212).toString()),
                DiagnosticField("Sample rate", "48000 Hz"),
                DiagnosticField("Channels", "2"),
            )
        }

        val section = assembler.assemble().section(DiagnosticSectionId.AUDIO)!!
        assertEquals(DiagnosticSection.Availability.AVAILABLE, section.availability)
        assertEquals(3, section.fields.size)
        assertTrue(assembler.assemble().toPlainText().contains("Sample rate: 48000 Hz"))
    }

    @Test
    fun `a contributor that throws yields a failed section, not a failed report`() {
        // The report is most needed when something is broken; it must survive that.
        val assembler = DiagnosticReportAssembler(ManualDiagnosticsClock())
        assembler.register(DiagnosticSectionId.HARDWARE) { error("sysfs unreadable") }
        assembler.register(DiagnosticSectionId.AUDIO) { listOf(DiagnosticField("Duration", "3s")) }

        val report = assembler.assemble()

        assertEquals(listOf(DiagnosticSectionId.HARDWARE), report.failedSections())
        assertEquals(
            DiagnosticSection.Availability.AVAILABLE,
            report.section(DiagnosticSectionId.AUDIO)!!.availability,
        )
        assertTrue(report.toPlainText().contains("collection FAILED"))
    }

    @Test
    fun `re-registering a section replaces the previous contributor`() {
        val assembler = DiagnosticReportAssembler(ManualDiagnosticsClock())
        assembler.register(DiagnosticSectionId.PROJECT) { listOf(DiagnosticField("Layers", "1")) }
        assembler.register(DiagnosticSectionId.PROJECT) { listOf(DiagnosticField("Layers", "7")) }

        assertEquals("7", assembler.assemble().section(DiagnosticSectionId.PROJECT)!!.fields.single().value)
    }

    @Test
    fun `the §98 required sections are all declared`() {
        // §98 enumerates device, OS, GPU, RAM, storage, renderer, codec, project complexity,
        // audio duration, analysis cache, performance and memory. Pinned so a section cannot
        // be dropped as later phases fill them in.
        assertEquals(
            listOf(
                DiagnosticSectionId.PLATFORM,
                DiagnosticSectionId.HARDWARE,
                DiagnosticSectionId.RENDERER,
                DiagnosticSectionId.CODECS,
                DiagnosticSectionId.PROJECT,
                DiagnosticSectionId.AUDIO,
                DiagnosticSectionId.PERFORMANCE,
                DiagnosticSectionId.MEMORY,
                DiagnosticSectionId.ERRORS,
            ),
            DiagnosticSectionId.entries.toList(),
        )
    }

    // --- §97 reporting path -------------------------------------------------------------

    @Test
    fun `reported errors are logged, counted and retained`() {
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }
        val reporter = ErrorReporter(logger)

        reporter.report(
            Subsystem.AUDIO,
            "decode",
            ArvsError(ErrorCategory.DECODER_ERROR, "MP3 frame sync lost at 4.2s"),
        )
        reporter.report(
            Subsystem.ASSETS,
            "relink",
            ArvsError(ErrorCategory.PERMISSION_ERROR, "SAF permission for 'set.flac' was revoked"),
        )

        assertEquals(2, sink.snapshot().size)
        assertEquals(2, reporter.totalErrorCount())
        assertEquals(1, reporter.countsByCategory()[ErrorCategory.DECODER_ERROR])
        assertEquals(Subsystem.AUDIO, reporter.recentErrors().first().subsystem)
    }

    @Test
    fun `counts survive rate limiting even when the log lines do not`() {
        // The count is the diagnostic that remains when §99 throttles the flood.
        val clock = ManualDiagnosticsClock()
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.PRODUCTION, clock).also { it.addSink(sink) }
        val reporter = ErrorReporter(logger)

        repeat(100) {
            reporter.report(
                Subsystem.AUDIO,
                "underrun",
                ArvsError(ErrorCategory.DECODER_ERROR, "buffer underrun"),
            )
        }

        assertEquals(5, sink.snapshot().size)          // §99 throttled the lines
        assertEquals(100, reporter.totalErrorCount())  // but not the count
    }

    @Test
    fun `error history is bounded`() {
        val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock())
        val reporter = ErrorReporter(logger, historyCapacity = 8)

        repeat(50) { index ->
            reporter.report(
                Subsystem.ANALYZER,
                "stage",
                ArvsError(ErrorCategory.AUDIO_ANALYSIS_ERROR, "stage $index failed"),
            )
        }

        assertEquals(8, reporter.recentErrors().size)
        assertTrue(reporter.recentErrors().last().error.message.contains("stage 49"))
        assertEquals(50, reporter.totalErrorCount())
    }

    // --- §98 / §114 reserved telemetry hook (U-7) ---------------------------------------

    @Test
    fun `the default event reporting hook collects nothing`() {
        // §98 (Ref AR-19.5) and §114: reserved, opt-in, off by default. U-7 is OPEN, so
        // nothing here may collect or transmit. Tested with a spy that would count if it
        // were ever reached.
        var received = 0
        val spy = EventReportingHook { _, _ -> received++ }
        val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock())
        val error = ArvsError(ErrorCategory.DECODER_ERROR, "boom")

        // The default wiring never reaches a hook at all.
        ErrorReporter(logger).report(Subsystem.AUDIO, "k", error)
        assertEquals(0, received)

        // And the explicit v1 wiring is doubly inert: a never-granted gate around the spy.
        ErrorReporter(logger, ConsentGatedEventReporting(spy) { false })
            .report(Subsystem.AUDIO, "k", error)
        assertEquals(0, received)

        // The shipped factory is that same closed gate, and calling it is a safe no-op.
        ConsentGatedEventReporting.offByDefault().onError(Subsystem.AUDIO, error)
        assertEquals(0, received)
    }

    @Test
    fun `consent gates the hook and is re-checked on every event`() {
        var consent = false
        val delivered = mutableListOf<ArvsError>()
        val gated = ConsentGatedEventReporting({ _, error -> delivered += error }) { consent }

        val error = ArvsError(ErrorCategory.PLUGIN_ERROR, "plugin crashed")
        gated.onError(Subsystem.PLUGINS, error)
        assertTrue(delivered.isEmpty())

        consent = true
        gated.onError(Subsystem.PLUGINS, error)
        assertEquals(1, delivered.size)

        // Revocation takes effect immediately, not at next process start.
        consent = false
        gated.onError(Subsystem.PLUGINS, error)
        assertEquals(1, delivered.size)
    }

    @Test
    fun `this module contains no network or transport code`() {
        // §114 forbids upload without explicit opt-in; U-7 has not been decided. The
        // strongest guarantee available is that no transport exists to be enabled by
        // accident. Asserted structurally over the module's own sources.
        val sources = java.io.File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("expected diagnostics sources to be present", sources.isNotEmpty())

        val forbidden = listOf("java.net", "okhttp", "HttpURLConnection", "java.nio.channels.Socket", "Retrofit")
        sources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("${file.name} must not reference $token (§114, U-7)", text.contains(token))
            }
        }
    }
}
