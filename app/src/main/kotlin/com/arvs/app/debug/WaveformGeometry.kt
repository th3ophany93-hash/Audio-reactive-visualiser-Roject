package com.arvs.app.debug

import com.arvs.core.assets.PeakBucket

/**
 * Maps §82.1 tier-2 peak buckets onto drawing coordinates.
 *
 * Deliberately a pure function, separate from the Compose code that calls it. Everything here
 * is arithmetic that can be wrong in ways a screenshot will not reveal — an inverted vertical
 * axis, silence collapsing to nothing, a rounding rule that leaves a gap at one edge — so it
 * is unit-tested, while the composable around it stays thin enough to be reviewed by eye.
 *
 * Coordinates follow the screen convention: y grows downward, so amplitude `+1` is at `y = 0`
 * and `-1` at `y = height`.
 */
public object WaveformGeometry {

    /**
     * One drawn column: a vertical segment from [top] to [bottom] at [x].
     *
     * Both extents are kept rather than a single height, because §82.1's buckets carry a
     * minimum and a maximum precisely so asymmetric material draws asymmetrically.
     */
    public data class Column(val x: Float, val top: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    /**
     * Lays [buckets] out across a [width] × [height] canvas.
     *
     * [minimumColumnHeight] keeps a silent passage visible as a hairline through the middle
     * rather than a gap. A gap reads as "no audio here", which is a different and wrong claim:
     * silence is content, and a trim editor that renders it as absence invites the user to cut
     * in the wrong place.
     */
    public fun layout(
        buckets: List<PeakBucket>,
        width: Float,
        height: Float,
        minimumColumnHeight: Float = 1.0f,
    ): List<Column> {
        if (buckets.isEmpty() || width <= 0f || height <= 0f) return emptyList()

        val columnWidth = width / buckets.size
        val centre = height / 2f

        return buckets.mapIndexed { index, bucket ->
            // Amplitude +1 is at the top of the canvas: y grows downward on screen, so the
            // sign has to be flipped exactly once, here, rather than at each draw site.
            var top = centre - bucket.maximum.coerceIn(-1f, 1f) * centre
            var bottom = centre - bucket.minimum.coerceIn(-1f, 1f) * centre
            if (bottom < top) {
                val swap = top
                top = bottom
                bottom = swap
            }
            if (bottom - top < minimumColumnHeight) {
                val half = minimumColumnHeight / 2f
                val middle = (top + bottom) / 2f
                top = middle - half
                bottom = middle + half
            }
            Column(x = index * columnWidth + columnWidth / 2f, top = top, bottom = bottom)
        }
    }

    /**
     * X position of the frame [frame] within `[startFrame, endFrame)` on a canvas of [width].
     *
     * Used for the playhead and the trim handles. Clamped rather than allowed off-canvas, so a
     * playhead outside the visible window parks at the edge instead of vanishing.
     */
    public fun frameToX(frame: Long, startFrame: Long, endFrame: Long, width: Float): Float {
        if (endFrame <= startFrame || width <= 0f) return 0f
        val fraction = (frame - startFrame).toDouble() / (endFrame - startFrame).toDouble()
        return (fraction.coerceIn(0.0, 1.0) * width).toFloat()
    }

    /** Inverse of [frameToX], for turning a touch into a seek position. */
    public fun xToFrame(x: Float, startFrame: Long, endFrame: Long, width: Float): Long {
        if (endFrame <= startFrame || width <= 0f) return startFrame
        val fraction = (x / width).coerceIn(0f, 1f).toDouble()
        return startFrame + (fraction * (endFrame - startFrame)).toLong()
    }
}
