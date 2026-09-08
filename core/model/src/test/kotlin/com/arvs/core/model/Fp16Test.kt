package com.arvs.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the binary16 codec against IEEE 754's published bit patterns.
 *
 * The expected values are the standard's own, not this implementation's output — a codec checked
 * against itself proves only self-consistency.
 */
class Fp16Test {

    private fun bits(value: Float) = Fp16.fromFloat(value).toInt() and 0xFFFF

    @Test
    fun `matches the published IEEE 754 binary16 bit patterns`() {
        assertEquals(0x0000, bits(0.0f))
        assertEquals(0x8000, bits(-0.0f))
        assertEquals(0x3C00, bits(1.0f))
        assertEquals(0xBC00, bits(-1.0f))
        assertEquals(0x4000, bits(2.0f))
        assertEquals(0x3800, bits(0.5f))
        assertEquals(0x3555, bits(1.0f / 3.0f))
        assertEquals(0x7BFF, bits(65_504.0f))          // largest finite
        assertEquals(0x0400, bits(6.103515625e-5f))    // smallest normal
        assertEquals(0x0001, bits(5.960464477539063e-8f)) // smallest subnormal
    }

    @Test
    fun `decodes the published bit patterns back exactly`() {
        assertEquals(1.0f, Fp16.toFloat(0x3C00.toShort()))
        assertEquals(-2.0f, Fp16.toFloat(0xC000.toShort()))
        assertEquals(0.5f, Fp16.toFloat(0x3800.toShort()))
        assertEquals(65_504.0f, Fp16.toFloat(0x7BFF.toShort()))
        assertEquals(6.103515625e-5f, Fp16.toFloat(0x0400.toShort()))
        assertEquals(5.960464477539063e-8f, Fp16.toFloat(0x0001.toShort()))
    }

    @Test
    fun `exactly representable values round-trip unchanged`() {
        listOf(0.0f, 1.0f, -1.0f, 0.5f, 0.25f, 2.0f, 1024.0f, -0.125f).forEach { value ->
            assertEquals(value, Fp16.quantise(value))
        }
    }

    @Test
    fun `overflow becomes infinity rather than wrapping`() {
        assertEquals(Float.POSITIVE_INFINITY, Fp16.quantise(70_000.0f))
        assertEquals(Float.NEGATIVE_INFINITY, Fp16.quantise(-70_000.0f))
        assertEquals(Float.POSITIVE_INFINITY, Fp16.quantise(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `NaN stays NaN and does not become infinity`() {
        assertTrue(Fp16.quantise(Float.NaN).isNaN())
    }

    @Test
    fun `underflow flushes to zero with the sign preserved`() {
        assertEquals(0.0f, Fp16.quantise(1.0e-10f))

        // -0.0f and 0.0f compare equal, so the sign is checked through the reciprocal: 1/-0.0
        // is negative infinity and 1/+0.0 is positive infinity. Comparing the values directly
        // would pass whichever sign the codec produced.
        assertEquals(Float.NEGATIVE_INFINITY, 1.0f / Fp16.quantise(-1.0e-10f))
        assertEquals(Float.POSITIVE_INFINITY, 1.0f / Fp16.quantise(1.0e-10f))
    }

    @Test
    fun `subnormals are representable, not flushed`() {
        // §17.4 flags the very quiet fixture as the designated precision case; subnormal support
        // is what keeps small magnitudes from vanishing outright.
        val subnormal = 3.0e-8f
        assertTrue(Fp16.quantise(subnormal) > 0.0f)
        assertTrue(Fp16.quantise(subnormal) < Fp16.MIN_NORMAL)
    }

    @Test
    fun `rounding is half-to-even`() {
        // 2049 is exactly between two representable halves at that magnitude (step 2), so it
        // resolves to the even neighbour, 2048 — not upward.
        assertEquals(2_048.0f, Fp16.quantise(2_049.0f))
        // 2051 sits exactly between 2050 and 2052; the even neighbour is 2052.
        assertEquals(2_052.0f, Fp16.quantise(2_051.0f))
    }

    @Test
    fun `relative error stays within half precision's ten-bit mantissa`() {
        // A 10-bit mantissa gives a relative step of 2^-11 = 4.88e-4 for normal values.
        var worst = 0.0
        var value = Fp16.MIN_NORMAL
        while (value < 1_000.0f) {
            worst = maxOf(worst, Fp16.relativeError(value))
            value *= 1.0009f
        }
        assertTrue("worst relative error was $worst", worst <= 4.9e-4)
    }

    @Test
    fun `conversion is deterministic`() {
        val values = FloatArray(10_000) { it * 0.00013f }
        val first = values.map { Fp16.fromFloat(it) }
        repeat(5) { assertEquals(first, values.map { Fp16.fromFloat(it) }) }
    }
}
