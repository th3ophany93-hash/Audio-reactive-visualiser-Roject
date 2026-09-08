package com.arvs.core.model

/**
 * IEEE 754 binary16 ("half precision") conversion — §17.4's retained-spectrum storage format.
 *
 * Hand-written rather than delegated to `Float.floatToFloat16` (JDK 20+) or `android.util.Half`.
 * Two reasons, and the second is the binding one:
 *
 *  - This project targets JVM 17 and `core:model` is pure Kotlin, so neither API is available on
 *    every path that needs it.
 *  - §18.3 makes the cache layout a versioned persistent format. A platform routine's rounding
 *    behaviour is not something this project controls, and §9.1 requires the cache to be
 *    bit-reproducible. Ten lines of arithmetic this project owns removes the question.
 *
 * Rounding is **round-half-to-even**, matching IEEE 754's default, so a value that sits exactly
 * between two representable halves resolves the same way everywhere.
 */
public object Fp16 {

    /** Largest finite binary16 value. */
    public const val MAX_VALUE: Float = 65_504.0f

    /** Smallest positive *normal* binary16 value, 2⁻¹⁴. */
    public const val MIN_NORMAL: Float = 6.103515625e-5f

    /** Smallest positive *subnormal* binary16 value, 2⁻²⁴. Below this, values flush to zero. */
    public const val MIN_SUBNORMAL: Float = 5.960464477539063e-8f

    /** Converts a float to its binary16 bit pattern, held in the low 16 bits of a [Short]. */
    public fun fromFloat(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        var mantissa = bits and 0x007F_FFFF

        // NaN and infinity keep their class; a NaN must not silently become infinity.
        if (((bits ushr 23) and 0xFF) == 0xFF) {
            return (sign or 0x7C00 or (if (mantissa != 0) 0x200 else 0)).toShort()
        }

        if (exponent >= 0x1F) {
            // Overflow to infinity, as IEEE 754 requires — never a wrap to a small value.
            return (sign or 0x7C00).toShort()
        }

        if (exponent <= 0) {
            if (exponent < -10) return sign.toShort()   // underflows past subnormal range
            // Subnormal: reintroduce the implicit leading 1 and shift into place.
            mantissa = mantissa or 0x0080_0000
            val shift = 14 - exponent
            val halfMantissa = mantissa ushr shift
            val roundBit = (mantissa ushr (shift - 1)) and 1
            val sticky = if ((mantissa and ((1 shl (shift - 1)) - 1)) != 0) 1 else 0
            val rounded = halfMantissa + roundToEven(halfMantissa, roundBit, sticky)
            return (sign or rounded).toShort()
        }

        val halfMantissa = mantissa ushr 13
        val roundBit = (mantissa ushr 12) and 1
        val sticky = if ((mantissa and 0x0FFF) != 0) 1 else 0
        var rounded = halfMantissa + roundToEven(halfMantissa, roundBit, sticky)
        if (rounded > 0x3FF) {
            // Rounding carried into the exponent.
            rounded = 0
            exponent += 1
            if (exponent >= 0x1F) return (sign or 0x7C00).toShort()
        }
        return (sign or (exponent shl 10) or rounded).toShort()
    }

    /** Converts a binary16 bit pattern back to a float. Exact — every half is a float. */
    public fun toFloat(half: Short): Float {
        val bits = half.toInt() and 0xFFFF
        val sign = (bits and 0x8000) shl 16
        val exponent = (bits ushr 10) and 0x1F
        val mantissa = bits and 0x03FF

        return when {
            exponent == 0x1F ->
                Float.fromBits(sign or 0x7F80_0000 or (mantissa shl 13))
            exponent == 0 && mantissa == 0 ->
                Float.fromBits(sign)
            exponent == 0 -> {
                // Subnormal half: normalise it into a float, which has the range to spare.
                var significand = mantissa
                var scale = -1
                while (significand and 0x0400 == 0) {
                    significand = significand shl 1
                    scale++
                }
                significand = significand and 0x03FF
                Float.fromBits(sign or ((127 - 15 - scale) shl 23) or (significand shl 13))
            }
            else ->
                Float.fromBits(sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13))
        }
    }

    /** Round-trips a float through binary16 — what a value becomes once stored. */
    public fun quantise(value: Float): Float = toFloat(fromFloat(value))

    /**
     * Relative error a value suffers when stored as binary16, or 0 for an exact zero.
     *
     * Used by §17.4's mandatory precision tests, which must "establish and document the accepted
     * precision of the FP16 representation, across §119's full fixture set".
     */
    public fun relativeError(value: Float): Double {
        if (value == 0.0f) return 0.0
        val stored = quantise(value)
        return kotlin.math.abs((stored - value).toDouble() / value.toDouble())
    }

    private fun roundToEven(mantissa: Int, roundBit: Int, sticky: Int): Int =
        if (roundBit == 1 && (sticky == 1 || (mantissa and 1) == 1)) 1 else 0
}
