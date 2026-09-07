package com.arvs.app.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arvs.core.assets.WaveformPeaks

/**
 * Draws §82.1's tier-2 peaks.
 *
 * Thin by design: every decision that can be wrong without looking wrong lives in
 * [WaveformGeometry] and is unit-tested. What remains here is colour and stroke.
 *
 * Note what this composable does *not* take: no `AssetRegistry`, no `AudioDecoder`, no
 * `PcmSource`. §16 forbids re-opening the source asset per redraw or scrub frame, and the
 * cheapest way to guarantee that is to give the drawing code nothing it could re-open with.
 */
@Composable
fun WaveformView(
    peaks: WaveformPeaks,
    modifier: Modifier = Modifier,
    startFrame: Long = 0,
    endFrame: Long = peaks.totalFrames,
    playheadFrame: Long? = null,
    height: Dp = 160.dp,
    waveColor: Color = Color(0xFF4FC3F7),
    playheadColor: Color = Color(0xFFFF7043),
) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val columnCount = size.width.toInt().coerceAtLeast(1)
        val buckets = peaks.bucketsFor(startFrame, endFrame, maxBuckets = columnCount)
        val columns = WaveformGeometry.layout(buckets, size.width, size.height)

        columns.forEach { column ->
            drawLine(
                color = waveColor,
                start = Offset(column.x, column.top),
                end = Offset(column.x, column.bottom),
                strokeWidth = 1f,
                cap = StrokeCap.Butt,
            )
        }

        playheadFrame?.let { frame ->
            val x = WaveformGeometry.frameToX(frame, startFrame, endFrame, size.width)
            drawLine(
                color = playheadColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
        }
    }
}
