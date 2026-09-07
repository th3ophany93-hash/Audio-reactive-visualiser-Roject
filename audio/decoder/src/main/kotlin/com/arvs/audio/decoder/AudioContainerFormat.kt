package com.arvs.audio.decoder

/**
 * The container formats §15 requires: WAV, MP3, M4A/AAC, FLAC, OGG.
 *
 * A closed set, for the same reason §97's error taxonomy is one: a format this project does
 * not support must produce a specific `UNSUPPORTED_FORMAT` naming what was found, not a
 * generic decode failure. §97 forbids the generic version outright.
 */
public enum class AudioContainerFormat(public val displayName: String, public val extension: String) {
    WAV("WAV (RIFF/PCM)", "wav"),
    MP3("MP3", "mp3"),
    M4A_AAC("M4A/AAC", "m4a"),
    FLAC("FLAC", "flac"),
    OGG("Ogg", "ogg"),
    ;

    /**
     * Whether this project decodes the format without a platform codec.
     *
     * Only [WAV]. The distinction matters more than it looks: the plan's determinism note
     * establishes that hardware decoders are **not** bit-exact across devices and vendors, so
     * golden analysis vectors are cut only from uncompressed fixtures. That makes a
     * pure-Kotlin PCM path not a convenience but the thing that lets the release-blocking CI
     * tier exist at all — CI has no MediaCodec.
     */
    public val decodableWithoutPlatformCodec: Boolean get() = this == WAV
}

/**
 * Identifies a container from its leading bytes.
 *
 * By content, never by file extension. A SAF document URI frequently has no extension at all,
 * and one that does may be lying; §82 already establishes that identity comes from content.
 */
public object FormatSniffer {

    /** Bytes needed to reach a verdict for every format below. */
    public const val REQUIRED_PREFIX_BYTES: Int = 12

    /** Returns the format, or null if the prefix matches nothing supported. */
    public fun sniff(prefix: ByteArray): AudioContainerFormat? {
        if (prefix.size < 4) return null

        // RIFF....WAVE — the "WAVE" at offset 8 matters: RIFF also fronts AVI and others.
        if (prefix.startsWithAscii("RIFF", 0) && prefix.size >= REQUIRED_PREFIX_BYTES &&
            prefix.startsWithAscii("WAVE", 8)
        ) {
            return AudioContainerFormat.WAV
        }

        if (prefix.startsWithAscii("fLaC", 0)) return AudioContainerFormat.FLAC
        if (prefix.startsWithAscii("OggS", 0)) return AudioContainerFormat.OGG

        // ISO-BMFF: a 4-byte box length then 'ftyp'. Covers M4A and MP4-wrapped AAC.
        if (prefix.size >= 8 && prefix.startsWithAscii("ftyp", 4)) return AudioContainerFormat.M4A_AAC

        // MP3 arrives either behind an ID3v2 tag or as a bare frame header (11 set bits).
        if (prefix.startsWithAscii("ID3", 0)) return AudioContainerFormat.MP3
        if (prefix.size >= 2) {
            val first = prefix[0].toInt() and 0xFF
            val second = prefix[1].toInt() and 0xFF
            if (first == 0xFF && (second and 0xE0) == 0xE0) return AudioContainerFormat.MP3
        }

        return null
    }

    private fun ByteArray.startsWithAscii(text: String, offset: Int): Boolean {
        if (size < offset + text.length) return false
        for (index in text.indices) {
            if (this[offset + index].toInt() and 0xFF != text[index].code) return false
        }
        return true
    }
}
