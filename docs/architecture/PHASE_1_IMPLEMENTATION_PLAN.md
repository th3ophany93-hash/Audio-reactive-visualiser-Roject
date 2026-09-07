# PHASE 1 IMPLEMENTATION PLAN — AUDIO ENGINE

**Status:** **PROPOSED — AWAITING APPROVAL.** No code has been written. No Android project exists.
**Governing document:** MASTER_SPECIFICATION_v3.0.md (all `§` references are to it).
**Gate:** FINAL_ARCHITECTURE_GATE.md — GO FOR PHASE 1 (U-1 = Hilt, U-2 = minSdk 35 / compileSdk 36 / targetSdk 36).
**Prime directive (§159):** this plan builds the production Audio Engine that Phases 2–11 sit on top of. Nothing here is a prototype to be replaced later.

> **UPDATE — P-1 … P-6 have been RESOLVED and ratified into the specification.** See §17 of this plan for their dispositions and the one residual item they exposed (U-21, default FFT hop). The plan below is otherwise unchanged and remains the approved approach.
>
> - **P-1 → §17.3** (canonical mono analysis signal) · **P-2 → §17.3** (48 kHz canonical rate) · **P-3 → §18.2** (`analysisConfigHash` membership) · **P-4 → §17.4** (1024 bins / FP16 / 100 Hz) · **P-5 → §14.1** (translation-scope clarification) · **P-6 → §18.3** (formatVersion, 1 GB budget, LRU eviction)
> - **U-21 → §17.5 (RESOLVED):** FFT window 2048 samples · analysis hop **480 samples** · native frame rate exactly **100 Hz**. Overlap 76.5625% is derived, never an input; "50% overlap" is superseded. Spectral frames are measured, never interpolated to reach the storage rate. **Phase 1 has no remaining blockers — all ten build steps are cleared.**

---

## 1. Current Repository / Module State

Verified by direct inspection at commit `62c76dc`:

| Aspect | State |
|---|---|
| Tracked files | 5, all under `docs/architecture/` |
| Source code | **None** |
| Gradle / Android project | **None** — no `settings.gradle`, `build.gradle`, `gradle/`, `app/`, no wrapper |
| Modules | **None** — the §116 module graph exists only as specification text |
| CI | **None** — no `.github/workflows`, no dependency-boundary check |
| Test infrastructure | **None** |
| Documents present | `MASTER_SPECIFICATION_v3.0.md`, `ARCHITECTURE_REVIEW.md`, `FINAL_PRE_IMPLEMENTATION_AUDIT.md`, `FINAL_PRE_IMPLEMENTATION_AUDIT_v2.md`, `FINAL_ARCHITECTURE_GATE.md` |

**Gate conformance:** the repository state is consistent with the gate — Phase 0 produced documents only, and no implementation was started ahead of approval.

**Two conformance gaps against §126/§127 that Phase 1 must close** (not blockers, but they are requirements the repo does not yet satisfy):

- **§126** requires `PROJECT_STATUS.md, ARCHITECTURE.md, TEST_PLAN.md, RENDERING_NOTES.md, PROJECT_FORMAT.md, PLUGIN_API.md, PLUGIN_SECURITY.md, PERFORMANCE.md, EXPORT.md, KNOWN_ISSUES.md` — **none exist.** Phase 1 creates the subset it earns: `PROJECT_STATUS.md`, `ARCHITECTURE.md`, `TEST_PLAN.md`, `PERFORMANCE.md`, `KNOWN_ISSUES.md`. The renderer/plugin/export documents are created by the phases that produce their content.
- **§127** requires ADR-001…ADR-012 — **none exist.** Phase 1 writes **ADR-002 (Audio Analysis)** and **ADR-012 (Testing Strategy)**, because Phase 1 makes the binding decisions those two record. The remaining ADRs are written by their owning phases.

---

## 2. Phase 1 Dependency Graph

Strictly a prefix of §116.1's enforced graph — no new edges, no back-edges:

```
core/model        (pure Kotlin — no Android, no GL, no coroutines in the type layer)
    ▲
core/time         (TimelineTime / AudioSourceTime / SampleIndex, trim mapping — pure Kotlin)
    ▲
core/diagnostics  (structured logging §99, timing spans §100.1, error taxonomy §97)
    ▲
core/assets       (Asset Registry §82, SAF, content hashing, derived-preview cache §82.1 tier 2)
    ▲
audio/decoder     (headless PCM decode — Media3/MediaCodec, §7)
    ▲
audio/analysis    (DSP + progressive scheduler §17/§17.1/§17.2)  ──uses──▶  audio/beat (§21)
    ▲
audio/cache       (AudioAnalysisCache §18/§18.1 — in-memory lock-free view + on-disk binary)
    ▲
audio/playback    (playback + master clock §14.1)
    ▲
core/project      (in-memory ProjectState + Command model §85.1 — NOT persistence)
    ▲
app               (Hilt wiring + debug-only harness Activity)
```

`testing/*` modules depend downward onto everything they exercise and are depended on by nothing.

**Modules explicitly NOT created in Phase 1:** `reactive/`, `renderer/`, `layers/`, `effects/`, `plugins/*`, `timeline/`, `export/`, `ui/` (beyond the debug harness). Creating any of them now would be premature architecture.

---

## 3. Modules / Packages Phase 1 Creates

All new; nothing to modify (empty repository).

| Module | Gradle type | Android dep? | Spec basis |
|---|---|---|---|
| `core:model` | Kotlin JVM library | No | §10, §11, §13.1, §90.1, §97 |
| `core:time` | Kotlin JVM library | No | §9.1, §14.1, §17.2 |
| `core:diagnostics` | Kotlin JVM library *(revised in Step 1 — see note)* | No | §97, §98, §98.1, §99, §100.1 |
| `core:assets` | Android library | Yes (SAF, DocumentFile) | §82, §82.1 |
| `core:project` | Kotlin JVM library | No | §10, §85, §85.1, §102, §102.1 |
| `audio:decoder` | Android library | Yes (MediaCodec/Media3) | §7, §15 |
| `audio:playback` | Android library | Yes | §14.1, §15, §16 |
| `audio:analysis` | Kotlin JVM library + Android glue | Mostly no | §17, §17.1, §17.2, §19, §20 |
| `audio:beat` | Kotlin JVM library | No | §21 |
| `audio:cache` | Android library | Yes (cacheDir) | §18, §18.1, §27.2 |
| `app` | Android application | Yes | §6, §6.1 (Hilt), debug harness only |
| `testing:audio` | Test fixtures library | No | §119 |
| `testing:golden` | Test harness library | No | §77.1, §120.1 |
| `testing:performance` | Test harness library | Yes | §87, §121 |

> **Revision (Step 1, implemented Step 3) — `core:diagnostics` is a Kotlin JVM library, not an Android library.**
> The plan originally typed it Android because §98.1 names `ComponentCallbacks2.onTrimMemory` and §99 implies `android.util.Log`. Building it that way turned out to poison every pure-JVM module that needs logging: an Android AAR cannot be consumed by a JVM module, and `audio:analysis`, `audio:cache`, `audio:beat` and `core:project` all sit downstream of it in §116.1's graph. Rather than make the DSP modules Android-dependent — which would have cost them JVM unit-testability for the sake of two platform symbols — the module was made platform-neutral, with `android.util.Log` supplied as a `LogSink` and `ComponentCallbacks2` levels translated into `MemoryPressureLevel`, both installed by `:app` at the composition root (§157.1's "injected, not reached for"). §98.1 and §99 are satisfied in full; only the location of the two Android bindings changed. Enforced by the `pureKotlinModules` guard in the root build script.

**Build configuration (§6.1):** `minSdk 35`, `compileSdk 36`, `targetSdk 36`, Kotlin, Hilt, Compose (dependency present for the debug harness only).

**CI (§116.1) — mandatory Phase 1 deliverable, not optional tooling:** a build-failing module-dependency check. Implemented as Gradle module visibility (`api`/`implementation` boundaries) **plus** an explicit allowed-edge assertion test that fails the build on any disallowed import. §116.1 requires this "before Phase 1 code lands" — so it is the *first* thing built, not the last.

---

## 4. Module Responsibilities

**`core:model`** — Owns the §10 Core Data Model as pure data: `Project`, `AudioTrackRef`, `AssetRef`, `Layer`, `Parameter`, `ReactiveMapping`, `Mask`, enums (`BlendMode`, `CombineOp`, `ParameterUnit`, `KnobClass`). Owns §13.1's unit system (`LogicalUnit | Normalized01 | RawPixel`, `pixelsPerLogicalUnit`) and §90.1's sRGB↔linear conversion as pure functions. Owns §97's error taxonomy as a sealed hierarchy. **Declares** `Parameter`/`ReactiveMapping` shapes; **evaluates nothing** — evaluation is Phase 3.

**`core:time`** — The time-domain foundation. Distinct, non-interchangeable types: `TimelineTime` (relative to trimmed selection t=0), `AudioSourceTime` (absolute raw-asset time — the §9.1 epoch), `SampleIndex`, `AnalysisFrameIndex` (100 Hz, §17.2). Owns the single `TrimMapping.toAudioTime(TimelineTime) = T + trimIn` implementation (§9.1/§14.1) and the 100 Hz frame↔time conversions plus the linear-interpolation rule (§17.2).

**`core:diagnostics`** — Structured, subsystem-tagged, rate-limited logging (§99). Named timing spans feeding a per-subsystem breakdown (§100.1). Error reporting against the §97 taxonomy — never a bare message. Diagnostic report assembly (§98). Memory-pressure dispatch (§98.1).

**`core:assets`** — Asset Registry (§82): stable `assetId`, SAF URI, persisted-permission flag, content `hash`, metadata (duration, sample rate, channels). Lazy validation with recoverable "Missing Asset" outcome (§82.1) — never a crash. Owns cache tier 2, the **derived preview cache** (§82.1): multi-resolution waveform peak pyramid, keyed by `(assetHash, peakFormatVersion)`.

**`core:project`** — In-memory `ProjectState` as the single source of truth (§102.1) and the §85 `Command` model with apply/invert, plus the §85.1 coalescing policy. **No file persistence** — that is Phase 9 (§137).

**`audio:decoder`** — Headless, playback-independent decode of WAV/MP3/M4A-AAC/FLAC/OGG (§15) to PCM. Two access modes over one implementation: streaming (playback) and full/offline (analysis, and later export §17.1). Independence is a §15 requirement, not a convenience.

**`audio:playback`** — Playback transport (play/pause/seek/loop-selection/gain/mute per §16) and the **master clock** (§14.1). Publishes `TimelineTime`. Guarantees no dropouts under analysis load (§14.1: "dropped or resampled audio is not acceptable under any circumstance").

**`audio:analysis`** — All DSP: RMS, Peak, Energy, Normalized Energy, Loudness Approximation, FFT magnitude + log spectrum, the §20 band set (default + custom), Spectral Centroid/Flux/Rolloff/Flatness, Chroma (§17). Owns the §17.1 progressive priority scheduler. Owns normalization (§19).

**`audio:beat`** — Onset strength, beat probability, beat phase, tempo, and the `beatConfidence` reliability channel (§21). Separate module because §21 forbids beat from becoming load-bearing for spectral analysis.

**`audio:cache`** — `AudioAnalysisCache` (§18): immutable-once-written, append-only, lock-free readable, never blocks a reader (§18.1). Content-addressed on-disk binary keyed `(assetHash, analysisConfigHash)` with a format version. Exposes read-by-time with the §17.2 interpolation rule and an explicit "analysis pending" status (§18.1).

**`app`** — Hilt composition root (§6.1). Owns the platform singletons §157.1 permits (`AudioEngine`; `GLContextHolder` arrives in Phase 2), injected, never static. Hosts a **debug-only** harness Activity: import a file, see the waveform, set trim, play, watch analysis progress. This is a test instrument, not the product UI (§103's real UI is later).

---

## 5. Public Interfaces / Contracts

Signatures are indicative of the contract, not final Kotlin.

```kotlin
// core:time — the §9.1 / §14.1 foundation
@JvmInline value class AudioSourceTime(val micros: Long)   // epoch = t=0 of the RAW asset
@JvmInline value class TimelineTime(val micros: Long)      // relative to trimmed selection t=0
class TrimMapping(val trimIn: AudioSourceTime, val trimOut: AudioSourceTime) {
    fun toAudioTime(t: TimelineTime): AudioSourceTime      // §14.1: audioT = T + trimIn
    fun duration(): TimelineTime
}

// core:assets — §82 / §82.1
interface AssetRegistry {
    suspend fun import(uri: Uri): Result<AssetRef, ImportError>   // takes persistable permission, hashes
    fun resolve(id: AssetId): AssetRef?
    suspend fun validate(id: AssetId): AssetAvailability           // Available | MissingRelinkRequired
    fun contentHash(id: AssetId): AssetHash                        // §27.2 requires this indirection
}
interface DerivedPreviewCache {
    suspend fun waveformPeaks(asset: AssetHash, level: PeakLevel): PeakData   // §16 immediate feedback
}

// audio:decoder — §15 (headless, playback-independent)
interface AudioDecoder {
    suspend fun probe(ref: AssetRef): Result<AudioFormatInfo, DecoderError>
    fun decodeStream(ref: AssetRef, from: AudioSourceTime): Flow<PcmChunk>
    suspend fun decodeAll(ref: AssetRef): Result<PcmSource, DecoderError>
}

// audio:analysis — §17 / §17.1 / §17.2
data class AnalysisConfig(/* MEMBERSHIP IS GAP P-3 — NOT DEFINED HERE */) {
    fun configHash(): AnalysisConfigHash
}
interface AudioAnalyzer {
    fun analyze(source: PcmSource, config: AnalysisConfig): Flow<AnalysisProgress>  // stage-ordered §17.1
}

// audio:cache — §18 / §18.1
interface AudioAnalysisCache {
    fun read(feature: FeatureId, at: AudioSourceTime): FeatureSample   // interpolated §17.2; never blocks
    fun status(feature: FeatureId, at: AudioSourceTime): SampleStatus  // Complete | Pending(nearestUsed)
    fun band(range: FrequencyRange, at: AudioSourceTime): Float        // §20 custom bands
    val key: AnalysisCacheKey                                          // (assetHash, analysisConfigHash)
}

// audio:playback — §14.1 master clock
interface PlaybackClock { val currentTime: StateFlow<TimelineTime>; val isMaster: Boolean }
interface AudioPlayback {
    fun play(); fun pause(); fun seek(t: TimelineTime)
    fun setLoop(range: ClosedRange<TimelineTime>?)                     // §16 preview loop
    fun setGain(g: Float); fun setMuted(m: Boolean); fun setFades(inMs: Long, outMs: Long)
    val underrunCount: StateFlow<Long>                                 // §14.1 guarantee, asserted in tests
}

// core:project — §85 / §102.1
sealed interface Command { fun apply(s: ProjectState): ProjectState; fun invert(s: ProjectState): Command }
interface ProjectStateHolder { val state: StateFlow<ProjectState>; fun dispatch(c: Command) }
```

**Two contracts deliberately NOT created in Phase 1:** `ParameterResolver` (§22.1) and the Resolved Modulation Cache (§27.2). Both are Phase 3 (§131). Phase 1 supplies only what they will consume: distinct time types, `TrimMapping`, and a deterministic, interpolating analysis cache.

---

## 6. Data Flow

```
 SAF pick
   │
   ▼
[core:assets] AssetRegistry.import
   │  takes persistable permission (§82.1) · streams file → SHA-256 → assetHash · probes format
   ├──────────────────────────────▶ Tier 1: original SAF asset (source of truth, re-read only for full decode)
   │
   ▼
[audio:decoder] decodeAll / decodeStream  ── headless, independent of playback (§15)
   │
   ├──▶ [core:assets] DerivedPreviewCache          Tier 2: waveform peak pyramid (§82.1)
   │        └──▶ Trim UI reads ONLY this, never re-opens the SAF URI per redraw (§16, binding)
   │
   ├──▶ [audio:playback] streaming PCM → AudioTrack/ExoPlayer → PlaybackClock (master, §14.1)
   │
   ▼
[audio:analysis] progressive, priority-ordered (§17.1)
   stage 1 waveform peaks → 2 RMS/peak envelope → 3 FFT + bands → 4 onset/beat/tempo → 5 centroid/flux/rolloff/flatness/chroma
   all features resampled onto the common 100 Hz timeline (§17.2), indexed in AudioSourceTime (§9.1)
   │
   ▼
[audio:cache] AudioAnalysisCache               Tier 3: content-addressed (assetHash, analysisConfigHash) (§18.1)
   immutable-once-written · append-only · lock-free reads · never blocks · "analysis pending" status
   │
   ▼
 Consumers
   Phase 1: debug harness (progress, waveform, feature meters), tests
   Phase 3: ParameterResolver + Resolved Modulation Cache (§22.1, §27.2) — reads by AudioSourceTime,
            translating from TimelineTime via TrimMapping exactly once (§14.1)
```

**Invariants this flow enforces:** analysis is indexed in absolute audio-source time, so trim edits never invalidate it (§9.1); the trim UI never touches tier 1 (§16); nothing downstream of the cache ever recomputes a feature (§17 "analyze once, reuse everywhere").

---

## 7. Threading / Coroutine Model (§101, §101.1)

| Context | Carries | Rules |
|---|---|---|
| Main/UI | Debug harness, state observation | Zero DSP, zero disk, zero decode |
| Playback (framework-owned) | AudioTrack/ExoPlayer callback thread | Never blocked by analysis or disk; never allocates in the callback |
| Decode | Bounded pool (Media3 codec threads + IO dispatcher) | Cancellable; independent per §15 |
| Analysis | Bounded `Dispatchers.Default` pool, structured concurrency under an `AnalysisScope` | Priority-ordered §17.1; fully cancellable on asset change; parallel across chunks, ordered on commit |
| Cache disk IO | IO dispatcher | Write-once, atomic rename; never on Main |

**Binding rules:**
- Cross-thread state passes only through the §101.1 mechanisms: the lock-free `AudioAnalysisCache` and immutable state snapshots via `StateFlow`. No shared mutable maps, no cross-thread direct object mutation.
- `AudioAnalysisCache` readers are **wait-free**: reads never take a lock and never block on the writer (§18.1). Writers append and publish via an atomic release of a completion marker.
- Ownership per §157.1/§6.1: `AudioEngine`, `AssetRegistry`, `AudioAnalysisCache` are Hilt `@Singleton`s — explicit, injected, testable — never bare `object`s.
- Cancellation is a first-class requirement: importing a second asset must cancel in-flight analysis deterministically, with no leaked coroutines and no partial writes surviving (asserted by test).

---

## 8. Clock Authority & Time-Domain Handling

- **Preview clock is master (§14.1).** `PlaybackClock` publishes `TimelineTime` derived from the audio playback position. Nothing in Phase 1 drives time from a frame counter. Export's virtual frame clock is Phase 8 and is not stubbed here.
- **Two time domains, two types.** `TimelineTime` and `AudioSourceTime` are distinct value classes. They cannot be added, compared, or passed interchangeably. This makes §9.1's epoch rule and §14.1's "translated exactly once" rule *compile-time* properties rather than review-time hopes.
- **The epoch is absolute audio-source time (§9.1)** — `t=0` of the raw asset, never the trimmed timeline. Every analysis frame index is an `AudioSourceTime`. Consequence, already ratified in §9.1: dragging a trim handle changes only the query offset, never any cached value.
- **Translation site.** §14.1 states the `audioT = T + trimIn` translation happens "exactly once, inside `ParameterResolver`, and nowhere else." `ParameterResolver` does not exist until Phase 3, and Phase 1 has no modulation consumers, so no pipeline translation occurs in Phase 1. The function lives in `core:time`; Phase 3's `ParameterResolver` becomes its sole pipeline caller.
  > **Clarification needed — see P-5 in §17.** The Trim editor inherently works in audio-source time (it displays the whole asset), so it necessarily converts between domains for display. Read literally, §14.1's "nowhere else" would forbid this. The intended scope is plainly the render/parameter-resolution path, not editor display, but the text does not say so.

---

## 9. Cache Identity & Invalidation Implementation Plan

**Three independent caches, three independent invalidation rules (§82.1) — never conflated.**

| Tier | Key | Location | Invalidation |
|---|---|---|---|
| 1 — original asset | `assetId` → SAF URI | User storage | Never cached; validated lazily; failure → Missing-Asset outcome |
| 2 — derived preview (waveform peaks) | `(assetHash, peakFormatVersion)` | App cache dir | New asset or new peak format → new key |
| 3 — analysis | `(assetHash, analysisConfigHash)` (§18.1) | App cache dir | New asset **or** changed analysis settings → new key |

**`assetHash` (§27.2, binding):** obtained by resolving `audio.assetRef` through the Asset Registry to that asset's `hash` field — never the raw `assetRef`. Implementation: SHA-256 over the asset's byte stream at import, stored on the `AssetRef`.

**`analysisConfigHash`: membership is gap P-3 and is NOT decided in this plan.**

**On-disk layout:**
```
<cacheDir>/analysis/<assetHash>/<analysisConfigHash>.acache
  header: magic · formatVersion · assetHash · analysisConfigHash · sampleRate · channels
        · frameRate(100Hz) · featureTable(offset,type,precision per feature) · completionMap
  body:   per-feature contiguous arrays on the common 100 Hz timeline (§17.2)
```
- **Immutability (§18.1):** files are written to a temp path and atomically renamed. An existing file is never mutated. A crash mid-analysis leaves no half-file visible to readers.
- **`formatVersion`** is distinct from `analysisConfigHash`: config identifies *what was computed*, format identifies *how it is laid out*. A reader rejects unknown format versions and regenerates. (Recommended; see P-6.)
- **Regeneration is always safe** — the cache is disposable (§18.1). Missing, corrupt, or version-rejected files trigger silent re-analysis via the §17.1 progressive path.
- **Memory pressure (§98.1):** analysis pages are evicted first; `ProjectState` and undo history are never released. Eviction must not break wait-free readers — implemented by page-level atomic swap to a "not resident, re-read from disk" state, never by freeing memory a reader may hold.
- **Disk budget/eviction policy:** not specified by §18.1 — see P-6.

---

## 10. Error Handling & Diagnostics

**Every failure maps to a §97 category — no generic messages, ever.** Phase-1-reachable subset: `IMPORT_ERROR`, `DECODER_ERROR`, `AUDIO_ANALYSIS_ERROR`, `UNSUPPORTED_FORMAT`, `PERMISSION_ERROR`, `OUT_OF_MEMORY`, `PROJECT_CORRUPTION` (cache-header corruption path).

- **Missing asset (§82.1):** lazy validation on first use; SAF permission loss or deleted file yields a recoverable `MissingRelinkRequired` outcome, never an exception escaping to a crash. The relink *UI* is later; the *mechanism* and its test are Phase 1.
- **Unsupported/corrupt media:** surfaced as categorized errors carrying the container/codec detail, not "something went wrong."
- **Analysis failure:** a failing stage degrades that feature only; earlier completed stages remain valid and readable (§17.1's staged model makes this natural).
- **Logging (§99):** structured, subsystem-tagged (`Audio`, `Analyzer`, `Assets`, `Project`), rate-limited in release builds.
- **Timing spans (§100.1):** every analysis stage and decode pass is instrumented with named spans from day one — §100.1 requires per-subsystem attribution so a future regression is diagnosed against the right subsystem.
- **Diagnostic report (§98):** exportable, containing device/OS/codec info, asset properties, analysis stage timings, cache hit/miss, memory-trim events.
- **"Analysis pending" (§18.1):** exposed as an explicit read status, so Phase 2/3's renderer can flag frames rather than stall. Directly testable in Phase 1 without a renderer.

---

## 11. Test Strategy (§118, §119, §77.1)

**Fixtures (§119) — synthesized, deterministic, license-safe (§115):** silence, sine (multiple frequencies), bass sweep, white noise, impulse, synthetic percussive/"drums" pattern, stereo (differing L/R), clipping, very quiet signal. Generated by a checked-in generator with fixed parameters — no third-party audio is committed.

**Tiers, per §77.1:**
- **CI tier** — deterministic, pinned environment, release-blocking. All analysis golden vectors live here.
- **Device-matrix tier** — Nothing Phone (1) and Xiaomi 14 Pro (§6.1): crash-freedom, playback integrity, performance. Never held to CI's exact-value thresholds.

**Suites:**
1. **DSP unit tests against analytic truth** — sine → known RMS/peak/centroid; silence → zeros with no NaN/Inf; impulse → flat magnitude spectrum; clipping → peak at full scale; very quiet → no denormal collapse.
2. **Decoder tests** — each §15 format decodes to expected duration/rate/channels; truncated and corrupt files produce categorized errors, not crashes.
3. **Golden analysis vectors** — feature arrays for each fixture, byte-compared. **Generated only from uncompressed (WAV/PCM) fixtures** — see the determinism note in §12.
4. **Cache tests** — write/read round-trip; cache hit equals cache miss exactly; unknown `formatVersion` rejected and regenerated; atomic-rename crash-safety (no half-file readable); interrupted analysis leaves no visible artifact.
5. **Asset tests** — import, hash stability, SAF permission persisted, revoked-permission → relink outcome, re-import of identical content hits the existing cache.
6. **Concurrency tests** — readers never block behind a writer; reads during active analysis return `Pending` with nearest-sample fallback, never stale-wrong values; rapid re-import cancels cleanly with no leaked coroutines.
7. **Memory-pressure tests** — `onTrimMemory` releases analysis pages, preserves `ProjectState`/undo, and readers remain correct across an eviction (§98.1).
8. **Module boundary test** — CI fails on any import violating §116.1.

---

## 12. Determinism Tests (§9.1)

The determinism contract Phase 3 inherits is only as strong as what Phase 1 proves:

| Test | Asserts |
|---|---|
| **Repeat-analysis identity** | Analyzing the same PCM twice with the same config produces bit-identical cache bytes |
| **Order independence** | Reading timestamps in random order equals reading them ascending — the §9.1 "playback order cannot matter" property |
| **Cache-path equivalence** | Values from a fresh analysis equal values read back from disk, exactly |
| **Trim independence** | Changing `trimIn`/`trimOut` alters no cached value and no cache key — the §9.1 ratified consequence |
| **Interpolation determinism** | Arbitrary-timestamp reads follow §17.2's linear rule exactly and are repeatable |
| **Epoch stability** | Feature values at a given `AudioSourceTime` are identical regardless of where playback started |
| **Time-type safety** | Compile-time: `TimelineTime` and `AudioSourceTime` cannot be conflated (negative-compilation test) |

> **Critical design consequence — hardware decoders are not bit-exact.** MediaCodec MP3/AAC output can differ across devices and codec vendors. Therefore golden vectors are cut **only from uncompressed fixtures**; compressed-format tests assert tolerance and structural correctness, never bit-equality. This mirrors §77.1's reason for pinning the CI environment, and prevents a permanently-flaky suite — the exact failure mode §123 warns about.

---

## 13. Performance / Budget Tests (§87, §100.1, §121)

| Budget | Source | Test |
|---|---|---|
| Trim screen usable ≈1 s after import | §17.1 (explicit) | Time from import to stage-1+2 availability for a 5-minute track, on both §6.1 devices |
| Playback integrity under load | §14.1 (absolute) | `underrunCount == 0` while scrubbing during active analysis |
| UI thread never blocks | §101 | No main-thread disk/DSP; strict-mode violations fail the test |
| Analysis throughput | **no absolute target specified** | Establish a per-track-minute baseline in Phase 1; enforce no-regression thereafter (§121) |
| Analysis memory ceiling | **no absolute target specified** | Establish baseline; assert eviction under `onTrimMemory` |
| Cache size per track-minute | **depends on P-4** | Measured and reported; a target cannot be set until P-4 is decided |

Two budgets have no specified number. Rather than invent them, Phase 1 establishes measured baselines and a regression gate — the same treatment §100.1/U-11 already applies to CPU-split numbers. The 16.67 ms frame budget (§87) has no Phase 1 consumer: there is no renderer yet.

---

## 14. What Phase 1 Explicitly Must NOT Implement

- **No renderer, compositor, RenderGraph, GL/EGL, or preview surface** — Phase 2 (§130).
- **No Reactive Engine**: no `ParameterResolver` (§22.1), no Resolved Modulation Cache (§27.2), no envelope followers, no peak/gravity, no `ReactiveMapping` *evaluation*. Schema only. Phase 3 (§131).
- **No visualizers or effects** (§132/§133) — and when they come, they come as first-party plugins (§30.1/§37.1), not core modules.
- **No plugin platform, no Tier-1 declarative runtime, no Tier-2/WASM, no `.arp` handling** — Phase 7 (§135), and Tier-2 additionally hard-gated by U-19.
- **No export, no encoder, no muxer, no Foreground Service, no `AudioExport`** — Phase 8 (§136). Media3 is used in Phase 1 for *decode only*; it is never a composition or rendering authority (§7).
- **No project persistence, serialization, autosave, recovery, or migration** — Phase 9 (§137). `ProjectState` is in-memory only. Only *caches* touch disk, and they are disposable by design.
- **No second source of truth for project data** (§102.1) — the analysis cache is derived and regenerable, never authoritative.
- **No product UI** (§103) — a debug-only harness Activity, clearly marked, excluded from release builds.
- **No timeline/keyframes** (§134) beyond the trim range the audio itself requires.
- **No resolution of Appendix B items** other than U-1/U-2, already closed. U-5's thresholds stay empirical; U-9/U-11 stay provisional.

---

## 15. Definition of Done for Phase 1 (§146 applied)

| §146 criterion | Phase 1 meaning |
|---|---|
| Implementation | All §3 modules exist, wired via Hilt, meeting §4 responsibilities |
| UI | Debug harness only — import, waveform, trim, playback, analysis progress |
| Serialization | Analysis + peak cache formats, versioned, round-trip tested (project serialization is Phase 9) |
| Tests | All §11 suites green in CI |
| Regression | §12 determinism suite and §116.1 boundary check are release-blocking |
| Performance | §13 baselines recorded in `PERFORMANCE.md`; §17.1's ~1 s trim figure met on both §6.1 devices |
| Error handling | Every failure path maps to a §97 category; missing-asset and corrupt-media paths tested |
| Documentation | `ARCHITECTURE.md`, `PROJECT_STATUS.md`, `TEST_PLAN.md`, `PERFORMANCE.md`, `KNOWN_ISSUES.md`, **ADR-002**, **ADR-012** |
| Migration consideration | Cache `formatVersion` present, unknown-version rejection tested |
| Export verification | N/A for Phase 1 — recorded explicitly, with the decoder's headless independence (§15) noted as the Phase 8 enabler |

**Additional §129 exit criteria (mandated by the specification, beyond the user's Phase 1 bullet list):**
- `core/model` established;
- module dependency-boundary CI check operational **before other Phase 1 code lands** (§116.1);
- Hilt DI set up (§6.1);
- **Coordinate (§13.1), Color (§90.1), and Clock (§14.1) decisions implemented as testable primitives** — pure functions with unit tests, no renderer required. §129 requires these in Phase 1 even though two of the three are renderer-facing; they are listed here because the user's Phase 1 summary did not mention them and they are not optional.

---

## 16. Risks & Regression Points

| Risk | Severity | Mitigation |
|---|---|---|
| **P-1…P-4 decided after coding starts** | **Highest** | They define the on-disk format and analyzer contract. Ratify before any `audio:*` code — see §17 |
| Hardware decoder non-determinism across devices | High | Golden vectors from PCM fixtures only; tolerance-based tests for compressed formats (§12) |
| FFT storage size on mobile (P-4) | High | Blocked on P-4; cache size measured and reported from the first build |
| Playback dropouts under analysis load | High | Bounded analysis pool, priority separation, `underrunCount == 0` as a release-blocking assertion |
| Memory eviction racing wait-free readers | High | Page-level atomic swap to non-resident, never free-under-reader; explicit concurrency test |
| SAF permission loss between sessions | Medium | Lazy validation + relink outcome (§82.1); tested by revoking permission in-test |
| Coroutine/cancellation leaks on rapid re-import | Medium | Structured concurrency under a single `AnalysisScope`; leak assertions in tests |
| Time-domain conflation | Medium | Distinct value classes; negative-compilation test |
| Cache format churn | Medium | `formatVersion` + reject-and-regenerate from day one |
| **Over-building toward Phase 3** | Medium | Hard rule: no `reactive/` module, no evaluation logic. Reviewed at every PR |
| Analysis cost on the API-35 minimum device | Medium | Progressive staging (§17.1) means usable-before-complete; baselines measured on Nothing Phone (1) |

---

## 17. Unspecified Decisions — RESOLVED (and the one residual item)

All six were ratified by the Project Owner and incorporated as **normative specification text**, not as Appendix B register entries — they are resolved, so they belong in the body rather than in a register of unresolved decisions. Dispositions:

| ID | Disposition | Ratified as |
|---|---|---|
| **P-1** Channel handling | **RESOLVED** — canonical **mono** analysis signal; stereo `0.5·L + 0.5·R`; source channel count/layout preserved; playback and export use the original source, never the mono signal | §17.3 |
| **P-2** Canonical sample rate | **RESOLVED** — **48,000 Hz**; deterministic software resampler, never platform/hardware resampling; window duration fixed at 2048/48000 s | §17.3 |
| **P-3** `analysisConfigHash` membership | **RESOLVED** — normative inclusion list (11 items) **and** normative exclusion list, governed by an explicit test: *if it changes a stored number it is in the hash; if it only changes consumption it is not* | §18.2 |
| **P-4** FFT retention | **RESOLVED** — 1024 magnitude bins (bins 1…1024 of the 2048-point FFT, DC discarded), 100 Hz, FP16; custom bands derived from the retained spectrum without re-FFT or re-decode; ≈12.4 MB per track-minute | §17.4 |
| **P-5** §14.1 translation scope | **RESOLVED** — "nowhere else" governs the render/parameter-resolution pipeline; Trim Editor display conversion is expected and permitted | §14.1 |
| **P-6** Cache format & disk policy | **RESOLVED** — `formatVersion` independent of `analysisConfigHash`; unknown version → reject + regenerate; **1 GB** default budget (configurable 256 MB – 8 GB); deterministic LRU, whole-entry eviction; open project's entry protected | §18.3 |

**U-21 — also RESOLVED (§17.5).** Fixing the canonical rate at 48 kHz made an arithmetic tension in §17.2 explicit, which has since been ratified as three distinct quantities that must never be conflated:

| Quantity | Canonical value |
|---|---|
| FFT window size | **2048 samples** (≈42.667 ms; 23.4375 Hz bins) |
| Analysis hop | **480 samples** (10 ms exactly) |
| Native analysis frame rate | **100 Hz** (`48000/480`) |

Overlap (76.5625%) is derived, never an input; the inherited "50% overlap" figure is superseded. Because native rate equals storage rate, spectral frames are written one-for-one as **measured** frames and are never interpolated or upsampled. Frame `n` covers samples `[n·480, n·480 + 2048)`, anchored at its window **start**, stored at `n · 10 ms` from the §9.1 epoch; `N = ceil(totalSamples / 480)` with zero-padded tail windows.

**No Phase 1 blockers remain. All ten build steps are cleared.**

| ID | Unspecified decision | Why Phase 1 cannot proceed without it | Spec basis |
|---|---|---|---|
| **P-1** | **Channel handling for analysis** — mono downmix, per-channel, or mid/side? §17's features are all scalar, yet §119 mandates a *stereo* fixture. If per-channel, every feature's cache layout and the analyzer contract change. | Determines cache layout, feature identity, and whether `channels` belongs in `analysisConfigHash` | §17, §19, §119 |
| **P-2** | **Canonical analysis sample rate / resampling policy** — §17.2 fixes the FFT window at *2048 samples* "at 44.1/48 kHz", but sources may be 22.05, 96, 192 kHz. A fixed sample-count window means window *duration* (and therefore every spectral feature) varies with source rate. Resample to a canonical rate, or accept rate-dependent features? | Determines cross-source comparability, golden-vector validity, and whether `sampleRate` is part of the cache identity | §17.2, §15 |
| **P-3** | **`analysisConfigHash` membership** — §18 requires "analysis settings" to invalidate the cache but never enumerates them. §106 lists six master controls (Sensitivity, Normalization, FFT size, Smoothing, Beat detection, Analysis quality); some are analysis-affecting, others are downstream reactive parameters that must *not* invalidate analysis. | **The single most consequential Phase 1 decision.** Too broad → spurious full re-analysis on a slider nudge. Too narrow → stale caches, i.e. the §27.2 correctness bug the audit just fixed | §18, §18.1, §27.2, §106 |
| **P-4** | **FFT spectrum retention: bin count and precision** — §18 requires storing "FFT" and §17 requires both magnitude and log spectrum at §17.2's 100 Hz. Retaining 1024 float32 bins at 100 Hz is ≈410 KB/s ≈ **123 MB for a 5-minute track**, which cannot be reconciled with "efficient binary storage" (§18) on the §6.1 minimum device. Needs: retained bin count, quantization (float32/float16/dB-uint8), and confirmation that §20 custom bands are derived from the retained spectrum at read time (per §22.2) rather than requiring re-analysis | Determines cache size, memory profile, custom-band feasibility, and golden-vector precision | §17, §17.2, §18, §20, §22.2, §98.1 |
| **P-5** *(clarification, low risk)* | **Scope of §14.1's "translated exactly once… and nowhere else"** — the Trim editor inherently converts between timeline and audio-source time for display. The intended scope is evidently the render/parameter-resolution path, but the text is absolute | Prevents a future reviewer reading the Trim editor as a spec violation | §14.1, §16 |
| **P-6** *(clarification, low risk)* | **Cache `formatVersion` and disk-eviction policy** — §18.1 mandates content-addressing and regenerability but specifies neither a layout-version field nor a disk budget/eviction rule for the app-managed cache directory. §98.1 covers *memory* pressure only | Prevents unbounded cache growth on device and enables safe format evolution | §18.1, §98.1 |

**Recommendation:** ratify P-1 … P-4 (and ideally P-5, P-6) before implementation begins. I have deliberately not chosen defaults for any of them.

---

## 18. Carried-Forward Test Obligations

Obligations recorded during implementation that are **not** yet discharged. Each names the
file that must carry it and the trigger that makes it due. They are recorded here so they
survive a context boundary, and mirrored as a comment in the file itself so they survive a
reader who never opens this document.

| # | Obligation | File | Due |
|---|---|---|---|
| T-1 | **§18.2 exclusion-half membership test.** `AnalysisConfigMembershipTest` asserts the inclusion list directly but leaves the exclusion half implicit — it holds only because no excluded concept has yet been added as a field of `AnalysisConfig`. Add a test that enumerates §18.2's named exclusions (reactive mapping parameters, master sensitivity, master smoothing, custom band definitions, trim points, keyframes) and asserts none appears among `AnalysisConfig`'s declared properties, failing with a message that names the §18.2 exclusion rule rather than the inclusion list. | `core/model/src/test/kotlin/com/arvs/core/model/AnalysisConfigMembershipTest.kt` | Before that test surface is next modified (Project Owner, Step 3) |

---

## Sequenced Build Order (once approved and P-1…P-4 are closed)

1. Gradle skeleton, Hilt, `minSdk 35`/`compileSdk 36`/`targetSdk 36`, **§116.1 boundary check first**
2. `core:model`, `core:time` (+ §13.1/§90.1 primitives and their tests)
3. `core:diagnostics`
4. `core:assets` (import, hashing, SAF, missing-asset path)
5. `audio:decoder` + format tests; `testing:audio` fixtures
6. Derived preview cache (waveform peaks) → debug harness shows a waveform
7. `audio:playback` + master clock + underrun assertions
8. `audio:analysis` stages 1–2 → §17.1's ~1 s trim target met
9. `audio:cache` (format, atomicity, wait-free reads) + determinism suite
10. `audio:analysis` stages 3–5, `audio:beat`
11. `core:project` (ProjectState + Command model, §85.1)
12. Performance baselines, diagnostics report, documentation, ADR-002/ADR-012

---

*No application code, Gradle configuration, or Android project scaffolding was created in the production of this plan. Awaiting approval.*
