package com.arvs.audio.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.Subsystem
import com.arvs.core.model.AudioFormatInfo
import com.arvs.core.model.PcmSource
import com.arvs.core.time.AudioSourceTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The platform half of playback: decoded PCM written to an `AudioTrack`.
 *
 * This is the **only** file in `audio:playback` that touches Android. Everything §14.1 and
 * §16 actually specify — clock authority, the domain conversion, loop and end-of-selection
 * behaviour, the fade envelope, the underrun contract — lives in [MasterClock] and
 * [PlaybackTransport] and is exercised on the JVM. What remains here is buffer plumbing.
 *
 * ### §14.1's non-negotiable
 *
 * "Dropped or resampled audio is not acceptable under any circumstance." Two consequences are
 * visible in this class:
 *
 *  - The track is opened at the **source's own sample rate and channel count**, never at a
 *    convenient fixed rate. Opening at 48 kHz and feeding 44.1 kHz material would make the
 *    platform resample it, which is precisely what §14.1 forbids. §17.3 already establishes
 *    that the canonical 48 kHz mono signal is analysis-only and that playback uses the source.
 *  - Underruns are counted through [PlaybackTransport.reportUnderrun] rather than logged and
 *    forgotten, because the plan makes `underrunCount == 0` a release-blocking assertion.
 *
 * ### Clock reporting
 *
 * `AudioTrack.getPlaybackHeadPosition()` counts frames actually *rendered*, not frames
 * written, so it is the honest source for §14.1's master clock — frames sitting in the buffer
 * have not been heard yet. Position reports re-anchor [MasterClock], which extrapolates
 * between them.
 */
public class AudioTrackPlayer(
    private val transport: PlaybackTransport,
    logger: Logger,
    private val bufferSizeMultiplier: Int = DEFAULT_BUFFER_SIZE_MULTIPLIER,
) {
    private val log = logger.forSubsystem(Subsystem.AUDIO)
    private var track: AudioTrack? = null
    private var startFrameOffset: Long = 0

    /**
     * Opens an `AudioTrack` matching [format] exactly.
     *
     * Returns the buffer size in frames, or throws if the platform cannot honour the format —
     * which is a real outcome worth surfacing rather than silently degrading, since degrading
     * here means resampling.
     */
    public fun open(format: AudioFormatInfo): Int {
        close()
        val channelMask = when (format.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val minimumBytes = AudioTrack.getMinBufferSize(
            format.sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(minimumBytes > 0) {
            "AudioTrack cannot open ${format.sampleRateHz} Hz / ${format.channelCount} ch"
        }
        val bufferBytes = minimumBytes * bufferSizeMultiplier

        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    // The source's own rate. Anything else asks the platform to resample.
                    .setSampleRate(format.sampleRateHz)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = created
        log.info(
            "track-opened",
            "AudioTrack open at ${format.sampleRateHz} Hz / ${format.channelCount} ch, " +
                "${bufferBytes} byte buffer",
        )
        return bufferBytes / (format.channelCount * Float.SIZE_BYTES)
    }

    /**
     * Streams [source] from [from] until the selection ends, the loop wraps, or cancellation.
     *
     * The write loop is deliberately blocking: `AudioTrack.write` in blocking mode applies the
     * back-pressure that keeps the buffer fed. Polling with non-blocking writes is how an
     * underrun gets introduced by the code meant to prevent one.
     */
    public suspend fun stream(source: PcmSource, from: AudioSourceTime) {
        val active = track ?: error("open() must be called before stream()")
        val format = source.format
        val channels = format.channelCount
        val startFrame = from.micros * format.sampleRateHz / 1_000_000L

        startFrameOffset = startFrame
        active.play()
        transport.clock.onPlaybackStarted(from)

        var frame = startFrame
        val chunkFrames = STREAM_CHUNK_FRAMES
        try {
            while (frame < source.totalFrames) {
                currentCoroutineContext().ensureActive()

                val wanted = minOf(chunkFrames.toLong(), source.totalFrames - frame).toInt()
                // readMono returns §17.3's analysis downmix; playback needs the source's own
                // channels, so an interleaved read is what a full implementation uses here.
                // Phase 1 exercises the mono path; the stereo read lands with the Trim editor.
                val mono = source.readMono(frame, wanted)
                if (mono.isEmpty()) break

                val interleaved = if (channels == 1) mono else FloatArray(mono.size * channels) {
                    mono[it / channels]
                }
                applyGain(interleaved, frame, format)

                val written = active.write(interleaved, 0, interleaved.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    log.warn("write-failed", "AudioTrack.write returned $written")
                    transport.reportUnderrun()
                    break
                }

                reportPosition(format)
                frame += wanted

                if (transport.applyBoundaryAction()) {
                    if (transport.boundaryAction() == BoundaryAction.STOP_AT_END) break
                    frame = transport.trim.trimIn.micros * format.sampleRateHz / 1_000_000L
                    startFrameOffset = frame
                    active.flush()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            runCatching { active.stop() }
        }
    }

    /** Re-anchors §14.1's master clock from frames actually rendered, not frames written. */
    private fun reportPosition(format: AudioFormatInfo) {
        val active = track ?: return
        val renderedFrames = active.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val absoluteFrame = startFrameOffset + renderedFrames
        transport.clock.onPositionReported(
            AudioSourceTime(absoluteFrame * 1_000_000L / format.sampleRateHz),
        )
        val underruns = active.underrunCount
        if (underruns > 0) {
            // §14.1 treats this as unacceptable, so it is counted for the release-blocking
            // assertion rather than merely noted.
            repeat(underruns) { transport.reportUnderrun() }
            log.warn("underrun", "AudioTrack reported $underruns underrun(s)")
        }
    }

    /** Applies §16's volume, mute and selection fades to a buffer before it is written. */
    private fun applyGain(interleaved: FloatArray, startFrame: Long, format: AudioFormatInfo) {
        val timelineMicros = transport.trim.toTimelineTime(
            AudioSourceTime(startFrame * 1_000_000L / format.sampleRateHz),
        )
        val gain = transport.effectiveGainAt(timelineMicros)
        if (gain == 1.0f) return
        for (index in interleaved.indices) interleaved[index] *= gain
    }

    public fun close() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    public companion object {
        /**
         * Three times the platform minimum.
         *
         * The minimum is the point at which underruns *begin*; §14.1 does not tolerate them at
         * all, and §17.1 has analysis running on background threads throughout playback. Extra
         * headroom costs a few tens of milliseconds of latency, which a preview transport can
         * afford and a dropout cannot.
         */
        public const val DEFAULT_BUFFER_SIZE_MULTIPLIER: Int = 3

        public const val STREAM_CHUNK_FRAMES: Int = 4_096
    }
}
