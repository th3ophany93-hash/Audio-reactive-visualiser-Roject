# FINAL PHASE 1 GATE

**Purpose:** Record the ratification of P-1 … P-6, the residual item that ratification exposed, and the final Phase 1 verdict.
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
| **U-1 — DI framework** | ✅ **RESOLVED** (prior pass) | §6, §6.1 | **Hilt** |
| **U-2 — Minimum Android API** | ✅ **RESOLVED** (prior pass) | §6, §6.1 | **minSdk 35 / compileSdk 36 / targetSdk 36** |

**No conflict was found between P-1, P-2, P-3, P-5, P-6 and any existing normative requirement.** P-4's "1024 bins" is arithmetically consistent with §17.2's 2048-sample window (a 2048-point real FFT yields 1025 unique bins; retaining 1024 discards DC). **One conflict was found and is reported rather than resolved — see §B.**

**No Appendix B items U-22 … U-26 were created.** Per your instruction, resolved decisions were incorporated directly as normative specification text; a register of *unresolved* decisions is the wrong home for settled ones. Only the single genuinely-open item (U-21) was added.

---

## B. Conflict Found and NOT Silently Resolved — U-21

Fixing the canonical rate at 48 kHz (P-2) made an arithmetic tension inside §17.2 explicit. §17.2 simultaneously carries an inherited **"50% overlap"** FFT default and mandates a **100 Hz** storage timeline. At 48 kHz those are not the same thing:

| Option | Native frame rate | Consequence |
|---|---|---|
| **50% overlap** (1024-sample hop) — the literal inherited default | **46.875 Hz** | Must be *upsampled* to the mandated 100 Hz. Every stored spectrum frame between two computed frames is interpolated rather than measured, and the retained spectrum costs ≈**2.13× its own information content** — P-4's full storage price paid for partly-fabricated data. Onset/beat temporal resolution 21.3 ms. |
| **480-sample hop** (76.6% overlap) | **exactly 100 Hz** | Every stored frame is a measured frame. Onset/beat resolution 10 ms — materially tighter audio-reactive sync, which is this product's core value (§2). |

This is load-bearing: it changes the numerical content of every cache entry and every golden vector, and P-3 itself lists "FFT/hop configuration" and "analysis frame rate" as *separate* hash members, confirming they are distinct inputs. Deciding it after `audio:analysis` exists means re-cutting every golden vector.

**Recorded as Appendix B U-21, OPEN.** Recommendation: the 480-sample hop, on the grounds that storing measured rather than interpolated spectra is both cheaper in information terms and better for the beat-sync quality the product is built around — but this is stated as a recommendation, not applied.

**Blast radius is scoped, not phase-wide:** U-21 blocks `audio:analysis` and `audio:cache` (build steps 8–10). It does **not** block build/CI, `core:model`, `core:time`, `core:diagnostics`, `core:assets`, `audio:decoder`, the waveform peak cache, or `audio:playback` (steps 1–7).

---

## C. Mandatory Phase 1 Requirements Incorporated

| Requirement | Status |
|---|---|
| **§129 — Coordinate (§13.1), Color (§90.1), Clock (§14.1) as testable primitives** | ✅ Was already present in §129; now restated under an explicit "Mandatory Phase 1 deliverables, stated explicitly so they cannot be read as optional" heading, with the rationale that two of the three are renderer-facing and must still be proven in Phase 1 so Phase 2 inherits them rather than inventing them under deadline. |
| **§85.1 — Command data model first-class from Phase 1** | ✅ Was already present; a **scope guard** has been added: the model, apply/invert contract, undo stack and coalescing policy exist and are tested in Phase 1, and this explicitly does **not** authorize timeline editing (Phase 6), renderer (Phase 2), reactive evaluation (Phase 3), or product UI. |

---

## D. Audit Results

All audits re-run against the amended file by direct inspection.

| # | Audit | Result |
|---|---|---|
| 1 | **Consistency** | ✅ Clean. New sections §17.3, §17.4, §17.5, §18.2, §18.3 sit in correct numeric order between their parents; no contradiction introduced with §17.2, §18, §18.1, §19, §20, §22.2, §27.2, §98.1, §106, or §119. |
| 2 | **Cross-reference** | ✅ Clean. All `AR-#.#` references in range `AR-1.1`–`AR-19.5`; zero invalid; `AR-58` absent. All 24 `item U-#` citations resolve to correct rows, including the four new ones (§17.2→U-21, §17.5, §18.2→U-21, §129→U-21). |
| 3 | **Stale / TBD / implementation-defined** | ✅ **Zero matches** for `TBD`, `to be decided`, `implementation-defined`, `will be specified later`, `can be chosen during implementation`, `defined at implementation time`, `decide later`. The previously-sole match (inside U-19's self-describing row) no longer trips the scan. |
| 4 | **Appendix B gate** | ✅ 21 rows, 6 columns, header/separator/rows all consistent. U-1 and U-2 `RESOLVED`; U-3…U-21 `OPEN` with owner and target phase recorded. §128's owner+target-phase clause satisfied for every row. |
| 5 | **Original 11 findings** | ✅ **All 11 still fixed** — individually re-verified (U-12 citation; no false Appendix-A schema pointer; 4-part cache key; U-19 hard gate; no `§18.3` bogus ref; `AR-14.1 (Review)` form; NETWORK removal; AR-1.4/5.2 marked N; bracket tags at §38/§40/§57; `plugins/system` split; U-20 gate). |
| 6 | **N1–N7** | ✅ **All 7 still fixed** — `AR-58` gone and replaced by a `[NOTED]` tag; Owner column populated 21/21; §74 ABI caveat present; `assetRef`→`assetHash` indirection present at **both** §18.1 and §27.2 and consistent; no `~~NETWORK~~` in a code span; 5/5 `Blocked by:` lines; `ProjectState`/`RendererState` normalized. |
| 7 | **New CRITICAL / HIGH findings** | ✅ **None.** The one finding raised (U-21) is a scoped, recorded, MEDIUM-HIGH open decision, not a defect in the amended text. |
| 8 | **Phase 1 load-bearing decisions** | ⚠️ **One remains: U-21**, scoped to `audio:analysis` + `audio:cache`. Everything else Phase 1 needs is now specified. |
| 9 | **No competing disk budget** | ✅ Verified — §18.3 is the only disk budget in the document; nothing pre-existing was overridden. |

---

## E. Phase 1 GO / NO-GO

# GO FOR PHASE 1 — with one scoped hold

**Cleared to start immediately (build steps 1–7):**
1. Gradle skeleton, Hilt, minSdk 35 / compileSdk 36 / targetSdk 36, **§116.1 module dependency-boundary CI check first**
2. `core:model`, `core:time` (+ §13.1 coordinate and §90.1 color primitives with tests)
3. `core:diagnostics`
4. `core:assets` (import, SHA-256 hashing, SAF, missing-asset path, three-tier cache scaffolding)
5. `audio:decoder` + format tests; `testing:audio` fixtures (§119)
6. Derived preview cache — waveform peak pyramid
7. `audio:playback` + master clock + underrun assertions

**On hold pending U-21 (build steps 8–10):** `audio:analysis`, `audio:cache`, `audio:beat`, and every golden analysis vector.

U-21 is a single question with two candidate answers and a stated recommendation; it does not require further investigation, only a decision.

---

## F. Remaining Architectural Decisions That Could Force a Rewrite After Phase 1 Begins

Stated plainly, because this is the question that matters:

| Decision | Rewrite risk if deferred | Mitigation |
|---|---|---|
| **U-21 — FFT hop** | **HIGH, and immediate.** Changes every cached value and every golden vector. This is the only open item that can force Phase 1 rework. | Decide before build step 8. Steps 1–7 are unaffected. |
| **FP16 precision at very low amplitude (§17.4)** | **MEDIUM.** If §119's "very quiet signal" fixture shows FP16 linear magnitude loses too much precision, the documented fallback is a dB-domain variant — a **format** change requiring a `formatVersion` bump. | Already anticipated in §17.4 with a defined fallback and a `formatVersion` mechanism; contained by design rather than open-ended. Measured in Phase 1's precision tests. |
| **U-5 — SSIM thresholds** | LOW. Empirical values, refined once real renders exist; no structural dependency. | Explicitly non-blocking per §129. |
| **U-9 / U-11 — GPU and CPU budget numbers** | LOW for Phase 1. Provisional values are permitted and Phase 1 has no renderer. | Baselines established in Phase 1, finalized Phase 3/10. |
| **U-19 — WASM host ABI** | HIGH **for Phase 7**, zero for Phase 1. | Already a hard gate at §57/§135; Phase 1 builds nothing that depends on it. |
| **Multichannel (>2 ch) downmix generalization (§17.3)** | LOW. Flagged in the text as a derived generalization rather than an independent ratification, so it is visible and overridable. Affects only sources with more than two channels. | Named explicitly in §17.3; override costs a `formatVersion` bump at worst. |

Nothing else in the specification is capable of forcing a Phase 1 rewrite. The cache identity model (§18.2), format versioning (§18.3), determinism epoch (§9.1), and time-domain contract (§14.1) are now all fully specified, which is what makes the remaining risk this small.

---

*No application code, Gradle configuration, or Android project scaffolding was created in the production of this document.*
