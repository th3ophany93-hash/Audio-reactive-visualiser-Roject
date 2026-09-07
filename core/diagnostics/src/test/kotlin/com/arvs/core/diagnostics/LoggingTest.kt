package com.arvs.core.diagnostics

import com.arvs.core.model.ArvsError
import com.arvs.core.model.ErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins §99: structured, subsystem-tagged, rate-limited logging.
 */
class LoggingTest {

    @Test
    fun `the eight §99 subsystems are present and no others`() {
        assertEquals(
            listOf(
                Subsystem.RENDERER,
                Subsystem.AUDIO,
                Subsystem.ANALYZER,
                Subsystem.REACTIVE,
                Subsystem.PROJECT,
                Subsystem.ASSETS,
                Subsystem.PLUGINS,
                Subsystem.EXPORTER,
            ),
            Subsystem.entries.toList(),
        )
    }

    @Test
    fun `events carry structure, not just a formatted string`() {
        // §98 and §100.1 both need to group events; formatting early destroys that.
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }

        logger.forSubsystem(Subsystem.ANALYZER).info("stage-start", "FFT stage begun")

        val event = sink.snapshot().single()
        assertEquals(Subsystem.ANALYZER, event.subsystem)
        assertEquals(LogLevel.INFO, event.level)
        assertEquals("stage-start", event.key)
        assertEquals("FFT stage begun", event.message)
    }

    @Test
    fun `events below the minimum level are dropped`() {
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.PRODUCTION, ManualDiagnosticsClock()).also { it.addSink(sink) }

        logger.forSubsystem(Subsystem.AUDIO).debug("k", "chatty")
        logger.forSubsystem(Subsystem.AUDIO).verbose("k", "chattier")
        assertTrue(sink.snapshot().isEmpty())

        logger.forSubsystem(Subsystem.AUDIO).info("k", "worth keeping")
        assertEquals(1, sink.snapshot().size)
    }

    @Test
    fun `production logging is rate-limited per §99`() {
        val clock = ManualDiagnosticsClock()
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.PRODUCTION, clock).also { it.addSink(sink) }
        val audio = logger.forSubsystem(Subsystem.AUDIO)

        repeat(100) { audio.info("underrun", "buffer underrun") }

        // PRODUCTION allows 5 per key per second.
        assertEquals(5, sink.snapshot().size)
    }

    @Test
    fun `suppressed events are counted, not forgotten`() {
        // The property that makes §99's limit diagnostic rather than merely quiet.
        val clock = ManualDiagnosticsClock()
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.PRODUCTION, clock).also { it.addSink(sink) }
        val audio = logger.forSubsystem(Subsystem.AUDIO)

        repeat(100) { audio.info("underrun", "buffer underrun") }
        clock.advanceMillis(1_000)
        audio.info("underrun", "buffer underrun")

        val last = sink.snapshot().last()
        assertEquals(95, last.suppressedSincePrevious)
        assertTrue(last.format().contains("+95 suppressed"))
    }

    @Test
    fun `the first occurrence of a key is always admitted`() {
        // A limiter that can swallow the first report of a novel failure is worse than none.
        val clock = ManualDiagnosticsClock()
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.PRODUCTION, clock).also { it.addSink(sink) }

        repeat(1_000) { logger.forSubsystem(Subsystem.AUDIO).info("flood", "noise") }
        logger.forSubsystem(Subsystem.ANALYZER).info("novel-failure", "first ever occurrence")

        assertTrue(sink.snapshot().any { it.key == "novel-failure" })
    }

    @Test
    fun `rate limiting is per key, so one chatty call site cannot starve another`() {
        val clock = ManualDiagnosticsClock()
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.PRODUCTION, clock).also { it.addSink(sink) }
        val audio = logger.forSubsystem(Subsystem.AUDIO)

        repeat(100) { audio.info("chatty", "noise") }
        repeat(3) { audio.info("quiet", "signal") }

        assertEquals(3, sink.snapshot().count { it.key == "quiet" })
    }

    @Test
    fun `the same key in different subsystems is limited independently`() {
        val clock = ManualDiagnosticsClock()
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.PRODUCTION, clock).also { it.addSink(sink) }

        repeat(100) { logger.forSubsystem(Subsystem.AUDIO).info("decode", "x") }
        repeat(2) { logger.forSubsystem(Subsystem.ASSETS).info("decode", "y") }

        assertEquals(2, sink.snapshot().count { it.subsystem == Subsystem.ASSETS })
    }

    @Test
    fun `debug builds are not rate-limited`() {
        // §99 scopes the requirement to production; throttling a debug session hides bugs.
        val sink = RecordingLogSink(capacity = 1_000)
        val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }

        repeat(500) { logger.forSubsystem(Subsystem.AUDIO).debug("k", "m") }
        assertEquals(500, sink.snapshot().size)
    }

    @Test
    fun `errors are logged with their §97 category attached`() {
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }

        logger.forSubsystem(Subsystem.AUDIO).error(
            key = "decode",
            error = ArvsError(ErrorCategory.DECODER_ERROR, "FLAC frame CRC mismatch at 12.4s"),
        )

        val event = sink.snapshot().single()
        assertEquals(ErrorCategory.DECODER_ERROR, event.error?.category)
        assertTrue(event.format().contains("DECODER_ERROR"))
        assertTrue(event.format().contains("FLAC frame CRC mismatch"))
    }

    @Test
    fun `a throwing sink does not break the subsystem that was logging`() {
        val good = RecordingLogSink()
        val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock())
        logger.addSink { error("sink exploded") }
        logger.addSink(good)

        logger.forSubsystem(Subsystem.PROJECT).info("k", "still delivered")

        assertEquals(1, good.snapshot().size)
    }
}
