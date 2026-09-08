# PERFORMANCE.md

Measured tolerances and budgets. §17.4 requires the FP16 precision figures to live here and to
become "the thresholds the CI tier (§77.1) enforces"; §121 requires performance baselines to be
recorded before/after every major feature.

Figures are produced by tests, not typed by hand. Each section names the test that produces it.

---

## 1. FP16 retained-spectrum precision (§17.4 — mandatory)

Produced by `audio:analysis` `Fp16PrecisionTest`, over §119's full fixture set at the ratified
§17.5 framing (2048-sample window, 480-sample hop, 48 kHz).

### 1.1 Relative error, normal range

Worst-case relative error for magnitudes binary16 stores as *normal* values. The arithmetic bound
for a 10-bit mantissa is 2⁻¹¹ ≈ **4.9e-4**; every fixture sits at or under it.

| §119 fixture | Worst relative error |
|---|---|
| silence | 0.000e+00 |
| bass-sweep | 4.614e-04 |
| white-noise | 4.859e-04 |
| impulse | 0.000e+00 |
| clipping | 4.860e-04 |
| very-quiet | 0.000e+00 *(no magnitude reaches the normal range — see §1.3)* |

**CI threshold: 4.9e-4.** A regression above it means the representation or the normalization
changed, not that the bound needs loosening.

### 1.2 Significant-bin survival

Share of bins **within 80 dB of their own frame's peak** that survive FP16 quantisation. Measured
against the frame peak rather than across all bins: a sparse spectrum is mostly window leakage far
below any audible floor, so counting every bin measures the window, not the format.

| §119 fixture | Peak magnitude | Significant bins | Survival |
|---|---|---|---|
| sine-440 (0.5) | 4.837e-01 | 2 428 | **100.00 %** |
| clipping | 1.143e+00 | 6 717 | **100.00 %** |
| white-noise | 4.798e-02 | 20 480 | **100.00 %** |
| drums-120bpm | 3.390e-01 | 20 480 | **100.00 %** |
| very-quiet | 9.673e-07 | 2 428 | **4.94 %** |

**CI threshold: > 99.9 % for ordinary-level content.** The very-quiet row is recorded, not gated —
see §1.3.

### 1.3 Finding — linear FP16 does not hold at low amplitude

§17.4 designates the very-quiet fixture as "the designated test for whether that precision holds at
low amplitude", and states the consequence of failure: "the documented fallback is a dB-domain
variant, which is a **format** change and therefore requires a `formatVersion` bump (§18.3) — not a
silent reinterpretation."

**The test shows it does not hold.** The fixture is a −120 dBFS tone; its normalized spectral peak
(9.673e-07) lands in binary16's **subnormal** range, where the format's precision changes from
10-bit *relative* to a fixed *absolute* step of 2⁻²⁴ ≈ 5.96e-08 — an absolute floor at roughly
−144 dBFS. Consequences measured:

- The peak survives, but only to the subnormal step: **≈3 % relative error**, versus 0.049 % for
  the same signal at ordinary level.
- **95 % of the fixture's significant bins are lost outright**, so the frame's internal dynamic
  range is destroyed even though its loudest bin survives.
- Ordinary-level content is entirely unaffected (§1.2), so this is a floor problem, not a
  precision problem: linear FP16 is scale-*dependent*, and a dB-domain representation would not be.

**This is a specification decision, not a defect, and it has not been taken.** Linear FP16 is
implemented exactly as §17.4 currently specifies. Switching to the dB-domain fallback would change
the meaning of every stored value and require a `formatVersion` bump; it is recorded for the
Project Owner as **T-13**.

---

## 2. Analysis cache size (§17.4, §18.3)

Derived, not measured — the arithmetic §18.3's budget is built on.

| Quantity | Value |
|---|---|
| Retained spectrum | 1024 bins × 2 bytes × 100 Hz = **200 KiB/s ≈ 11.7 MB/track-minute** |
| Stored scalars | ≈ **0.7 MB/track-minute** (≈34 channels × 4 bytes × 100 Hz) |
| Total | **≈ 12.4 MB/track-minute**; a five-minute track ≈ **62 MB** |
| §18.3 default budget | **1 GB** ≈ 80 track-minutes ≈ 16 five-minute tracks |
| Tier-2 waveform peaks (§82.1) | ≈ **0.9 MB/track-minute** (separate cache, separate budget) |

---

## 3. Pending measurements

| # | Measurement | Blocked on |
|---|---|---|
| 1 | Time-to-Stage-2 on both §6.1 reference devices (§17.7's recorded consequence, §121) | Nothing Phone (1) and Xiaomi 14 Pro — Step 12 |
| 2 | §17.1's "Trim screen usable within ~1 second" for a five-minute track | Same devices — Step 12 |
| 3 | `underrunCount == 0` under analysis load (§14.1, release-blocking) | Same devices — Step 12 |
