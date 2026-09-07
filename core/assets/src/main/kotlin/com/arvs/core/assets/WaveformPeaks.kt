package com.arvs.core.assets

import com.arvs.core.time.TimeSpan

/**
 * A multi-resolution waveform peak pyramid — tier 2 of §82.1's three-tier cache.
 *
 * §16 is binding about why this exists: *"immediate visual feedback"* while dragging trim
 * handles is satisfied by reading these peaks, **never** by re-opening the SAF-backed asset
 * per redraw or scrub frame. §16 states plainly that an implementation which re-decodes on
 * every zoom or scrub "does not meet this requirement".
 *
 * A pyramid rather than one flat array of peaks, because the two ends of the zoom range have
 * opposite needs: fully zoomed out, a five-minute track has to become a few hundred columns,
 * and reducing 14 million samples per redraw is exactly the per-frame work §16 forbids.
 * Storing progressively coarser levels means every zoom level reads roughly the same small
 * number of buckets.
 *
 * Each bucket keeps **both** minimum and maximum, not a single absolute peak. A waveform drawn
 * from `abs` alone is mirror-symmetric by construction, which silently erases the asymmetry of
 * real percussive material — the drawing looks plausible and is wrong.
 *
 * Peaks are computed from §17.3's canonical mono signal. (§17.1 lists waveform peaks as
 * analysis *stage 1*; that is a statement about scheduling priority. The code lives here
 * because §82.1 assigns tier-2 ownership to the asset layer, and because §116.1's graph makes
 * `core:assets` and `audio:analysis` deliberately disjoint.)
 */
public class WaveformPeaks(
    public val sampleRateHz: Int,
    public val totalFrames: Long,
    /** Coarsening levels, ordered finest first. Level 0 is the base resolution. */
    public val levels: List<PeakLevel>,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(totalFrames >= 0) { "totalFrames must not be negative: $totalFrames" }
        require(levels.isNotEmpty()) { "a peak pyramid needs at least a base level" }
        levels.zipWithNext { finer, coarser ->
            require(coarser.framesPerBucket > finer.framesPerBucket) {
                "levels must coarsen strictly: ${finer.framesPerBucket} then ${coarser.framesPerBucket}"
            }
        }
    }

    public val duration: TimeSpan get() = TimeSpan(totalFrames * 1_000_000L / sampleRateHz)

    public val baseLevel: PeakLevel get() = levels.first()

    /**
     * The coarsest level that still yields at least [minimumBuckets] buckets over
     * [frameCount] frames.
     *
     * Coarsest-that-suffices, not finest-available: reading more buckets than the display has
     * columns is work whose result is thrown away, and doing it every scrub frame is the cost
     * §16 rules out.
     */
    public fun levelFor(frameCount: Long, minimumBuckets: Int): PeakLevel {
        require(minimumBuckets > 0) { "minimumBuckets must be positive: $minimumBuckets" }
        if (frameCount <= 0) return baseLevel
        return levels.lastOrNull { level -> frameCount / level.framesPerBucket >= minimumBuckets }
            ?: baseLevel
    }

    /**
     * Buckets covering `[startFrame, endFrame)`, at most [maxBuckets] of them.
     *
     * This is the call a waveform view makes on every redraw, and it touches nothing but
     * memory already loaded — no file, no decoder, no SAF URI.
     */
    public fun bucketsFor(startFrame: Long, endFrame: Long, maxBuckets: Int): List<PeakBucket> {
        require(maxBuckets > 0) { "maxBuckets must be positive: $maxBuckets" }
        val from = startFrame.coerceIn(0, totalFrames)
        val to = endFrame.coerceIn(from, totalFrames)
        if (to == from) return emptyList()

        val level = levelFor(to - from, maxBuckets)
        val firstBucket = (from / level.framesPerBucket).toInt()
        val lastBucket = ((to - 1) / level.framesPerBucket).toInt().coerceAtMost(level.bucketCount - 1)
        if (lastBucket < firstBucket) return emptyList()

        val available = lastBucket - firstBucket + 1
        if (available <= maxBuckets) {
            return (firstBucket..lastBucket).map { level.bucket(it) }
        }

        // More buckets than columns: merge neighbours rather than sampling every Nth, which
        // would drop transients entirely — the loudest bucket in a group is the one a
        // waveform most needs to show.
        val out = ArrayList<PeakBucket>(maxBuckets)
        for (column in 0 until maxBuckets) {
            val groupStart = firstBucket + (available.toLong() * column / maxBuckets).toInt()
            val groupEnd = firstBucket + (available.toLong() * (column + 1) / maxBuckets).toInt()
            var minimum = Float.MAX_VALUE
            var maximum = -Float.MAX_VALUE
            for (index in groupStart until maxOf(groupEnd, groupStart + 1)) {
                minimum = minOf(minimum, level.minimumAt(index))
                maximum = maxOf(maximum, level.maximumAt(index))
            }
            out += PeakBucket(minimum, maximum)
        }
        return out
    }
}

/** One resolution of the pyramid: [framesPerBucket] source frames reduced to a min/max pair. */
public class PeakLevel(
    public val framesPerBucket: Int,
    private val minimums: FloatArray,
    private val maximums: FloatArray,
) {
    init {
        require(framesPerBucket > 0) { "framesPerBucket must be positive: $framesPerBucket" }
        require(minimums.size == maximums.size) {
            "min and max arrays must be the same length: ${minimums.size} vs ${maximums.size}"
        }
    }

    public val bucketCount: Int get() = minimums.size

    public fun minimumAt(index: Int): Float = minimums[index]
    public fun maximumAt(index: Int): Float = maximums[index]
    public fun bucket(index: Int): PeakBucket = PeakBucket(minimums[index], maximums[index])

    /** Copies for serialisation. Returned as copies so a cached pyramid cannot be mutated. */
    internal fun minimumsCopy(): FloatArray = minimums.copyOf()
    internal fun maximumsCopy(): FloatArray = maximums.copyOf()
}

/** The vertical extent of one waveform column. */
public data class PeakBucket(public val minimum: Float, public val maximum: Float) {
    /** Height of the column, for a symmetric renderer that wants a single figure. */
    public val magnitude: Float get() = maxOf(maximum, -minimum)
}
