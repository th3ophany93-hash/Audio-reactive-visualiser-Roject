# FINAL ARCHITECTURE GATE — MASTER_SPECIFICATION v3.0

**Purpose:** Record the resolution of the two remaining Phase 1 blockers (U-1, U-2) identified in FINAL_PRE_IMPLEMENTATION_AUDIT_v2.md, the resulting Android build-target ratification, and the final gate verdict.
**Scope discipline:** This pass resolved exactly U-1 and U-2 (plus the build-target ratification they imply) and updated only the sections that reference them. No architectural redesign, no other Appendix B item resolved, no Phase 1 requirement changed, no application code written, no Android project created.

---

## 1. U-1 Resolution

**Decision:** Dependency Injection framework = **Hilt**.

**Location:** §6 (summary line) and new §6.1 "Ratified Android Build Targets" — `[RESOLVED — Appendix B U-1]`.

**Consequence:** §157.1's platform-singleton rule ("a small, explicit, documented, dependency-injected set of owned platform singletons... injected via DI rather than referenced as bare `object`/static fields") now has a named mechanism: every such singleton (`GLContextHolder`, `AudioEngine`, etc.) is a Hilt-injected component.

---

## 2. U-2 Resolution

**Decision:** Minimum supported Android API level = **API 35 (Android 15)**.

**Location:** §6 (summary line) and §6.1 — `[RESOLVED — Appendix B U-2]`.

---

## 3. Final Android Build Targets (ratified)

| Target | Value | Platform |
|---|---|---|
| `minSdk` | **35** | Android 15 |
| `compileSdk` | **36** | Android 16 |
| `targetSdk` | **36** | Android 16 |

**Rationale (recorded in §6.1):**
- **Nothing Phone (1)**, Android 15 / API 35 — minimum real-device test platform.
- **Xiaomi 14 Pro**, Android 16 / API 36 — primary higher-tier real-device test platform.
- Android 14 and lower are intentionally **not supported** in v1.
- API 36 is the development/target platform (`compileSdk`/`targetSdk`); API 35 remains the minimum runtime platform (`minSdk`).

**Explicitly not resolved by this ratification:** Appendix B item **U-17** (the full device-matrix hardware list for §122's Device Matrix Smoke Tests) remains open. Nothing Phone (1) and Xiaomi 14 Pro are recorded here only as the rationale anchoring the minSdk/compileSdk/targetSdk decision — they are not, by themselves, a resolution of U-17's broader question (which SoCs/RAM tiers/tablet models to standardize testing on across Phases 10–11). This distinction is stated explicitly in §6.1 and in U-17's own Appendix B row, so a future reader cannot mistake "we named two test phones" for "the device matrix is decided."

---

## 4. Verification Performed This Pass

| # | Check | Result |
|---|---|---|
| 1 | U-1 and U-2 marked resolved | **CONFIRMED.** Appendix B rows for U-1/U-2 carry `**RESOLVED**` in a new `Status` column; §6/§6.1 state the resolutions with `[RESOLVED — Appendix B U-#]` tags; §129's "Blocked by:" line states both are resolved. |
| 2 | No other Appendix B item incorrectly marked as a Phase 1 blocker | **CONFIRMED.** Full-text search for `Phase 1` across the document's "Blocks phase" column shows exactly three rows reference Phase 1: U-1 (now resolved), U-2 (now resolved), and U-5 — which was already, and remains, explicitly annotated as non-blocking to Phase 1's *start* (empirical SSIM tuning, refined once real renders exist). No row was added, removed, or silently reassigned to Phase 1. |
| 3 | Phase 1 requirements unchanged | **CONFIRMED.** §129's requirement line — "Audio: import, decode, playback, waveform, trim, analysis, cache." — and its first explanatory paragraph (core/model, module boundary CI check, Coordinate/Color/Clock decisions) are byte-for-byte unchanged from the prior audited version. Only the "Blocked by:" line beneath them was updated, from naming U-1/U-2 as open hard blockers to recording their resolution. |
| 4 | Full cross-reference audit | **CONFIRMED CLEAN.** Every `AR-#.#` occurrence (149 instances) re-verified in range `AR-1.1`–`AR-19.5`, none orphaned, none newly introduced or removed. Every `item U-#` citation (23 instances, one new: §6.1's reference to U-17) re-verified topically correct against its Appendix B row. The Appendix B table's column structure (6 columns: `#, Decision, Nature, Owner, Status, Blocks phase`) is consistent across the header, separator, and all 20 rows. |
| 5 | Stale-reference / contradiction audit | **CONFIRMED CLEAN.** No remaining statement anywhere in the document claims DI framework or minimum API level are "not resolved" (the only surviving such claim, at §6, now correctly scopes itself to U-3/WASM runtime only). No new contradiction was introduced between §6/§6.1's resolution and any other section that references DI, `minSdk`, `compileSdk`, or `targetSdk` — no such other section exists yet, since these values were previously undefined everywhere. |
| 6 | "Defined at implementation time / TBD / implementation-defined" audit | **CONFIRMED CLEAN.** Only one match anywhere in the document, inside Appendix B item U-19's own description, which is itself quoting the phrase to describe the past problem it corrects (not a live, ungated deferral). No new instance of this pattern was introduced by resolving U-1/U-2. |
| 7 | No new CRITICAL or HIGH findings | **CONFIRMED.** None found. This was a narrow, additive resolution of two named decisions plus the build-target values they implied; no new architectural surface, no new companion-document dependency, and no new terminology were introduced beyond what §6.1 self-consistently defines and cross-references. |

---

## 5. Phase 1 Gate Status

**Phase 1 has no unresolved hard blockers.**

- U-1 (DI framework): **RESOLVED — Hilt.**
- U-2 (minimum Android API level): **RESOLVED — API 35.**
- U-5 (exact SSIM thresholds): open, but explicitly non-blocking to Phase 1's start (empirical, refined once real renders exist — §129, §77.1).

Every other Appendix B item (U-3, U-4, U-6 through U-20) targets a phase later than Phase 1, or is deliberately deferred with no phase attached (U-7, U-12–U-15, U-18), or is a rubber-stamp roadmap confirmation (U-18). None of them gate Phase 1's start.

---

## 6. Remaining Future-Phase Gates (unchanged by this pass, listed for completeness)

| Item | Blocks | Status |
|---|---|---|
| U-3 | Phase 7 | OPEN — WASM runtime library selection + feasibility spike |
| U-4 | Phase 7, ongoing | OPEN — shim-support window policy |
| U-5 | Phase 1 infra, refined through all phases | OPEN — non-blocking |
| U-6 | Phase 8 | OPEN — Foreground Service type classification |
| U-7 | Post-v1 | OPEN — deliberately deferred |
| U-8 | Before Phase 2 UI | OPEN — accessibility conformance target |
| U-9 | Phase 3 (survey), Phase 2+ provisional | OPEN — provisional defaults permitted |
| U-10 | Phase 10 | OPEN — Adaptive Quality Ladder thresholds |
| U-11 | Phase 3+, finalized Phase 10 | OPEN — provisional split given |
| U-12 | If reopened | OPEN — deliberately deferred (single-audio-track scope) |
| U-13 | Post-v1 | OPEN — deliberately deferred (Color Grading scheduling) |
| U-14 | Post-v1, if ever | OPEN — deliberately deferred (networked plugin class) |
| U-15 | Post-v1, if ever | OPEN — deliberately deferred (HDR/wide-gamut) |
| U-16 | Phase 7 | OPEN — `.arp` signature scheme |
| U-17 | Phase 10–11 | OPEN — full device-matrix hardware list (two anchor devices now named in §6.1 as build-target rationale only; matrix itself still undecided) |
| U-18 | N/A, roadmap confirmation | OPEN — Vulkan deferral confirmation |
| U-19 | **Phase 7 — hard gate** | OPEN — complete WASM Host ABI (Analyzer + Custom Layer/Generator), must be authored and security-reviewed before any Tier-2 plugin implementation begins |
| U-20 | Phase 7, before UI-schema-generation | OPEN — Plugin UI Schema declarative grammar |

None of these block Phase 1. U-19 remains the single most consequential open item for the project as a whole (it hard-gates all Tier-2 plugin work at Phase 7), but Phase 7 is several phases away from where the project is about to start.

---

## FINAL VERDICT

# GO FOR PHASE 1

Both remaining Phase 1 blockers are resolved:
- **Dependency Injection: Hilt.**
- **Minimum Android API: 35 (Android 15); compileSdk/targetSdk: 36 (Android 16).**

All eleven originally-reported findings remain fixed. All seven post-fix corrections (N1–N7) remain fixed. No new CRITICAL or HIGH findings were introduced by this pass. The cross-reference, stale-deferral-phrase, and terminology audits are clean. Phase 1's own requirements are unchanged from the last audited version — only its blocker status changed, from "blocked by U-1, U-2" to "no unresolved hard blockers."

Phase 1 (Audio Engine: import, decode, playback, waveform, trim, analysis, cache) may now begin, subject to all standing process requirements already specified in this document (§124 Root-Cause Protocol, §125 Claude Must Self-Diagnose, the isolation/golden-test discipline of §77–§80, and every other section unaffected by this pass).

---

*No Phase 1 work, application code, or Android project scaffolding was created in the production of this document.*
