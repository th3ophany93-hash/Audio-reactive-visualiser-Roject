package com.arvs.app.debug

import com.arvs.core.assets.PeakBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the arithmetic a screenshot would not reveal as wrong. */
class WaveformGeometryTest {

    @Test
    fun `amplitude plus one is drawn at the top, minus one at the bottom`() {
        // y grows downward on screen. Getting this backwards produces a waveform that looks
        // entirely plausible and is vertically mirrored.
        val columns = WaveformGeometry.layout(listOf(PeakBucket(-1f, 1f)), width = 10f, height = 100f)

        assertEquals(0f, columns.single().top, 1e-4f)
        assertEquals(100f, columns.single().bottom, 1e-4f)
    }

    @Test
    fun `a half-scale bucket occupies half the canvas, centred`() {
        val columns = WaveformGeometry.layout(listOf(PeakBucket(-0.5f, 0.5f)), 10f, 100f)

        assertEquals(25f, columns.single().top, 1e-4f)
        assertEquals(75f, columns.single().bottom, 1e-4f)
    }

    @Test
    fun `asymmetric buckets draw asymmetrically`() {
        // The reason §82.1's buckets keep both extents: a column drawn from abs alone would be
        // symmetric about the centre and would erase this.
        val columns = WaveformGeometry.layout(listOf(PeakBucket(-0.1f, 0.9f)), 10f, 100f)

        val column = columns.single()
        assertEquals(5f, column.top, 1e-4f)
        assertEquals(55f, column.bottom, 1e-4f)
        assertTrue("the column must not be centred", column.top + column.bottom != 100f)
    }

    @Test
    fun `silence draws a hairline, not a gap`() {
        // A gap reads as "no audio here", which is a different and wrong claim. Silence is
        // content, and a trim editor rendering it as absence invites a cut in the wrong place.
        val columns = WaveformGeometry.layout(listOf(PeakBucket(0f, 0f)), 10f, 100f, minimumColumnHeight = 2f)

        assertEquals(2f, columns.single().height, 1e-4f)
        assertEquals(50f, (columns.single().top + columns.single().bottom) / 2f, 1e-4f)
    }

    @Test
    fun `columns are evenly spaced and centred in their slot`() {
        val columns = WaveformGeometry.layout(List(4) { PeakBucket(-1f, 1f) }, width = 100f, height = 50f)

        assertEquals(listOf(12.5f, 37.5f, 62.5f, 87.5f), columns.map { it.x })
    }

    @Test
    fun `out-of-range bucket values are clamped rather than drawn off-canvas`() {
        val columns = WaveformGeometry.layout(listOf(PeakBucket(-4f, 4f)), 10f, 100f)

        assertEquals(0f, columns.single().top, 1e-4f)
        assertEquals(100f, columns.single().bottom, 1e-4f)
    }

    @Test
    fun `a degenerate canvas or empty input yields no columns`() {
        assertTrue(WaveformGeometry.layout(emptyList(), 100f, 50f).isEmpty())
        assertTrue(WaveformGeometry.layout(listOf(PeakBucket(0f, 1f)), 0f, 50f).isEmpty())
        assertTrue(WaveformGeometry.layout(listOf(PeakBucket(0f, 1f)), 100f, 0f).isEmpty())
    }

    @Test
    fun `inverted bucket extents are normalised instead of drawing backwards`() {
        val columns = WaveformGeometry.layout(listOf(PeakBucket(0.9f, -0.9f)), 10f, 100f)
        assertTrue(columns.single().top < columns.single().bottom)
    }

    // --- playhead / trim mapping -----------------------------------------------------------

    @Test
    fun `frame to x spans the visible window`() {
        assertEquals(0f, WaveformGeometry.frameToX(1_000, 1_000, 2_000, 500f), 1e-4f)
        assertEquals(250f, WaveformGeometry.frameToX(1_500, 1_000, 2_000, 500f), 1e-4f)
        assertEquals(500f, WaveformGeometry.frameToX(2_000, 1_000, 2_000, 500f), 1e-4f)
    }

    @Test
    fun `a playhead outside the window parks at the edge rather than vanishing`() {
        assertEquals(0f, WaveformGeometry.frameToX(0, 1_000, 2_000, 500f), 1e-4f)
        assertEquals(500f, WaveformGeometry.frameToX(99_999, 1_000, 2_000, 500f), 1e-4f)
    }

    @Test
    fun `x to frame round-trips through frame to x`() {
        val start = 48_000L
        val end = 96_000L
        listOf(48_000L, 60_000L, 72_000L, 95_999L).forEach { frame ->
            val x = WaveformGeometry.frameToX(frame, start, end, 1_000f)
            assertEquals(frame.toDouble(), WaveformGeometry.xToFrame(x, start, end, 1_000f).toDouble(), 60.0)
        }
    }

    @Test
    fun `a degenerate window does not divide by zero`() {
        assertEquals(0f, WaveformGeometry.frameToX(500, 1_000, 1_000, 500f), 0f)
        assertEquals(1_000L, WaveformGeometry.xToFrame(250f, 1_000, 1_000, 500f))
        assertEquals(1_000L, WaveformGeometry.xToFrame(250f, 1_000, 2_000, 0f))
    }
}
