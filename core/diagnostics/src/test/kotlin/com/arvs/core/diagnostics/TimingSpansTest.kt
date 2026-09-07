package com.arvs.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins §100.1: named spans, attributed per subsystem, available from day one.
 */
class TimingSpansTest {

    @Test
    fun `a span records the elapsed time against its subsystem and name`() {
        val clock = ManualDiagnosticsClock()
        val recorder = TimingSpanRecorder(clock)

        recorder.record(Subsystem.ANALYZER, "fft") { clock.advanceMillis(12) }

        val stats = recorder.snapshot().span(Subsystem.ANALYZER, "fft")!!
        assertEquals(1, stats.count)
        assertEquals(12_000_000L, stats.totalNanos)
        assertEquals(12.0, stats.totalMillis, 1e-9)
    }

    @Test
    fun `repeated spans accumulate count, total, min and max`() {
        val clock = ManualDiagnosticsClock()
        val recorder = TimingSpanRecorder(clock)

        listOf(5L, 20L, 11L).forEach { millis ->
            recorder.record(Subsystem.AUDIO, "decode") { clock.advanceMillis(millis) }
        }

        val stats = recorder.snapshot().span(Subsystem.AUDIO, "decode")!!
        assertEquals(3, stats.count)
        assertEquals(36_000_000L, stats.totalNanos)
        assertEquals(5_000_000L, stats.minNanos)
        assertEquals(20_000_000L, stats.maxNanos)
        assertEquals(12_000_000L, stats.meanNanos)
    }

    @Test
    fun `a span whose body throws is still recorded`() {
        // Losing the measurement exactly when a stage fails removes it when most wanted.
        val clock = ManualDiagnosticsClock()
        val recorder = TimingSpanRecorder(clock)

        assertThrows(IllegalStateException::class.java) {
            recorder.record<Unit>(Subsystem.ANALYZER, "chroma") {
                clock.advanceMillis(7)
                error("stage failed")
            }
        }

        assertEquals(7_000_000L, recorder.snapshot().span(Subsystem.ANALYZER, "chroma")!!.totalNanos)
    }

    @Test
    fun `CPU time is attributed per subsystem, not as one aggregate`() {
        // §100.1's central requirement: separate bars, so a regression is diagnosed against
        // the subsystem that actually caused it.
        val clock = ManualDiagnosticsClock()
        val recorder = TimingSpanRecorder(clock)

        recorder.record(Subsystem.ANALYZER, "fft") { clock.advanceMillis(10) }
        recorder.record(Subsystem.ANALYZER, "bands") { clock.advanceMillis(4) }
        recorder.record(Subsystem.AUDIO, "decode") { clock.advanceMillis(6) }

        val bySubsystem = recorder.snapshot().bySubsystem()
        assertEquals(14_000_000L, bySubsystem[Subsystem.ANALYZER])
        assertEquals(6_000_000L, bySubsystem[Subsystem.AUDIO])
        assertNull(bySubsystem[Subsystem.RENDERER]) // never ran: absent, not a misleading zero
    }

    @Test
    fun `two subsystems may use the same span name without colliding`() {
        val clock = ManualDiagnosticsClock()
        val recorder = TimingSpanRecorder(clock)

        recorder.record(Subsystem.AUDIO, "read") { clock.advanceMillis(3) }
        recorder.record(Subsystem.ASSETS, "read") { clock.advanceMillis(9) }

        assertEquals(3_000_000L, recorder.snapshot().span(Subsystem.AUDIO, "read")!!.totalNanos)
        assertEquals(9_000_000L, recorder.snapshot().span(Subsystem.ASSETS, "read")!!.totalNanos)
    }

    @Test
    fun `an explicitly opened span is idempotent on close`() {
        val clock = ManualDiagnosticsClock()
        val recorder = TimingSpanRecorder(clock)

        val span = recorder.begin(Subsystem.PROJECT, "apply-command")
        clock.advanceMillis(2)
        span.close()
        clock.advanceMillis(100)
        span.close() // must not double-count, and must not record the extra 100ms

        val stats = recorder.snapshot().span(Subsystem.PROJECT, "apply-command")!!
        assertEquals(1, stats.count)
        assertEquals(2_000_000L, stats.totalNanos)
    }

    @Test
    fun `an unnamed span is rejected`() {
        val recorder = TimingSpanRecorder(ManualDiagnosticsClock())
        assertThrows(IllegalArgumentException::class.java) { recorder.begin(Subsystem.AUDIO, "  ") }
    }

    @Test
    fun `spans are ordered by subsystem then name for stable reporting`() {
        val clock = ManualDiagnosticsClock()
        val recorder = TimingSpanRecorder(clock)

        recorder.record(Subsystem.ANALYZER, "zeta") { clock.advanceMillis(1) }
        recorder.record(Subsystem.AUDIO, "alpha") { clock.advanceMillis(1) }
        recorder.record(Subsystem.ANALYZER, "alpha") { clock.advanceMillis(1) }

        assertEquals(
            listOf(
                Subsystem.AUDIO to "alpha",
                Subsystem.ANALYZER to "alpha",
                Subsystem.ANALYZER to "zeta",
            ),
            recorder.snapshot().spans.map { it.subsystem to it.name },
        )
    }

    @Test
    fun `reset clears the breakdown`() {
        val clock = ManualDiagnosticsClock()
        val recorder = TimingSpanRecorder(clock)
        recorder.record(Subsystem.AUDIO, "decode") { clock.advanceMillis(1) }

        recorder.reset()

        assertTrue(recorder.snapshot().isEmpty)
    }

    @Test
    fun `concurrent recording from many threads loses no measurements`() {
        // §101 runs subsystems on separate threads; the recorder is reached from all of them.
        val recorder = TimingSpanRecorder(SystemDiagnosticsClock)
        val threads = (0 until 8).map {
            Thread {
                repeat(500) { recorder.record(Subsystem.AUDIO, "decode") { } }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(4_000L, recorder.snapshot().span(Subsystem.AUDIO, "decode")!!.count)
    }
}
