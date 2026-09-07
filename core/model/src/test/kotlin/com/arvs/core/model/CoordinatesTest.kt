package com.arvs.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins §13.1's resolution-independent coordinate system.
 *
 * The consequence §13.1 protects is stated plainly there: a project authored at draft
 * resolution and exported at 4K must render identical geometry and identical *relative*
 * effect scale. [effect radius scales with resolution so relative appearance is preserved]
 * is that requirement expressed as arithmetic.
 */
class CoordinatesTest {

    @Test
    fun `height-normalised canvas makes pixelsPerLogicalUnit the render height`() {
        val canvas = Canvas.LANDSCAPE_16_9
        assertEquals(1.0, canvas.logicalHeight, 0.0)
        assertEquals(1080.0, canvas.pixelsPerLogicalUnit(1080), 0.0)
        assertEquals(2160.0, canvas.pixelsPerLogicalUnit(2160), 0.0)
    }

    @Test
    fun `aspect ratios are exact and resolution-independent`() {
        assertEquals(16.0 / 9.0, Canvas.LANDSCAPE_16_9.aspectRatio, 1e-12)
        assertEquals(9.0 / 16.0, Canvas.VERTICAL_9_16.aspectRatio, 1e-12)
        assertEquals(1.0, Canvas.SQUARE_1_1.aspectRatio, 0.0)
    }

    @Test
    fun `effect radius scales with resolution so relative appearance is preserved`() {
        // §13.1's worked consequence: a blur specified in logical units must occupy the
        // same fraction of the frame at every resolution. Here, 2% of frame height.
        val canvas = Canvas.LANDSCAPE_16_9
        val blurRadiusLogical = 0.02

        val atDraft = canvas.logicalToPixels(blurRadiusLogical, actualRenderPixelHeight = 540)
        val at4K = canvas.logicalToPixels(blurRadiusLogical, actualRenderPixelHeight = 2160)

        assertEquals(10.8, atDraft, 1e-9)
        assertEquals(43.2, at4K, 1e-9)

        // Four times the resolution, four times the pixel radius — so it looks the same,
        // rather than four times sharper, which is precisely the failure §13.1 forbids.
        assertEquals(4.0, at4K / atDraft, 1e-12)
    }

    @Test
    fun `logical and pixel conversions round-trip`() {
        val canvas = Canvas.VERTICAL_9_16
        for (height in intArrayOf(360, 720, 1080, 1920, 2160)) {
            val logical = 0.375
            val pixels = canvas.logicalToPixels(logical, height)
            assertEquals(logical, canvas.pixelsToLogical(pixels, height), 1e-12)
        }
    }

    @Test
    fun `pixelsPerLogicalUnit derives from height, not width`() {
        // Deriving from width would distort every pixel-domain effect whenever the render
        // target's aspect ratio differs from the canvas's.
        val wide = Canvas.LANDSCAPE_16_9
        val tall = Canvas.VERTICAL_9_16

        // Same render height, so the same scale factor, despite very different widths.
        assertEquals(
            wide.pixelsPerLogicalUnit(1080),
            tall.pixelsPerLogicalUnit(1080),
            0.0,
        )
    }

    @Test
    fun `degenerate canvases and render sizes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Canvas(0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Canvas(1.0, -1.0) }
        assertThrows(IllegalArgumentException::class.java) {
            Canvas.LANDSCAPE_16_9.pixelsPerLogicalUnit(0)
        }
    }

    @Test
    fun `parameter units and knob classes cover the ratified sets`() {
        // §13.1's three units and §25/§86.1's two knob classes, pinned so a fourth unit or
        // a third knob class cannot appear without this failing.
        assertEquals(3, ParameterUnit.entries.size)
        assertEquals(
            listOf(
                ParameterUnit.LOGICAL_UNIT,
                ParameterUnit.NORMALIZED_01,
                ParameterUnit.RAW_PIXEL,
            ),
            ParameterUnit.entries.toList(),
        )

        assertEquals(2, KnobClass.entries.size)
        assertEquals(
            listOf(KnobClass.QUALITY_KNOB, KnobClass.CONTENT_KNOB),
            KnobClass.entries.toList(),
        )
    }
}
