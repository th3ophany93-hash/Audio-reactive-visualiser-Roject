package com.arvs.audio.analysis

import com.arvs.audio.cache.AnalysisCacheEntry
import com.arvs.core.model.AnalysisCacheKey
import com.arvs.core.model.AnalysisConfig
import com.arvs.core.model.AnalysisStage
import com.arvs.core.model.AssetHash
import com.arvs.core.model.Fp16
import com.arvs.core.model.SpectrumCodec
import com.arvs.core.time.AnalysisFraming
import com.arvs.testing.audio.AudioFixture
import com.arvs.testing.audio.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * §17.4's **mandatory** precision testing for the retained spectrum, under §17.4.1's dB-domain
 * representation.
 *
 * §17.4: "deterministic golden and tolerance tests must establish and document the accepted
 * precision of the FP16 representation, across §119's full fixture set, including the very quiet
 * and clipping cases. The measured tolerances are recorded in `PERFORMANCE.md` and become the
 * thresholds the CI tier (§77.1) enforces."
 *
 * §17.4 named the consequence of failure — a dB-domain variant, as a format change — and Step 10
 * measured that failure. §17.4.1 [D-7] takes the fallback, so what is measured here is the
 * fallback's precision, at `formatVersion` 3.
 *
 * **Two different quantities, deliberately kept apart.** §17.4's 4.9e-4 tolerance governs the
 * quantity FP16 quantises, which §17.4.1 makes the **dBFS value**. The decoded **linear**
 * magnitude carries a larger error below −16 dBFS, necessarily and for any dB-domain
 * representation. Both are measured; neither is allowed to stand in for the other.
 */
class Fp16PrecisionTest {

    private val stage = SpectrumStage()

    /**
     * §17.4's tolerance: binary16's own relative step, 2⁻¹¹ ≈ 4.88e-4.
     *
     * Arithmetic, not a measurement — it is the bound the format cannot beat, and under §17.4.1
     * it applies to the stored dBFS value.
     */
    private val storedTolerance = 4.9e-4

    /**
     * §17.4.1's dB-domain guarantee: binary16's coarsest half-ULP anywhere in −160…0 dBFS is
     * 0.0625 dB (the 128…256 binade). Also arithmetic rather than a tuned figure.
     */
    private val dbTolerance = 0.0625

    private fun spectraOf(fixture: AudioFixture): List<FloatArray> {
        val canonical = CanonicalSignal.from(fixture.toPcmBuffer(), fixture.format)
        val frames = AnalysisFrames(canonical)
        val scratch = stage.newScratch()
        val buffer = frames.newFrameBuffer()
        return (0 until frames.frameCount).map { index ->
            frames.readFrame(index, buffer)
            stage.newSpectrumBuffer().also { stage.spectrumOf(buffer, scratch, it) }
        }
    }

    // --- §17.4's tolerance, on the quantity §17.4.1 quantises -----------------------------------

    @Test
    fun `the stored dB value meets section 17_4's tolerance across every fixture`() {
        // PERFORMANCE.md §1.1. Every §119 fixture, every frame, every bin.
        val report = StringBuilder("\n=== PERFORMANCE.md §1.1 — stored dBFS relative error ===\n")
        Fixtures.all(frames = 9_600).forEach { fixture ->
            var worst = 0.0
            spectraOf(fixture).forEach { spectrum ->
                spectrum.forEach { magnitude ->
                    worst = maxOf(worst, SpectrumCodec.storedRelativeError(magnitude))
                }
            }
            report.append("| %-14s | %.3e |\n".format(fixture.name, worst))
            assertTrue(
                "${fixture.name}: stored relative error $worst exceeds $storedTolerance",
                worst <= storedTolerance,
            )
        }
        println(report)
    }

    @Test
    fun `dB-domain precision is uniform across the whole level range`() {
        // PERFORMANCE.md §1.2. The property the fallback was taken *for*: the very-quiet fixture
        // is represented as precisely as the loudest one, because binary16's relative step now
        // applies to an exponent. Under linear FP16 this test could not have passed at all.
        val report = StringBuilder("\n=== PERFORMANCE.md §1.2 — worst dB error ===\n")
        Fixtures.all(frames = 9_600).forEach { fixture ->
            var worstDb = 0.0
            spectraOf(fixture).forEach { spectrum ->
                spectrum.forEach { magnitude ->
                    val db = SpectrumCodec.toDb(magnitude)
                    val round = SpectrumCodec.toDb(SpectrumCodec.decode(SpectrumCodec.encode(magnitude)))
                    worstDb = maxOf(worstDb, abs(round - db).toDouble())
                }
            }
            report.append("| %-14s | %.4f dB |\n".format(fixture.name, worstDb))
            assertTrue("${fixture.name}: worst dB error $worstDb", worstDb <= dbTolerance)
        }
        println(report)
    }

    // --- the very-quiet fixture: mandatory, and now meaningfully representable -------------------

    @Test
    fun `the very quiet fixture is meaningfully representable`() {
        // §17.4.1 keeps this fixture mandatory and requires exactly this. Under linear FP16 only
        // 4.94 % of its significant bins survived; the fixture is unchanged and the bar is the
        // same, so the comparison is like for like.
        val spectra = spectraOf(Fixtures.veryQuiet(9_600))
        val (significant, survived) = survivalOf(spectra)

        assertTrue("no significant bins to measure", significant > 0)
        val survival = survived.toDouble() / significant
        println("very-quiet: %d significant bins, survival %.2f %%".format(significant, survival * 100))
        assertTrue("very-quiet survival ${survival * 100} %", survival > 0.999)
    }

    @Test
    fun `significant bins survive across every fixture`() {
        // PERFORMANCE.md §1.3. "Significant" = within 80 dB of the frame's own peak: a sparse
        // spectrum is mostly window leakage far below any audible floor, so counting every bin
        // measures the window rather than the format.
        val report = StringBuilder("\n=== PERFORMANCE.md §1.3 — significant-bin survival ===\n")
        Fixtures.all(frames = 9_600).forEach { fixture ->
            val (significant, survived) = survivalOf(spectraOf(fixture))
            if (significant == 0L) return@forEach
            val survival = survived.toDouble() / significant
            report.append("| %-14s | %7d | %.2f %% |\n".format(fixture.name, significant, survival * 100))
            assertTrue(
                "${fixture.name}: survival ${survival * 100} %",
                survival > 0.999,
            )
        }
        println(report)
    }

    /** Bins within 80 dB of their frame's peak, and how many survive a codec round trip. */
    private fun survivalOf(spectra: List<FloatArray>): Pair<Long, Long> {
        var significant = 0L
        var survived = 0L
        spectra.forEach { spectrum ->
            val peak = spectrum.maxOrNull() ?: 0.0f
            if (peak <= 0.0f) return@forEach
            val floor = peak / 10_000.0f                      // −80 dB relative to the frame peak
            spectrum.forEach { magnitude ->
                if (magnitude < floor || magnitude <= SpectrumCodec.FLOOR_LINEAR) return@forEach
                significant++
                if (SpectrumCodec.decode(SpectrumCodec.encode(magnitude)) > SpectrumCodec.FLOOR_LINEAR) {
                    survived++
                }
            }
        }
        return significant to survived
    }

    // --- the decoded-linear consequence, measured rather than asserted away ----------------------

    @Test
    fun `decoded linear error is recorded, and matches the dB propagation identity`() {
        // PERFORMANCE.md §1.4. §17.4.1 states plainly that 4.9e-4 does not carry over to the
        // decoded linear magnitude below −16 dBFS. That is a property of any dB representation,
        // so it is measured and recorded rather than hidden behind a loosened bound.
        //
        // The bound asserted is the exact propagation identity rather than a figure derived from
        // 0.0625 dB: a dB error of Δ becomes a linear relative error of 10^(Δ/20) − 1, checked
        // per bin against that bin's own quantisation error. A bound computed from the FP16
        // half-ULP alone is very slightly too tight, because `toDb` narrows its Double result to
        // Float before quantisation, so the total dB error can exceed a pure half-ULP by that
        // Float rounding (~1e-5 near the floor). The identity has no such gap.
        //
        // FLOAT_SLACK covers only the final narrowing of the decoded value: Float's own relative
        // step, 2⁻²⁴ ≈ 6e-8.
        // The dB error is taken against the *exact* Double logarithm, not against `toDb`'s
        // already-narrowed Float: the narrowing is one of the roundings the decoded value
        // carries, so measuring from the Float would leave it out of the identity and the bound
        // would be a hair too tight. FLOAT_SLACK then covers only the final narrowing of the
        // decoded magnitude — Float's relative step, 2⁻²⁴ ≈ 6e-8.
        val floatSlack = 1e-6
        val report = StringBuilder("\n=== PERFORMANCE.md §1.4 — decoded linear relative error ===\n")
        var worstResidual = 0.0
        Fixtures.all(frames = 9_600).forEach { fixture ->
            var worstLinear = 0.0
            var worstDb = 0.0
            spectraOf(fixture).forEach { spectrum ->
                spectrum.forEach { magnitude ->
                    if (magnitude <= SpectrumCodec.FLOOR_LINEAR) return@forEach
                    val exactDb = 20.0 * kotlin.math.log10(magnitude.toDouble())
                    val storedDb = Fp16.toFloat(SpectrumCodec.encode(magnitude)).toDouble()
                    val dbError = abs(storedDb - exactDb)
                    val linearError = SpectrumCodec.linearRelativeError(magnitude)

                    val residual = linearError - (Math.pow(10.0, dbError / 20.0) - 1.0)
                    assertTrue(
                        "${fixture.name}: linear $linearError exceeds identity for dB $dbError",
                        residual <= floatSlack,
                    )
                    worstResidual = maxOf(worstResidual, residual)
                    worstLinear = maxOf(worstLinear, linearError)
                    worstDb = maxOf(worstDb, dbError)
                }
            }
            report.append(
                "| %-14s | %.3e | from %.5f dB |\n".format(fixture.name, worstLinear, worstDb),
            )
        }
        report.append("worst residual against the identity: %.3e\n".format(worstResidual))
        println(report)
    }

    // --- format invariants -----------------------------------------------------------------------

    @Test
    fun `the clipping fixture does not overflow the representation`() {
        // Magnitudes above 1.0 are positive dBFS. They must survive rather than saturate.
        val spectra = spectraOf(Fixtures.clipping(9_600))
        val peak = spectra.maxOf { it.max() }
        assertTrue("clipping peak $peak should exceed full scale", peak > 1.0f)
        val round = SpectrumCodec.decode(SpectrumCodec.encode(peak))
        assertEquals(peak.toDouble(), round.toDouble(), peak * 1e-2)
        assertTrue(!round.isInfinite())
    }

    @Test
    fun `quantisation preserves ordering within a frame`() {
        // A monotone codec is what lets a reader compare two stored bins without decoding both.
        // log10 is monotone and FP16 rounding is monotone, so the composition must be too.
        Fixtures.all(frames = 4_800).forEach { fixture ->
            spectraOf(fixture).take(20).forEach { spectrum ->
                for (index in 0 until spectrum.size - 1) {
                    val a = spectrum[index]
                    val b = spectrum[index + 1]
                    if (a <= SpectrumCodec.FLOOR_LINEAR || b <= SpectrumCodec.FLOOR_LINEAR) continue
                    val qa = SpectrumCodec.decode(SpectrumCodec.encode(a))
                    val qb = SpectrumCodec.decode(SpectrumCodec.encode(b))
                    if (a < b) assertTrue("${fixture.name}: $a<$b but $qa>$qb", qa <= qb)
                    if (a > b) assertTrue("${fixture.name}: $a>$b but $qa<$qb", qa >= qb)
                }
            }
        }
    }

    @Test
    fun `a spectrum round-trips through the cache within the documented tolerance`() {
        val fixture = Fixtures.drums(9_600)
        val spectra = spectraOf(fixture)
        val entry = entryFor(spectra)

        val out = FloatArray(stage.retainedBinCount)
        var worstDb = 0.0
        spectra.indices.forEach { index ->
            assertTrue("frame $index unreadable", entry.spectrumFrame(index.toLong(), out))
            for (bin in out.indices) {
                val original = SpectrumCodec.toDb(spectra[index][bin])
                worstDb = maxOf(worstDb, abs(SpectrumCodec.toDb(out[bin]) - original).toDouble())
            }
        }
        assertTrue("cache round trip worst dB error $worstDb", worstDb <= dbTolerance)
    }

    @Test
    fun `the cache stores dB values, not linear magnitudes`() {
        // The concrete regression the formatVersion bump exists to prevent. A reader that decodes
        // the payload with a plain Fp16.toFloat gets numbers near −160, not magnitudes near 0…1.
        // If this ever passes with the naive decode, the payload has silently reverted to linear.
        val spectra = spectraOf(Fixtures.sine440(9_600, amplitude = 0.5f))

        // Asserted against the payload the encoder produces, rather than by reaching into the
        // cache's internals: widening a production API so a test can look inside it is how the
        // internal becomes load-bearing.
        val raw = halvesFor(spectra)
        val naive = raw.map { Fp16.toFloat(it) }
        assertTrue("no stored value is a dBFS number", naive.any { it < -1.0f })
        assertTrue("a linear payload would have no negatives", naive.count { it < 0.0f } > raw.size / 2)

        // And the decode side undoes it, so a reader still sees magnitudes.
        val out = FloatArray(stage.retainedBinCount)
        assertTrue(entryFor(spectra).spectrumFrame(0L, out))
        assertTrue("decoded magnitudes must be non-negative", out.all { it >= 0.0f })
    }

    /** §17.4.1's encoded payload for a whole track, frame-major, exactly as the cache stores it. */
    private fun halvesFor(spectra: List<FloatArray>): ShortArray {
        val bins = stage.retainedBinCount
        return ShortArray(spectra.size * bins).also { halves ->
            spectra.forEachIndexed { index, spectrum ->
                SpectrumCodec.encodeFrame(spectrum, halves, index * bins)
            }
        }
    }

    private fun entryFor(spectra: List<FloatArray>): AnalysisCacheEntry {
        val bins = stage.retainedBinCount
        val halves = halvesFor(spectra)
        return AnalysisCacheEntry(
            key = AnalysisCacheKey(AssetHash("a".repeat(64)), AnalysisConfig().hash()),
            framing = AnalysisFraming.CANONICAL,
            frameCount = spectra.size.toLong(),
            trackPeakEnergy = 1.0f,
            sourceSampleRateHz = 48_000,
            sourceChannelCount = 1,
        ).also {
            it.stageSpectrum(AnalysisStage.SPECTRUM, bins, halves)
            it.advanceTo(AnalysisStage.SPECTRUM, spectra.size - 1L)
        }
    }
}
