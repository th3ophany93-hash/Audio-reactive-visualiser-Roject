package com.arvs.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Pins §17.4.1's dB-domain retained-spectrum codec against its normative definition. */
class SpectrumCodecTest {

    @Test
    fun `the normative floor is exactly what the decision states`() {
        assertEquals(-160.0f, SpectrumCodec.DB_FLOOR)
        // 10^(-160/20). Stated independently of the constant so a changed floor fails here.
        // Compared at Float resolution: 1e-8f is 9.9999999e-9, and the constant is a Float.
        val floor = 10.0.pow(-160.0 / 20.0)
        assertEquals(floor, SpectrumCodec.FLOOR_LINEAR.toDouble(), floor * 1e-6)
    }

    @Test
    fun `the floor survives binary16 bit-exactly`() {
        // -160 = -1.25 x 2^7, an integer multiple of that binade's ULP of 0.125, so it is
        // exactly representable. If it were not, the floor would drift on every round trip and
        // "below-floor values are represented as DB_FLOOR" would not be a fixed point.
        assertEquals(SpectrumCodec.DB_FLOOR, Fp16.quantise(SpectrumCodec.DB_FLOOR))
    }

    @Test
    fun `encoding is twenty log ten of the magnitude`() {
        listOf(1.0f, 0.5f, 0.1f, 0.001f, 1e-6f, 1.143f).forEach { magnitude ->
            val expected = 20.0 * log10(magnitude.toDouble())
            assertEquals("$magnitude", expected, SpectrumCodec.toDb(magnitude).toDouble(), 1e-5)
        }
        // A full-scale bin reads 0 dBFS, which is what makes the reference meaningful.
        assertEquals(0.0f, SpectrumCodec.toDb(1.0f))
    }

    @Test
    fun `zero and below-floor magnitudes are represented as the floor`() {
        assertEquals(SpectrumCodec.DB_FLOOR, SpectrumCodec.toDb(0.0f))
        assertEquals(SpectrumCodec.DB_FLOOR, SpectrumCodec.toDb(SpectrumCodec.FLOOR_LINEAR))
        assertEquals(SpectrumCodec.DB_FLOOR, SpectrumCodec.toDb(1e-12f))
        assertEquals(SpectrumCodec.DB_FLOOR, SpectrumCodec.toDb(Float.MIN_VALUE))
    }

    @Test
    fun `NaN and negative magnitudes take the floor rather than propagating`() {
        // A magnitude is |X| and cannot legitimately be negative, and a NaN reaching storage
        // would survive quantisation and poison every downstream mapping. Both are total.
        assertEquals(SpectrumCodec.DB_FLOOR, SpectrumCodec.toDb(Float.NaN))
        assertEquals(SpectrumCodec.DB_FLOOR, SpectrumCodec.toDb(-1.0f))
        assertEquals(SpectrumCodec.FLOOR_LINEAR, SpectrumCodec.toLinear(Float.NaN))
        assertTrue(!SpectrumCodec.decode(SpectrumCodec.encode(Float.NaN)).isNaN())
    }

    @Test
    fun `decoding is ten to the dB over twenty, with the floor as the lower bound`() {
        listOf(0.0f, -6.0f, -60.0f, -120.0f, -159.0f).forEach { db ->
            val expected = 10.0.pow(db.toDouble() / 20.0)
            assertEquals("$db", expected, SpectrumCodec.toLinear(db).toDouble(), expected * 1e-6)
        }
        // Below the floor is clamped to the floor, not extrapolated.
        assertEquals(SpectrumCodec.FLOOR_LINEAR, SpectrumCodec.toLinear(-200.0f))
        assertEquals(SpectrumCodec.FLOOR_LINEAR, SpectrumCodec.toLinear(SpectrumCodec.DB_FLOOR))
    }

    @Test
    fun `quantisation is applied to the dB value, not to the linear magnitude`() {
        // The distinguishing property of §17.4.1, measured against what linear storage does to
        // the same magnitudes. Below binary16's subnormal floor the linear representation first
        // loses most of its precision and then loses the value entirely; the dB representation
        // carries both to within a fraction of a percent.
        //
        //   1e-7  linear -> 1.192093e-07, 19.21 % error   |  dB -> within 1 %
        //   3e-8  linear -> 5.960464e-08, 98.68 % error   |  dB -> within 1 %
        //   1e-8  linear -> 0,           100 % error      |  (at the §17.4.1 floor)
        listOf(1e-7f, 3e-8f).forEach { magnitude ->
            val linearError = abs(Fp16.quantise(magnitude) - magnitude) / magnitude
            assertTrue("linear kept $magnitude too well: $linearError", linearError > 0.19)

            val round = SpectrumCodec.decode(SpectrumCodec.encode(magnitude))
            assertTrue("lost to zero: $round", round > 0.0f)
            assertEquals("$magnitude", magnitude.toDouble(), round.toDouble(), magnitude * 1e-2)
        }
        // And a magnitude linear FP16 loses outright still round-trips in the dB domain.
        assertEquals(0.0f, Fp16.quantise(1e-8f))
        assertTrue(SpectrumCodec.decode(SpectrumCodec.encode(2e-8f)) > 0.0f)
    }

    @Test
    fun `the stored dB value always meets the 4_9e-4 FP16 relative bound`() {
        // §17.4's tolerance, governing the quantity §17.4.1 makes FP16 quantise. It holds by
        // construction across the whole representable range: it is binary16's own relative step.
        var worst = 0.0
        var magnitude = 2.0f
        while (magnitude > SpectrumCodec.FLOOR_LINEAR) {
            worst = maxOf(worst, SpectrumCodec.storedRelativeError(magnitude))
            magnitude *= 0.97f
        }
        assertTrue("worst stored relative error $worst", worst <= 4.9e-4)
    }

    @Test
    fun `decoded linear precision is bounded and scale-independent across the whole range`() {
        // The property the fallback was taken *for*: never worse than 0.0625 dB anywhere in
        // -160..0, so a -120 dBFS bin is represented as precisely as a -6 dBFS one. Asserted in
        // the dB domain because that is where the guarantee is uniform.
        var magnitude = 1.0f
        var worstDbError = 0.0
        while (magnitude > SpectrumCodec.FLOOR_LINEAR * 2) {
            val db = SpectrumCodec.toDb(magnitude)
            val roundTripped = SpectrumCodec.toDb(SpectrumCodec.decode(SpectrumCodec.encode(magnitude)))
            worstDbError = maxOf(worstDbError, abs(roundTripped - db).toDouble())
            magnitude *= 0.9f
        }
        assertTrue("worst dB error $worstDbError", worstDbError <= 0.0625)
    }

    @Test
    fun `encode and decode are deterministic and idempotent under re-encoding`() {
        // §9.1: identical input must give bit-identical output, and a decoded value must
        // re-encode to the same bits — otherwise repeated cache round trips would drift.
        listOf(1.0f, 0.5f, 1e-3f, 1e-6f, 1e-7f, 0.0f, 1.143f).forEach { magnitude ->
            val first = SpectrumCodec.encode(magnitude)
            assertEquals("$magnitude", first, SpectrumCodec.encode(magnitude))
            val decoded = SpectrumCodec.decode(first)
            assertEquals("re-encode $magnitude", first, SpectrumCodec.encode(decoded))
        }
    }

    @Test
    fun `frame encoding round-trips at an offset`() {
        // Magnitudes stay above §17.4.1's floor (1e-8); below it every value legitimately
        // encodes to DB_FLOOR, which is the floor working, not a round-trip failure.
        val frame = FloatArray(8) { 10.0f.pow(-it.toFloat()) }
        val store = ShortArray(24)
        SpectrumCodec.encodeFrame(frame, store, 8)
        val out = FloatArray(8)
        SpectrumCodec.decodeFrame(store, 8, out)
        for (index in frame.indices) {
            assertEquals("bin $index", frame[index].toDouble(), out[index].toDouble(), frame[index] * 1e-2)
        }
        // The neighbouring frames are untouched — an off-by-one in the offset would corrupt them.
        assertEquals(0.toShort(), store[7])
        assertEquals(0.toShort(), store[16])
    }

    @Test
    fun `a frame that does not fit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpectrumCodec.encodeFrame(FloatArray(4), ShortArray(6), 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpectrumCodec.decodeFrame(ShortArray(6), -1, FloatArray(4))
        }
    }

    @Test
    fun `distinct quiet magnitudes stay distinct, which linear storage could not manage`() {
        // The concrete failure §17.4's very-quiet fixture exposed: under linear FP16 these all
        // collapse together at the subnormal floor, destroying a frame's internal dynamic range.
        val quiet = listOf(1e-6f, 5e-7f, 1e-7f, 5e-8f)
        val encoded = quiet.map { SpectrumCodec.encode(it) }
        assertEquals(quiet.size, encoded.toSet().size)
        for (index in 0 until quiet.size - 1) {
            assertNotEquals(encoded[index], encoded[index + 1])
        }
    }
}
