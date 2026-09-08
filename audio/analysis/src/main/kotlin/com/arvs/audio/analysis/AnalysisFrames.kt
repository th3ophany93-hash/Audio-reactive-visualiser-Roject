package com.arvs.audio.analysis

import com.arvs.core.time.AnalysisFraming

/**
 * Applies §17.5's ratified framing to a [CanonicalSignal].
 *
 * §17.5 keeps three quantities deliberately separate — window 2048, hop 480, native rate
 * 100 Hz — and `core:time`'s `AnalysisFraming` already encodes that, with overlap as a derived
 * property that cannot be supplied. This class is only the extraction: given a frame index, the
 * 2048 samples that frame covers.
 *
 * Two §17.5 rules are load-bearing here:
 *
 *  - **Frames are anchored at the window start.** Frame `n` covers samples
 *    `[n·480, n·480 + 2048)`. Centre-anchoring would place frames off the 10 ms grid and
 *    reintroduce precisely the interpolation §17.5 forbids.
 *  - **The tail is zero-padded**, and `N = ceil(totalSamples / hop)`. That makes the frame
 *    count a pure function of asset length — no dependence on buffering, chunking, or decode
 *    order, which is what §9.1's determinism needs.
 *
 * Note the framing is the **analysis** framing, not an FFT-only one: §17.5 calls the hop "the
 * distance between successive analysis frames". Every frame-aligned feature shares it, which is
 * what lets §17.2 store them all on one 100 Hz timeline without realigning anything.
 */
public class AnalysisFrames(
    public val signal: CanonicalSignal,
    public val framing: AnalysisFraming = AnalysisFraming.CANONICAL,
) {
    init {
        require(signal.sampleRateHz == framing.sampleRateHz) {
            "signal is at ${signal.sampleRateHz} Hz but the framing expects ${framing.sampleRateHz} Hz"
        }
    }

    /** §17.5: `N = ceil(totalSamples / hop)`. */
    public val frameCount: Long = framing.frameCount(signal.frameCount.toLong())

    /**
     * Copies frame [frameIndex] into [into], zero-padding past the end of the signal.
     *
     * Takes a caller-owned buffer rather than allocating: a five-minute track is ~30 000
     * frames, and allocating a 2048-float array per frame per stage is 240 MB of garbage for
     * work that reuses one buffer perfectly well.
     */
    public fun readFrame(frameIndex: Long, into: FloatArray) {
        require(frameIndex in 0 until frameCount) {
            "frameIndex $frameIndex outside 0 until $frameCount"
        }
        require(into.size == framing.windowSamples) {
            "buffer must be exactly ${framing.windowSamples} samples, got ${into.size}"
        }

        val start = framing.frameStartSample(frameIndex)
        val available = (signal.frameCount - start).coerceAtMost(framing.windowSamples.toLong())
        if (available <= 0) {
            into.fill(0.0f)
            return
        }
        System.arraycopy(signal.samples, start.toInt(), into, 0, available.toInt())
        if (available < framing.windowSamples) {
            // §17.5's zero-padded tail.
            into.fill(0.0f, available.toInt(), framing.windowSamples)
        }
    }

    /** Allocates a correctly sized frame buffer. */
    public fun newFrameBuffer(): FloatArray = FloatArray(framing.windowSamples)

    /** Runs [block] over every frame in order, reusing a single buffer. */
    public inline fun forEachFrame(block: (frameIndex: Long, frame: FloatArray) -> Unit) {
        val buffer = newFrameBuffer()
        for (index in 0 until frameCount) {
            readFrame(index, buffer)
            block(index, buffer)
        }
    }
}
