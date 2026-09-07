package com.arvs.core.model

import kotlin.math.pow

/**
 * Colour, and the sRGB ↔ linear-light boundary ratified in MASTER_SPECIFICATION_v3.0 §90.1.
 *
 * §90.1 is binding: **all** compositing happens in linear light — blend modes (§38),
 * gradients (§40) and keyframe colour interpolation (§41.1). Textures convert sRGB → linear
 * on sample; the final composite converts linear → sRGB on write. Getting this wrong is the
 * classic cause of blend modes looking washed-out or too dark.
 *
 * The two spaces are separate types for the same reason the two time domains are (see
 * `core:time`): "composite in linear" is an invariant that survives far better as a type
 * than as a comment. You cannot blend an [SrgbColor] by accident, because it has no blend
 * operation — you must convert first, and the conversion is where the intent becomes
 * explicit.
 *
 * No renderer exists yet (Phase 2, §130). §129 requires this primitive in Phase 1 anyway.
 */

/**
 * A colour in the sRGB transfer space — the space colours are authored, stored and
 * displayed in. Components are 0..1. **Not** valid for arithmetic: convert with [toLinear].
 */
public data class SrgbColor(
    public val red: Float,
    public val green: Float,
    public val blue: Float,
    public val alpha: Float = 1.0f,
) {
    /** sRGB → linear light (§90.1). Alpha is already linear and passes through untouched. */
    public fun toLinear(): LinearColor = LinearColor(
        red = srgbToLinearChannel(red),
        green = srgbToLinearChannel(green),
        blue = srgbToLinearChannel(blue),
        alpha = alpha,
    )

    public companion object {
        public val BLACK: SrgbColor = SrgbColor(0f, 0f, 0f, 1f)
        public val WHITE: SrgbColor = SrgbColor(1f, 1f, 1f, 1f)
        public val TRANSPARENT: SrgbColor = SrgbColor(0f, 0f, 0f, 0f)

        /** Parses `#RRGGBB` or `#AARRGGBB` (§40's HEX input). */
        public fun ofHex(hex: String): SrgbColor {
            val cleaned = hex.removePrefix("#")
            require(cleaned.length == 6 || cleaned.length == 8) {
                "hex colour must be #RRGGBB or #AARRGGBB, got '$hex'"
            }
            val value = cleaned.toLong(16)
            return if (cleaned.length == 6) {
                SrgbColor(
                    red = ((value shr 16) and 0xFF) / 255f,
                    green = ((value shr 8) and 0xFF) / 255f,
                    blue = (value and 0xFF) / 255f,
                    alpha = 1f,
                )
            } else {
                SrgbColor(
                    red = ((value shr 16) and 0xFF) / 255f,
                    green = ((value shr 8) and 0xFF) / 255f,
                    blue = (value and 0xFF) / 255f,
                    alpha = ((value shr 24) and 0xFF) / 255f,
                )
            }
        }
    }
}

/**
 * A colour in linear light — the space every composite, blend and interpolation operates in
 * (§90.1). Components are 0..1 for SDR content; the type does not clamp, so intermediate
 * results stay lossless until the final conversion back to [SrgbColor].
 */
public data class LinearColor(
    public val red: Float,
    public val green: Float,
    public val blue: Float,
    public val alpha: Float = 1.0f,
) {
    /** Linear light → sRGB (§90.1), for output write. */
    public fun toSrgb(): SrgbColor = SrgbColor(
        red = linearToSrgbChannel(red),
        green = linearToSrgbChannel(green),
        blue = linearToSrgbChannel(blue),
        alpha = alpha,
    )

    /**
     * Linear interpolation, per §41.1's rule that colour interpolates **in linear space by
     * default**.
     *
     * This is the operation §90.1 exists to protect. Interpolating in sRGB instead — the
     * naive `lerp` on stored values — produces the muddy midpoints that make cross-fades and
     * gradients look wrong; §41.1 rules it out.
     */
    public fun lerp(other: LinearColor, t: Float): LinearColor = LinearColor(
        red = red + (other.red - red) * t,
        green = green + (other.green - green) * t,
        blue = blue + (other.blue - blue) * t,
        alpha = alpha + (other.alpha - alpha) * t,
    )

    public companion object {
        public val BLACK: LinearColor = LinearColor(0f, 0f, 0f, 1f)
        public val WHITE: LinearColor = LinearColor(1f, 1f, 1f, 1f)
        public val TRANSPARENT: LinearColor = LinearColor(0f, 0f, 0f, 0f)
    }
}

/**
 * sRGB → linear for a single channel: the IEC 61966-2-1 electro-optical transfer function.
 *
 * The piecewise-linear segment below 0.04045 matters — the pure 2.2 or 2.4 power curve
 * often used as a shortcut diverges near black, which is exactly where banding is most
 * visible.
 */
public fun srgbToLinearChannel(channel: Float): Float =
    if (channel <= 0.04045f) {
        channel / 12.92f
    } else {
        ((channel + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

/** Linear → sRGB for a single channel. Inverse of [srgbToLinearChannel]. */
public fun linearToSrgbChannel(channel: Float): Float =
    if (channel <= 0.0031308f) {
        channel * 12.92f
    } else {
        (1.055f * channel.toDouble().pow(1.0 / 2.4).toFloat()) - 0.055f
    }
