# PERFORMANCE.md

Measured tolerances and budgets. §17.4 requires the FP16 precision figures to live here and to
become "the thresholds the CI tier (§77.1) enforces"; §121 requires performance baselines to be
recorded before/after every major feature.

Figures are produced by tests, not typed by hand. Each section names the test that produces it.

---

## 1. FP16 retained-spectrum precision (§17.4, §17.4.1 — mandatory)

Produced by `audio:analysis` `Fp16PrecisionTest`, over §119's full fixture set at the ratified
§17.5 framing (2048-sample window, 480-sample hop, 48 kHz), under §17.4.1 [D-7]'s **dB-domain**
representation at `formatVersion` 3.

§17.4's tolerance of **4.9e-4** governs the quantity FP16 quantises, which §17.4.1 makes the
**dBFS value**. §1.1 is that quantity. §1.4 records what the decoded *linear* magnitude costs;
the two are not interchangeable and neither stands in for the other.

### 1.1 Relative error of the stored dBFS value — §17.4's normative bound

| §119 fixture | Worst relative error |
|---|---|
| silence | 0.000e+00 |
| sine-440 | 4.771e-04 |
| sine-1000 | 4.691e-04 |
| sine-10000 | 4.810e-04 |
| bass-sweep | 4.860e-04 |
| white-noise | 4.871e-04 |
| impulse | 0.000e+00 |
| drums-120bpm | 4.839e-04 |
| clipping | 4.768e-04 |
| very-quiet | 4.662e-04 |
| stereo-440-660 | 4.750e-04 |

**CI threshold: 4.9e-4.** Every fixture is under it, including very-quiet — which under the
previous linear representation could not be measured against this bound at all, because its
magnitudes never reached binary16's normal range.

### 1.2 Absolute dB error — the scale-independence the fallback was taken for

| §119 fixture | Worst dB error |
|---|---|
| silence | 0.0000 dB |
| sine-440 | 0.0624 dB |
| sine-1000 | 0.0624 dB |
| sine-10000 | 0.0623 dB |
| bass-sweep | 0.0625 dB |
| white-noise | 0.0312 dB |
| impulse | 0.0000 dB |
| drums-120bpm | 0.0453 dB |
| clipping | 0.0617 dB |
| very-quiet | 0.0617 dB |
| stereo-440-660 | 0.0625 dB |

**CI threshold: 0.0625 dB** — binary16's coarsest half-ULP anywhere in −160…0 dBFS (the 128…256
binade). Arithmetic, not a tuned figure. The −120 dBFS fixture is held to the same 0.0617 dB as
the loudest content, which is the point: precision no longer depends on level.

### 1.3 Significant-bin survival

Share of bins **within 80 dB of their own frame's peak, and above §17.4.1's −160 dBFS floor**
that survive quantisation.

| §119 fixture | Significant bins | Survival |
|---|---|---|
| sine-440 | 2 428 | **100.00 %** |
| sine-1000 | 3 144 | **100.00 %** |
| sine-10000 | 3 806 | **100.00 %** |
| bass-sweep | 1 578 | **100.00 %** |
| white-noise | 20 480 | **100.00 %** |
| drums-120bpm | 20 480 | **100.00 %** |
| clipping | 6 717 | **100.00 %** |
| very-quiet | 203 | **100.00 %** |
| stereo-440-660 | 3 091 | **100.00 %** |

**CI threshold: > 99.9 %, applied to every fixture including very-quiet.**

Under the previous linear representation very-quiet survived **4.94 %** of 2 428 significant bins.
It now survives **100.00 %**. Its significant-bin *population* is smaller (203) because the count
excludes bins below §17.4.1's −160 dBFS floor: at a peak of 9.673e-07, the 80 dB band reaches down
to 9.673e-11, which is outside the representation by design. The populations are therefore not
like-for-like — what changed is that every bin the format claims to carry now survives, where
before 95 % of them did not.

### 1.4 Decoded linear relative error — the recorded cost

| §119 fixture | Worst relative error | from dB error |
|---|---|---|
| silence | 0.000e+00 | 0.00000 dB |
| sine-440 | 7.206e-03 | 0.06237 dB |
| sine-1000 | 7.208e-03 | 0.06238 dB |
| sine-10000 | 7.157e-03 | 0.06235 dB |
| bass-sweep | 7.205e-03 | 0.06250 dB |
| white-noise | 3.590e-03 | 0.03124 dB |
| impulse | 0.000e+00 | 0.00000 dB |
| drums-120bpm | 5.198e-03 | 0.04527 dB |
| clipping | 7.129e-03 | 0.06170 dB |
| very-quiet | 7.080e-03 | 0.06171 dB |
| stereo-440-660 | 7.222e-03 | 0.06251 dB |

Gated against the exact propagation identity `linearRelativeError = 10^(ΔdB/20) − 1`, checked per
bin rather than against a recorded constant. Worst residual against that identity across the whole
fixture set: **5.937e-08** — Float's own relative step, 2⁻²⁴ ≈ 5.96e-08, which is the single
rounding the identity does not capture.

This is the trade §17.4.1 states. Linear FP16 held 4.9e-4 in this column everywhere it could
represent a value, then underflowed to nothing below ≈−144 dBFS. dB-domain FP16 gives up that
tighter figure below −16 dBFS and gains a bounded, scale-independent representation across the
entire −160…0 range.

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
