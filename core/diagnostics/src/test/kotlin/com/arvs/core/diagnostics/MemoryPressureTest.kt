package com.arvs.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins §98.1's memory-pressure response, including the half that matters most: the things
 * that must **never** be released.
 */
class MemoryPressureTest {

    private class FakeResource(
        override val resourceName: String,
        override val regenerationCost: RegenerationCost,
        private val bytes: Long = 1_024,
    ) : RegenerableResource {
        var releasedAt: MemoryPressureLevel? = null
        override fun releaseUnderPressure(level: MemoryPressureLevel): Long {
            releasedAt = level
            return bytes
        }
    }

    @Test
    fun `RegenerationCost cannot express an irrecoverable resource`() {
        // This is how §98.1's "never release ProjectState or undo history" is enforced:
        // not by a rule someone must remember, but by a sentence the API cannot say. A
        // resource that cannot be regenerated has no cost to declare, so it cannot be
        // registered, so it cannot be released. Adding a value here would break that.
        assertEquals(
            listOf(RegenerationCost.CHEAP, RegenerationCost.MODERATE, RegenerationCost.EXPENSIVE),
            RegenerationCost.entries.toList(),
        )
        assertTrue(RegenerationCost.entries.none { it.name.contains("IRRECOVERABLE") })
    }

    @Test
    fun `low pressure releases only cheap-to-regenerate resources`() {
        // §98.1: non-visible GPU resources and prefetched analysis pages go first.
        val cheap = FakeResource("prefetched-analysis-pages", RegenerationCost.CHEAP)
        val moderate = FakeResource("cached-analysis-pages", RegenerationCost.MODERATE)
        val expensive = FakeResource("decoded-pcm", RegenerationCost.EXPENSIVE)

        val dispatcher = MemoryPressureDispatcher()
        listOf(cheap, moderate, expensive).forEach { dispatcher.register(it) }

        dispatcher.dispatch(MemoryPressureLevel.LOW)

        assertEquals(MemoryPressureLevel.LOW, cheap.releasedAt)
        assertEquals(null, moderate.releasedAt)
        assertEquals(null, expensive.releasedAt)
    }

    @Test
    fun `pressure escalates proportionally`() {
        assertTrue(MemoryPressureLevel.LOW.releases(RegenerationCost.CHEAP))
        assertFalse(MemoryPressureLevel.LOW.releases(RegenerationCost.MODERATE))

        assertTrue(MemoryPressureLevel.MODERATE.releases(RegenerationCost.CHEAP))
        assertTrue(MemoryPressureLevel.MODERATE.releases(RegenerationCost.MODERATE))
        assertFalse(MemoryPressureLevel.MODERATE.releases(RegenerationCost.EXPENSIVE))

        RegenerationCost.entries.forEach { cost ->
            assertTrue(MemoryPressureLevel.CRITICAL.releases(cost))
        }
    }

    @Test
    fun `critical pressure releases everything registered, cheapest first`() {
        val order = mutableListOf<String>()
        val dispatcher = MemoryPressureDispatcher()

        // Registered in the *wrong* order on purpose: release order must come from the
        // declared cost, not from registration order.
        listOf(
            RegenerationCost.EXPENSIVE to "decoded-pcm",
            RegenerationCost.CHEAP to "gpu-offscreen-layers",
            RegenerationCost.MODERATE to "waveform-peaks",
        ).forEach { (cost, name) ->
            dispatcher.register(object : RegenerableResource {
                override val resourceName = name
                override val regenerationCost = cost
                override fun releaseUnderPressure(level: MemoryPressureLevel): Long {
                    order += name
                    return 512
                }
            })
        }

        val result = dispatcher.dispatch(MemoryPressureLevel.CRITICAL)

        assertEquals(listOf("gpu-offscreen-layers", "waveform-peaks", "decoded-pcm"), order)
        assertEquals(1_536L, result.totalBytesFreed)
    }

    @Test
    fun `the trim result records what was freed, for the §98 report`() {
        val dispatcher = MemoryPressureDispatcher()
        dispatcher.register(FakeResource("prefetched-analysis-pages", RegenerationCost.CHEAP, bytes = 4_096))

        val result = dispatcher.dispatch(MemoryPressureLevel.LOW)

        assertEquals(MemoryPressureLevel.LOW, result.level)
        assertEquals(4_096L, result.totalBytesFreed)
        assertEquals("prefetched-analysis-pages", result.released.single().resourceName)
    }

    @Test
    fun `a resource that frees nothing is not reported as released`() {
        val dispatcher = MemoryPressureDispatcher()
        dispatcher.register(FakeResource("already-empty", RegenerationCost.CHEAP, bytes = 0))

        val result = dispatcher.dispatch(MemoryPressureLevel.CRITICAL)

        assertTrue(result.released.isEmpty())
        assertEquals(0L, result.totalBytesFreed)
    }

    @Test
    fun `a throwing resource does not abort the trim of the others`() {
        // Under memory pressure, giving up on the remaining releases is how the process
        // gets killed.
        val survivor = FakeResource("survivor", RegenerationCost.CHEAP, bytes = 2_048)
        val dispatcher = MemoryPressureDispatcher()
        dispatcher.register(object : RegenerableResource {
            override val resourceName = "explodes"
            override val regenerationCost = RegenerationCost.CHEAP
            override fun releaseUnderPressure(level: MemoryPressureLevel): Long =
                throw OutOfMemoryError("no headroom even to free")
        })
        dispatcher.register(survivor)

        val result = dispatcher.dispatch(MemoryPressureLevel.CRITICAL)

        assertEquals(MemoryPressureLevel.CRITICAL, survivor.releasedAt)
        assertEquals(2_048L, result.totalBytesFreed)
    }

    @Test
    fun `unregistering removes a resource from future trims`() {
        val resource = FakeResource("transient", RegenerationCost.CHEAP)
        val dispatcher = MemoryPressureDispatcher()
        val registration = dispatcher.register(resource)

        assertEquals(1, dispatcher.registeredCount())
        registration.unregister()
        assertEquals(0, dispatcher.registeredCount())

        dispatcher.dispatch(MemoryPressureLevel.CRITICAL)
        assertEquals(null, resource.releasedAt)
    }

    @Test
    fun `a trim logs against the §99 Project subsystem when a logger is supplied`() {
        val sink = RecordingLogSink()
        val logger = Logger(LoggingPolicy.DEBUG, ManualDiagnosticsClock()).also { it.addSink(sink) }
        val dispatcher = MemoryPressureDispatcher(logger.forSubsystem(Subsystem.PROJECT))
        dispatcher.register(FakeResource("cache", RegenerationCost.CHEAP, bytes = 128))

        dispatcher.dispatch(MemoryPressureLevel.MODERATE)

        val event = sink.snapshot().single()
        assertEquals("memory-trim", event.key)
        assertTrue(event.message.contains("128 bytes"))
    }
}
