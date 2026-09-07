# FINAL PHASE 1 GATE

**Purpose:** Record the ratification of P-1 … P-6 **and U-21**, and the final Phase 1 verdict.
**Subject:** `MASTER_SPECIFICATION_v3.0.md` as amended in this pass, verified by direct inspection — not by assuming the edits landed.
**Scope discipline:** documentation only. No application code, no Gradle, no Android project, no scaffolding was created.

---

## A. Decision Status

| ID | Status | Ratified as | Substance |
|---|---|---|---|
| **P-1 — Channel handling** | ✅ **RESOLVED** | §17.3 | Canonical **mono** analysis signal. Stereo: `mono[n] = 0.5·left[n] + 0.5·right[n]`. Mono passes through. Source channel count/layout preserved in Asset Registry (§82) and decoder metadata (§15). **Playback (§14.1/§16) and future export (§136) use the original source, never the mono signal.** §119's stereo fixture mandatory, verifying deterministic downmix. |
| **P-2 — Canonical sample rate** | ✅ **RESOLVED** | §17.3 | **48,000 Hz**. Deterministic software windowed-sinc polyphase resampler; sources already at 48 kHz pass through bit-exact; platform/hardware resampling explicitly forbidden (it would make golden vectors device-dependent and break §9.1). Window duration fixed at `2048/48000 s ≈ 42.667 ms`; bin width `23.4375 Hz`. Part of analysis configuration identity. |
| **P-3 — `analysisConfigHash` membership** | ✅ **RESOLVED** | §18.2 | Normative **inclusion** list (11 items: canonical rate, channel policy, FFT size, window function, hop/overlap, frame rate, default band definitions, normalization, beat config, quality level, algorithm/schema version) **and** normative **exclusion** list. Governing test: *if changing it changes a stored number it is in the hash; if it only changes consumption of an already-stored number it is not.* |
| **P-4 — FFT retention** | ✅ **RESOLVED** | §17.4 | 1024 magnitude bins, 100 Hz, FP16. Concrete bin mapping specified (bins 1…1024 of the 2048-point FFT; DC discarded). Scalars computed at full float32 during analysis and stored. Custom bands and log spectrum derived at read time — **never** re-running the FFT or re-decoding. Precision tolerance tests mandatory. Measured cost ≈**12.4 MB per track-minute** (≈62 MB per five-minute track). |
| **P-5 — §14.1 wording** | ✅ **RESOLVED** | §14.1 | "Translated exactly once … and nowhere else" governs the **render/parameter-resolution pipeline**. Trim Editor display-space conversion is expected and permitted. An editor converting to draw a waveform is not a violation; a renderer or plugin applying `trimIn` inside the parameter pipeline is. |
| **P-6 — Cache format & disk policy** | ✅ **RESOLVED** | §18.3 | `formatVersion` **independent** of `analysisConfigHash` (what-was-computed vs how-it-is-laid-out); unknown/newer version → reject, delete, regenerate, never partial-parse. Cache disposable; project state never depends on cache presence and eviction can never invalidate it. Budget **1 GB default**, configurable 256 MB – 8 GB, derived from §17.4's measured per-minute cost. Deterministic **LRU by last access, whole entries only**; open project's entry protected; OS low-storage signals honored. |
| **U-21 — Canonical analysis hop** | ✅ **RESOLVED** | §17.5 | **FFT window 2048 samples · analysis hop 480 samples · native frame rate exactly 100 Hz** at the §17.3 canonical 48 kHz. Overlap 76.5625% is *derived*, never an input. The v2.0 "50% overlap" figure is **superseded** and must not be cited as canonical. Spectral frames are measured at the storage rate and **never interpolated or upsampled** to reach it. |
| **U-1 — DI framework** | ✅ **RESOLVED** (prior pass) | §6, §6.1 | **Hilt** |
| **U-2 — Minimum Android API** | ✅ **RESOLVED** (prior pass) | §6, §6.1 | **minSdk 35 / compileSdk 36 / targetSdk 36** |

**No conflict was found between P-1, P-2, P-3, P-5, P-6 and any existing normative requirement.** P-4's "1024 bins" is arithmetically consistent with §17.2's 2048-sample window (a 2048-point real FFT yields 1025 unique bins; retaining 1024 discards DC). One conflict was found during that pass, reported rather than silently resolved, and **has since been ratified as U-21 — see §B.**

**No Appendix B items U-22 … U-26 were created.** Per your instruction, resolved decisions were incorporated directly as normative specification text; a register of *unresolved* decisions is the wrong home for settled ones. Only the single genuinely-open item (U-21) was added — and it is now resolved, leaving Phase 1 with no open Appendix B item of any kind.

---

## B. The Conflict Reported Last Pass — Now Resolved (U-21)

Fixing the canonical rate at 48 kHz made an arithmetic tension in §17.2 explicit: the inherited **"50% overlap"** default and the mandated **100 Hz** storage timeline could not both be native. That conflict was reported rather than silently resolved. It has now been ratified:

**Canonical framing — three distinct quantities, never conflated (§17.5):**

| Quantity | Canonical value | Nature |
|---|---|---|
| FFT window size | **2048 samples** (≈42.667 ms, 23.4375 Hz bins) | Unchanged input |
| Analysis hop | **480 samples** (10 ms exactly) | Input — canonical |
| Native analysis frame rate | **100 Hz** (`48000/480`) | Consequence of the two above |

Window overlap is `(2048 − 480)/2048 = 76.5625%` — **derived, never an input**. The v2.0 "50% overlap" figure is superseded and must not be retained, cited, or assumed anywhere.

**Binding consequence:** because the native rate equals the storage rate, FFT-derived frames are written one-for-one as **measured** frames. Spectral frames are never interpolated or upsampled to satisfy the 100 Hz timeline. §17.2's resample/align rule still governs features whose own native rate differs (e.g. a tempo estimate over a longer window) and is explicitly inapplicable to the FFT path.

**Derived framing consequences, recorded so they are specified rather than assumed:**
- **Frame anchoring is the window START.** Frame `n` covers samples `[n·480, n·480 + 2048)` and is stored at `n · 10 ms` from the §9.1 epoch. Centre-anchoring would place frames at `n·10 ms + 21.333 ms`, never landing on the 10 ms grid — reintroducing exactly the interpolation the ratification forbids. Start-anchoring is forced, not chosen.
- **Tail handling:** `N = ceil(totalSamples / 480)` frames, final windows zero-padded, making `N` a pure function of asset length.
- **Onset/beat temporal resolution is 10 ms**, not 21.3 ms.

**Cache identity:** hop is an `analysisConfigHash` input in its own right (§18.2 item 5), independent of FFT size and frame rate.

## C. Mandatory Phase 1 Requirements Incorporated

| Requirement | Status |
|---|---|
| **§129 — Coordinate (§13.1), Color (§90.1), Clock (§14.1) as testable primitives** | ✅ Was already present in §129; now restated under an explicit "Mandatory Phase 1 deliverables, stated explicitly so they cannot be read as optional" heading, with the rationale that two of the three are renderer-facing and must still be proven in Phase 1 so Phase 2 inherits them rather than inventing them under deadline. |
| **§85.1 — Command data model first-class from Phase 1** | ✅ Was already present; a **scope guard** has been added: the model, apply/invert contract, undo stack and coalescing policy exist and are tested in Phase 1, and this explicitly does **not** authorize timeline editing (Phase 6), renderer (Phase 2), reactive evaluation (Phase 3), or product UI. |

---

## D. Audit Results

All audits re-run against the amended file by direct inspection after the U-21 ratification.

| # | Audit | Result |
|---|---|---|
| 1 | **Consistency** | ✅ Clean. §17.3, §17.4, §17.5, §18.2, §18.3 in correct numeric order; no contradiction with §17.2, §18, §18.1, §19, §20, §22.2, §27.2, §98.1, §106, §119. The three framing quantities (window / hop / frame rate) are stated distinctly and consistently everywhere they appear. |
| 2 | **Superseded "50% overlap"** | ✅ Three remaining occurrences, all explicitly historical — §17.2's note, §17.5's derivation, and U-21's row. **None presents it as canonical.** |
| 3 | **Cross-reference** | ✅ Clean. All `AR-#.#` in range `AR-1.1`–`AR-19.5`; zero invalid; `AR-58` absent. All `item U-#` citations resolve correctly. §17.5 referenced from 9 sites, all coherent. |
| 4 | **Stale / TBD / implementation-defined** | ✅ **Zero matches.** |
| 5 | **Appendix B gate** | ✅ 21 rows, 6 columns, uniform structure. U-1, U-2, U-21 `RESOLVED`; U-3…U-20 `OPEN` with owner and target phase. §128's owner + target-phase clause satisfied for every row. |
| 6 | **Regression — original 11 findings** | ✅ **All 11 still fixed**, individually re-verified. |
| 7 | **Regression — N1–N7** | ✅ **All 7 still fixed**, individually re-verified (incl. Owner column 21/21, both `assetRef`→`assetHash` sites, 5/5 `Blocked by:` lines). |
| 8 | **New CRITICAL / HIGH findings** | ✅ **None.** |
| 9 | **Phase 1 load-bearing decisions** | ✅ **None remaining.** §129 now reads "Blocked by: nothing." |

---

## E. Phase 1 GO / NO-GO

# GO FOR PHASE 1 — UNCONDITIONAL

No scoped hold remains. All ten build steps are cleared:

| Step | Work | Status |
|---|---|---|
| 1 | Gradle skeleton, Hilt, minSdk 35 / compileSdk 36 / targetSdk 36, **§116.1 boundary check first** | Cleared |
| 2 | `core:model`, `core:time` (+ §13.1 coordinate, §90.1 color primitives) | Cleared |
| 3 | `core:diagnostics` | Cleared |
| 4 | `core:assets` (import, SHA-256, SAF, missing-asset, cache tiers) | Cleared |
| 5 | `audio:decoder` + `testing:audio` fixtures (§119) | Cleared |
| 6 | Derived preview cache — waveform peak pyramid | Cleared |
| 7 | `audio:playback` + master clock + underrun assertions | Cleared |
| 8 | `audio:analysis` stages 1–2 | **Cleared by U-21** |
| 9 | `audio:cache` + determinism suite | **Cleared by U-21** |
| 10 | `audio:analysis` stages 3–5, `audio:beat` | **Cleared by U-21** |

---

## F. Remaining Architectural Decisions That Could Force a Rewrite After Phase 1 Begins

| Decision | Rewrite risk | Mitigation |
|---|---|---|
| **U-21 — FFT hop** | ✅ **ELIMINATED.** Ratified as 480 samples; every dependent reference, hash input, cache-identity clause, and golden-vector definition updated in the same pass. | Closed. |
| **FP16 precision at very low amplitude (§17.4)** | **MEDIUM — the only remaining item that could change the cache format.** If §119's "very quiet signal" fixture shows FP16 linear magnitude loses too much precision, the documented fallback is a dB-domain variant, which is a `formatVersion` bump. | Anticipated in §17.4 with a defined fallback and an existing version mechanism — bounded, not open-ended. Measured early, in Phase 1's precision tests. |
| **U-5 — SSIM thresholds** | LOW. Empirical, no structural dependency. | Explicitly non-blocking (§129). |
| **U-9 / U-11 — GPU and CPU budget numbers** | LOW for Phase 1 — no renderer exists. | Provisional values permitted; baselines set in Phase 1. |
| **U-19 — WASM host ABI** | HIGH for **Phase 7**, zero for Phase 1. | Hard-gated at §57/§135; Phase 1 builds nothing depending on it. |
| **Multichannel (>2 ch) downmix generalization (§17.3)** | LOW. Flagged in-text as a derived generalization, visible and overridable; affects only >2-channel sources. | Override costs a `formatVersion` bump at worst. |

**Nothing else in the specification is capable of forcing a Phase 1 rewrite.** Cache identity (§18.2), format versioning (§18.3), analysis framing (§17.5), determinism epoch (§9.1), and the time-domain contract (§14.1) are now all fully specified.

---

*This document records the state at which Phase 1 implementation was authorized to begin.*
