package com.arvs.core.model

import kotlin.math.log10
import kotlin.math.pow

/**
 * §17.4.1's dB-domain codec for the retained spectrum — the storage representation, in one place.
 *
 * §17.4's mandatory precision test showed that linear FP16 does not hold at low amplitude: a
 * −120 dBFS spectrum lands in binary16's subnormal range, where the format's precision collapses
 * from a 10-bit *relative* mantissa to a fixed *absolute* step of 2⁻²⁴, an absolute floor near
 * −144 dBFS. §17.4 anticipated this and named the fallback; **§17.4.1 [D-7] takes it**.
 *
 * ```
 * encode:  dBFS = 20 · log₁₀(linearMagnitude),  floored at DB_FLOOR
 * store:   FP16 quantises the dBFS value — never the linear magnitude
 * decode:  linearMagnitude = 10^(storedDb / 20),  DB_FLOOR respected as the lower bound
 * ```
 *
 * ### Why this is the right trade, and what it costs
 *
 * Linear FP16 held 4.9e-4 relative precision everywhere it could represent a value at all, then
 * underflowed to nothing. dB-domain FP16 represents the whole −160…0 dBFS range with **bounded,
 * scale-independent** precision — never worse than 0.0625 dB (0.72 %) anywhere — because binary16's
 * relative step now applies to an exponent rather than to a mantissa.
 *
 * The cost is stated plainly in §17.4.1: §17.4's 4.9e-4 tolerance governs the quantity FP16
 * quantises, which is now the dBFS value, and holds there by construction. It does **not** carry
 * over to the decoded *linear* magnitude and cannot, for any dB-domain representation: a dB error
 * `Δd` becomes a linear relative error of `10^(Δd/20) − 1 ≈ 0.11513·Δd`, so the decoded-linear
 * bound is 4.9e-4 only above −16 dBFS. Both figures are measured per §119 fixture and recorded in
 * `PERFORMANCE.md`.
 *
 * ### Placement
 *
 * `core:model`, beside [Fp16], because both `audio:analysis` (which encodes) and `audio:cache`
 * (which decodes) must agree byte-for-byte and neither may depend on the other — the same §116.1
 * reasoning that placed `AnalysisStage` here.
 */
public object SpectrumCodec {

    /** §17.4.1's normative floor, in dBFS. Zero and below-floor magnitudes are stored as this. */
    public const val DB_FLOOR: Float = -160.0f

    /**
     * The linear magnitude [DB_FLOOR] denotes: `10^(−160/20)` = 1e-8.
     *
     * Decoding [DB_FLOOR] yields this, not zero — §17.4.1 defines decode as `10^(storedDb/20)`
     * with the floor as a *lower bound* on the dB value, so the floor is a magnitude like any
     * other rather than a special "silence" token.
     */
    public const val FLOOR_LINEAR: Float = 1e-8f

    /**
     * Linear magnitude → dBFS, floored.
     *
     * Total by construction: the guard is written as `!(linear > FLOOR_LINEAR)` rather than
     * `linear <= FLOOR_LINEAR` so that **NaN also takes the floor**. A NaN reaching storage would
     * otherwise survive quantisation and propagate into every downstream reactive mapping, and
     * §9.1's bit-reproducibility leaves no room for a value whose comparisons are all false.
     * Negative inputs take the same path; a magnitude is `|X|` and cannot legitimately be negative.
     */
    public fun toDb(linearMagnitude: Float): Float {
        if (!(linearMagnitude > FLOOR_LINEAR)) return DB_FLOOR
        return (20.0 * log10(linearMagnitude.toDouble())).toFloat()
    }

    /** dBFS → linear magnitude, with [DB_FLOOR] as the lower bound. NaN takes the floor, as above. */
    public fun toLinear(db: Float): Float {
        if (!(db > DB_FLOOR)) return FLOOR_LINEAR
        return 10.0.pow(db.toDouble() / 20.0).toFloat()
    }

    /** Linear magnitude → the stored binary16 bit pattern. §17.4.1's full encode path. */
    public fun encode(linearMagnitude: Float): Short = Fp16.fromFloat(toDb(linearMagnitude))

    /** Stored binary16 bit pattern → linear magnitude. §17.4.1's full decode path. */
    public fun decode(half: Short): Float = toLinear(Fp16.toFloat(half))

    /** Encodes one frame's spectrum into [into] at [offset]. Frame-major, as the format stores it. */
    public fun encodeFrame(spectrum: FloatArray, into: ShortArray, offset: Int) {
        require(offset >= 0 && offset + spectrum.size <= into.size) {
            "frame at $offset..${offset + spectrum.size} does not fit ${into.size} values"
        }
        for (index in spectrum.indices) into[offset + index] = encode(spectrum[index])
    }

    /** Decodes one frame's spectrum from [halves] at [offset]. */
    public fun decodeFrame(halves: ShortArray, offset: Int, into: FloatArray) {
        require(offset >= 0 && offset + into.size <= halves.size) {
            "frame at $offset..${offset + into.size} does not fit ${halves.size} values"
        }
        for (index in into.indices) into[index] = decode(halves[offset + index])
    }

    /**
     * The relative error [encode]/[decode] introduces in the **linear** magnitude.
     *
     * Exposed so `PERFORMANCE.md`'s figures are produced by the codec itself rather than
     * re-derived by a test that could drift from it.
     */
    public fun linearRelativeError(linearMagnitude: Float): Double {
        if (!(linearMagnitude > FLOOR_LINEAR)) return 0.0
        val round = decode(encode(linearMagnitude)).toDouble()
        return kotlin.math.abs(round - linearMagnitude) / linearMagnitude
    }

    /** The relative error FP16 introduces in the **stored dBFS value** — §17.4's 4.9e-4 quantity. */
    public fun storedRelativeError(linearMagnitude: Float): Double {
        val db = toDb(linearMagnitude).toDouble()
        if (db == 0.0) return 0.0
        return kotlin.math.abs(Fp16.quantise(db.toFloat()).toDouble() - db) / kotlin.math.abs(db)
    }
}
