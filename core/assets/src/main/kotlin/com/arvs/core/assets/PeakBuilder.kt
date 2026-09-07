package com.arvs.core.assets

import com.arvs.core.model.PcmSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds a [WaveformPeaks] pyramid from decoded PCM.
 *
 * The base level is computed by streaming the source once; every coarser level is then folded
 * from the level below it rather than from the samples again. That makes the whole pyramid one
 * pass over the audio plus a geometrically shrinking tail — the alternative, re-reading the
 * asset per level, would multiply the cost of §17.1's "near-instant" stage 1 by the number of
 * levels for no gain in accuracy: a min/max fold is exact under composition.
 */
public class PeakBuilder(
    /** Frames reduced into one bucket at the finest level. */
    public val baseFramesPerBucket: Int = DEFAULT_BASE_FRAMES_PER_BUCKET,
    /** How many additional, progressively coarser levels to build. */
    public val additionalLevels: Int = DEFAULT_ADDITIONAL_LEVELS,
    /** Frames read from the source per iteration. */
    private val readChunkFrames: Int = DEFAULT_READ_CHUNK_FRAMES,
) {
    init {
        require(baseFramesPerBucket > 0) { "baseFramesPerBucket must be positive" }
        require(additionalLevels >= 0) { "additionalLevels must not be negative" }
        require(readChunkFrames >= baseFramesPerBucket) {
            "readChunkFrames ($readChunkFrames) must cover at least one bucket " +
                "($baseFramesPerBucket), or a bucket would span two reads"
        }
    }

    /** Reduces [source] to a pyramid. Cancellable: this runs during import (§17.1). */
    public suspend fun build(source: PcmSource): WaveformPeaks {
        val totalFrames = source.totalFrames
        val baseBuckets = if (totalFrames == 0L) 0 else
            ((totalFrames + baseFramesPerBucket - 1) / baseFramesPerBucket).toInt()

        val minimums = FloatArray(baseBuckets)
        val maximums = FloatArray(baseBuckets)

        var frame = 0L
        var bucket = 0
        while (frame < totalFrames && bucket < baseBuckets) {
            currentCoroutineContext().ensureActive()
            val wanted = minOf(readChunkFrames.toLong(), totalFrames - frame).toInt()
            val samples = source.readMono(frame, wanted)
            if (samples.isEmpty()) break

            var offset = 0
            while (offset < samples.size && bucket < baseBuckets) {
                val end = minOf(offset + baseFramesPerBucket, samples.size)
                var minimum = Float.MAX_VALUE
                var maximum = -Float.MAX_VALUE
                for (index in offset until end) {
                    val sample = samples[index]
                    if (sample < minimum) minimum = sample
                    if (sample > maximum) maximum = sample
                }
                minimums[bucket] = minimum
                maximums[bucket] = maximum
                bucket++
                offset = end
            }
            frame += samples.size
        }

        val levels = mutableListOf(PeakLevel(baseFramesPerBucket, minimums, maximums))
        var currentMin = minimums
        var currentMax = maximums
        var framesPerBucket = baseFramesPerBucket
        repeat(additionalLevels) {
            if (currentMin.size <= 1) return@repeat
            val foldedCount = (currentMin.size + 1) / 2
            val foldedMin = FloatArray(foldedCount)
            val foldedMax = FloatArray(foldedCount)
            for (index in 0 until foldedCount) {
                val left = index * 2
                val right = left + 1
                foldedMin[index] =
                    if (right < currentMin.size) minOf(currentMin[left], currentMin[right]) else currentMin[left]
                foldedMax[index] =
                    if (right < currentMax.size) maxOf(currentMax[left], currentMax[right]) else currentMax[left]
            }
            framesPerBucket *= 2
            levels += PeakLevel(framesPerBucket, foldedMin, foldedMax)
            currentMin = foldedMin
            currentMax = foldedMax
        }

        return WaveformPeaks(
            sampleRateHz = source.format.sampleRateHz,
            totalFrames = totalFrames,
            levels = levels,
        )
    }

    public companion object {
        /**
         * 256 frames per base bucket — about 5.3 ms at 48 kHz.
         *
         * Chosen against the zoom range rather than picked round: at the deepest useful zoom a
         * trim editor shows a few tens of milliseconds across a phone screen, so a bucket of
         * roughly five milliseconds still gives several columns of detail there, while a
         * five-minute track reduces to ~56 000 base buckets — around 450 KB as float min/max
         * pairs, and ~900 KB for the whole pyramid once the geometric tail is included.
         */
        public const val DEFAULT_BASE_FRAMES_PER_BUCKET: Int = 256

        /**
         * Nine coarser levels above the base, so the coarsest bucket spans 256 · 2⁹ = 131 072
         * frames ≈ 2.7 s at 48 kHz. A five-minute track is then ~110 buckets fully zoomed
         * out — the right order of magnitude for a screen's worth of columns.
         */
        public const val DEFAULT_ADDITIONAL_LEVELS: Int = 9

        public const val DEFAULT_READ_CHUNK_FRAMES: Int = 65_536
    }
}
