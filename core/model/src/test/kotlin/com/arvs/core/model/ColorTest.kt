package com.arvs.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins §90.1's linear-light rule.
 *
 * The test that actually earns its place here is
 * [linear interpolation differs measurably from naive sRGB interpolation] — it proves the
 * conversion is doing real work. Round-trip tests alone would still pass if both transfer
 * functions were the identity.
 */
class ColorTest {

    private val tolerance = 1e-4f

    @Test
    fun `transfer function endpoints are exact`() {
        assertEquals(0.0f, srgbToLinearChannel(0.0f), 0.0f)
        assertEquals(1.0f, srgbToLinearChannel(1.0f), tolerance)
        assertEquals(0.0f, linearToSrgbChannel(0.0f), 0.0f)
        assertEquals(1.0f, linearToSrgbChannel(1.0f), tolerance)
    }

    @Test
    fun `mid-grey matches the known sRGB value`() {
        // sRGB 0.5 is ~0.2140 in linear light — the canonical sanity value, and the reason
        // "50% grey" looks nothing like 0.5 to a compositor.
        assertEquals(0.21404f, srgbToLinearChannel(0.5f), tolerance)
        assertEquals(0.5f, linearToSrgbChannel(0.21404f), tolerance)
    }

    @Test
    fun `the piecewise-linear toe near black is used`() {
        // Below 0.04045 the transfer function is linear (c / 12.92). A naive pure-power
        // approximation diverges here, which is exactly where banding shows up first.
        assertEquals(0.04f / 12.92f, srgbToLinearChannel(0.04f), 1e-7f)
        assertEquals(0.001f * 12.92f, linearToSrgbChannel(0.001f), 1e-7f)
    }

    @Test
    fun `srgb to linear and back round-trips across the range`() {
        var value = 0.0f
        while (value <= 1.0f) {
            val roundTripped = linearToSrgbChannel(srgbToLinearChannel(value))
            assertEquals("round-trip failed at $value", value, roundTripped, tolerance)
            value += 0.01f
        }
    }

    @Test
    fun `colour round-trips through both spaces`() {
        val original = SrgbColor(0.2f, 0.6f, 0.9f, 0.75f)
        val roundTripped = original.toLinear().toSrgb()

        assertEquals(original.red, roundTripped.red, tolerance)
        assertEquals(original.green, roundTripped.green, tolerance)
        assertEquals(original.blue, roundTripped.blue, tolerance)
        assertEquals(original.alpha, roundTripped.alpha, tolerance)
    }

    @Test
    fun `alpha is never transfer-encoded`() {
        // Alpha is a coverage fraction, already linear. Gamma-encoding it would make
        // cross-fades wrong in a way that is easy to miss and hard to diagnose.
        val srgb = SrgbColor(0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, srgb.toLinear().alpha, 0.0f)
        assertEquals(0.5f, LinearColor(0.1f, 0.1f, 0.1f, 0.5f).toSrgb().alpha, 0.0f)
    }

    @Test
    fun `linear interpolation differs measurably from naive sRGB interpolation`() {
        // This is the whole point of §90.1. Blending black to white in linear light and
        // converting back gives ~0.7354 in sRGB, not 0.5 — the midpoint a naive sRGB lerp
        // would produce. If this assertion ever fails, compositing has silently regressed
        // into the wrong space.
        val black = SrgbColor.BLACK.toLinear()
        val white = SrgbColor.WHITE.toLinear()

        val correctMidpoint = black.lerp(white, 0.5f).toSrgb().red
        val naiveMidpoint = 0.5f

        assertEquals(0.7354f, correctMidpoint, 1e-3f)
        assertTrue(
            "linear-space blending must differ from naive sRGB blending",
            abs(correctMidpoint - naiveMidpoint) > 0.2f,
        )
    }

    @Test
    fun `interpolation endpoints are exact`() {
        val from = LinearColor(0.1f, 0.2f, 0.3f, 1.0f)
        val to = LinearColor(0.7f, 0.8f, 0.9f, 0.5f)

        assertEquals(from, from.lerp(to, 0.0f))
        assertEquals(to.red, from.lerp(to, 1.0f).red, tolerance)
        assertEquals(to.alpha, from.lerp(to, 1.0f).alpha, tolerance)
    }

    @Test
    fun `hex parsing handles both RGB and ARGB forms`() {
        val opaqueRed = SrgbColor.ofHex("#FF0000")
        assertEquals(1.0f, opaqueRed.red, tolerance)
        assertEquals(0.0f, opaqueRed.green, tolerance)
        assertEquals(1.0f, opaqueRed.alpha, tolerance)

        val halfGreen = SrgbColor.ofHex("#8000FF00")
        assertEquals(1.0f, halfGreen.green, tolerance)
        assertEquals(128f / 255f, halfGreen.alpha, tolerance)
    }
}
