# MASTER SPECIFICATION v3.0 — ARCHITECTURE RATIFIED

**Document Status:** Production Architecture Specification, Architecture-Ratified Revision.
**Supersedes:** MASTER_SPECIFICATION v2.0, as amended by ARCHITECTURE_REVIEW.md ("the Review").
**Intended Development Agent:** Claude Code / equivalent autonomous coding agent.
**Target Platform:** Android.
**Primary Use Case:** Professional personal production of audio-reactive music videos, visualizers, promotional clips, YouTube videos, TikTok/Reels/Shorts content and similar audiovisual material.

## 0.0 Relationship to Prior Documents and Traceability Convention

This document is **self-contained**: every requirement needed for implementation is stated here in full. It is not necessary to read v2.0 or the Review to implement against this document.

Every requirement carried forward unchanged from v2.0 appears here with the same substance as v2.0 (reworded only for clarity/consistency, never weakened, omitted, or reinterpreted).

Every requirement changed, resolved, clarified, or newly introduced because of the Review is marked inline with a traceability tag:

> **[RATIFIED — Ref AR-#.#]** — text of the resolution, immediately followed by the binding requirement.

`AR-#.#` refers to the finding number in ARCHITECTURE_REVIEW.md §1–§19 (e.g. `AR-1.1` = Review section 1, finding 1). A companion **Traceability Appendix** (Appendix A) lists every `AR-#.#` finding marked `Spec change: Y` and the exact section(s) of this document where it was incorporated, so completeness of incorporation is independently checkable.

Section numbers below **match v2.0's numbering exactly** for every section that existed in v2.0, so nothing is renumbered away. New material introduced by ratification is inserted as a decimal sub-section immediately after its most relevant parent (e.g. `§9.1` is new material inserted after original §9, before original §10). This guarantees: (a) every v2.0 requirement remains locatable at its original number, (b) every new/amended requirement is unambiguously new and traceable, (c) reading order stays logical.

Where this document's text differs from v2.0's text without a `[RATIFIED]` tag, it is a non-substantive clarification (wording only) — no requirement was weakened, dropped, or reinterpreted without a tag.

---

## PART I — PRINCIPLES, VISION, AND SCOPE

### 0. ABSOLUTE DEVELOPMENT PRINCIPLE

*(Unchanged from v2.0.)*

This project is NOT:
- a simple music visualizer;
- a collection of hard-coded visual effects;
- a prototype;
- a demo application;
- a conventional mobile video editor.

This project IS: a modular, extensible, GPU-accelerated audio-reactive compositing engine with a professional Android editing interface.

The application must be designed as a **PLATFORM**. The built-in effects are merely built-in extensions. Future effects, visualizers, generators, analyzers, importers, exporters and custom layer types must be installable after the application itself has been built.

The architecture must therefore support: CORE ENGINE + EXTENSION API + PLUGIN RUNTIME + PLUGIN VALIDATION + PLUGIN UI GENERATION + PLUGIN TESTING + PLUGIN VERSIONING.

> **[RATIFIED — Ref AR-18.1, AR-18.2, AR-18.3]** This principle is now load-bearing, not aspirational: the built-in visualizers (§30–§33), effects (§37), templates (§110), and any bespoke demonstration layer type are **required to ship as first-party plugins built on the exact same public Plugin API** any third-party plugin uses (see §12.1, §30.1, §37.1, §110.1). The only layer types permitted to be genuinely hard-coded into the core engine are the minimal set in §12.1 (Image, Video, Text, Shape, Gradient, SolidColor, Group) because they require no novel render algorithm — everything else is a plugin, including everything shipped on day one.

### 1. PRODUCT VISION

*(Unchanged from v2.0.)* The user should be able to:
1. Create a project.
2. Import music.
3. Trim the music visually.
4. Add one or more images.
5. Add additional image/video layers.
6. Add text.
7. Add audio visualizers.
8. Add particles and procedural generators.
9. Apply effects.
10. Assign different audio-reactive inputs to different layers.
11. Assign multiple audio-reactive inputs to a single parameter.
12. Combine audio reactivity with keyframes.
13. Save the project.
14. Reopen it later with no loss of state.
15. Export in high quality.
16. Install additional plugins whenever functionality is missing.
17. Have installed plugins appear automatically in the appropriate menus.
18. Remove/disable/update plugins without rebuilding the application.

The user must NOT have to wait for a new APK merely because a new effect is required.

> **[RATIFIED — Ref AR-19.1]** **v1 scope is explicitly a single audio track per project.** Item 2 ("Import music") and all of §15–§21 assume exactly one audio asset per project. Multi-track audio mixing, voiceover-over-music, and ducking/sidechain are **explicitly out of scope for v1** and are not implied anywhere in this document unless a section says otherwise. This resolves an ambiguity in v2.0 §1, which gestured at "promotional clips" (implying possible voiceover use) without stating a track-count scope. See Appendix B (Unresolved Decisions) item U-12 for whether this should be revisited.

### 2. CORE DIFFERENTIATOR

*(Unchanged from v2.0.)*

The central architectural abstraction is:

```
AUDIO FEATURE → MODULATION → PARAMETER → LAYER
```

NOT: `AUDIO → GLOBAL VISUALIZER`.

Example:
- Background Layer: Bass → Scale
- Artwork Layer: Mid → Rotation
- Logo Layer: Beat → Scale + Glow
- Spectrum Layer: Full FFT → Height
- Particle Layer: High Frequency → Emission
- Glitch Effect: Transient → Intensity

All of these must operate independently. (Enforced by §78–§80's permanent isolation tests; see §15.2 for their ratified status as CI-blocking tests.)

### 3. COMPETITIVE REFERENCE

*(Unchanged from v2.0.)* Competitor products are reference material for capabilities and UX only. Do not copy proprietary source code, shaders, graphics, presets, assets or other protected implementation.

**3.1 Vibely** demonstrates: audio import/extraction/trimming; 12 reactive visualizers (spectrum, waveform, oscilloscope, synthwave, starfield, plasma, kaleidoscope, vinyl); text; image layers; gradients; reactive effects (glitch, fisheye, blur, color distortion); templates; projects; undo/redo; layer duplication; rotation; mirroring; perspective; project autosave; multiple aspect ratios; 4K export; 60 FPS export; particle-style Dust and Vortex layers. Vibely is an important UX benchmark. This project must go beyond its composition model by treating every layer and every effect as an independently addressable reactive entity. Reference: `https://apps.apple.com/app/id1528056717`.

### 4. OTHER REFERENCE SYSTEMS

*(Unchanged from v2.0.)* Use the conceptual strengths of: **Avee** (layered visual elements, configurable visualizers, frequency-driven parameters, procedural visual components); **STAELLA** (composition of visual elements, shapes, visualizers, text, layered artistic construction); **projectM/MilkDrop** (FFT-driven graphics, procedural shaders, parameter modulation, preset architecture); **BiepBop-style systems** (per-band mapping, layer-specific modulation, stackable effects, masks, blend modes, keyframes). These references inform architecture but are NOT implementation dependencies.

---

## PART II — SYSTEM ARCHITECTURE

### 5. APPLICATION ARCHITECTURE

*(Unchanged in structure from v2.0; module boundary enforcement added per §5.1 of the Review — see §116.1.)*

```
CORE
├── Project Engine
├── Timeline Engine
├── Asset Engine
├── Audio Engine
├── Audio Analysis Engine
├── Reactive Engine
├── Layer Engine
├── Renderer
├── Compositor
├── Effect Engine
├── Export Engine
├── Diagnostics
└── Extension Platform

EXTENSIONS
├── Effect Plugins
├── Visualizer Plugins
├── Generator Plugins
├── Analyzer Plugins
├── Layer Plugins
├── Importer Plugins
└── Exporter Plugins
```

### 6. TECHNOLOGY BASELINE

Primary language: Kotlin. UI: Jetpack Compose. Rendering abstraction: `RendererBackend`. Initial production backend: OpenGL ES 3.x where required for compatibility. Business logic must never directly depend on a specific graphics backend. Dependency injection: **Hilt** (resolves Appendix B U-1). Android build targets: **minSdk 35, compileSdk 36, targetSdk 36** (resolves Appendix B U-2; see §6.1 for the full ratification and rationale).

> **[RATIFIED — Ref AR-17.1]** The "architecture must permit a future VulkanBackend" requirement is **scoped down to a boundary-discipline requirement, not an active engineering deliverable for v1**. Concretely: `renderer/core` business logic (compositor, layer traversal, parameter resolution) must call `RendererBackend` only — never `GLES20.*`/`GLES30.*` directly — enforced by the module dependency graph (§116.1). No Vulkan-specific concepts (explicit command buffers, memory barriers, descriptor sets, render passes) are designed or built into the v1 `RendererBackend` interface. This "leaves the door open" without pre-paying for a second backend that has not yet been validated by real implementation. Building an actual `VulkanBackend` is not part of any phase in §128–§148 and requires a separate future ADR (ADR-011 remains reserved for this).

> **[NOTED — not a Review finding; forward-reference to Appendix B]** WASM runtime library selection is **not resolved by this document** — see Appendix B, item U-3. Dependency injection framework and minimum supported Android API level are now resolved — see §6.1.

#### 6.1 Ratified Android Build Targets (Resolves Appendix B U-1, U-2)

> **[RESOLVED — Appendix B U-1]** Dependency injection framework: **Hilt**. Every platform-singleton component required by §157.1 (e.g. `GLContextHolder`, one `AudioEngine` instance) is injected via Hilt — never referenced as a bare `object`/static field.
>
> **[RESOLVED — Appendix B U-2]** Minimum supported Android API level: **API 35 (Android 15)**.
>
> **Ratified Android build targets:**
> - `minSdk = 35` (Android 15)
> - `compileSdk = 36`
> - `targetSdk = 36` (Android 16)
>
> **Rationale:** Nothing Phone (1), running Android 15 / API 35, is this project's minimum real-device test platform; Xiaomi 14 Pro, running Android 16 / API 36, is the primary higher-tier real-device test platform. Android 14 and lower are intentionally not supported in v1. API 36 is the development/target platform (`compileSdk`/`targetSdk`); API 35 remains the minimum runtime platform (`minSdk`).
>
> This ratification does **not** resolve Appendix B item U-17 (the full device-matrix hardware list for §122's Device Matrix Smoke Tests), which remains open — Nothing Phone (1) and Xiaomi 14 Pro are named here only as the rationale anchoring the minSdk/compileSdk/targetSdk decision, not as a complete device-matrix resolution.

### 7. MEDIA INFRASTRUCTURE

> **[RATIFIED — Ref AR-1.1]** This section replaces v2.0 §7 in full, resolving the contradiction between "the custom compositor is authoritative" and "Media3 Transformer may be used for composition." **Media3 (including Transformer) is confined strictly to decode, encode, mux, and container-format concerns. Media3 never performs visual compositing, never sees layers, effects, masks, blend modes, or reactive mappings, and is never the render path for any user-visible frame content.**
>
> Concretely:
> - Media3 `MediaCodec`-backed decoders may be used to decode source audio/video assets into raw buffers/textures for the Asset Engine to hand to the Renderer.
> - Media3 Transformer/muxer infrastructure may be used for the **encode + mux** stage of Export (§92), consuming already-fully-composited frames produced by the custom RenderGraph (§8) — never Media3's own `Composition`/`EditedMediaItem`/`GlEffect` compositing model, and never Media3's built-in "standard effects."
> - The one authoritative render graph (§8) renders every visual frame, for both preview and export, with no exception.
>
> This is a permanent constraint, not a v1-only decision: it exists because it is the direct enforcement mechanism for §8/§9 (one render graph, deterministic frames) and for §11 (preview/export consistency). Any future contributor who is tempted to route any visual effect through Media3 must be blocked at code review — this is exactly the failure mode this ratification exists to prevent.

### 8. CORE RENDERING PRINCIPLE

The application must have one authoritative render graph.

```
Preview: RenderGraph → GPU → Screen
Export:  RenderGraph → GPU → Encoder → Video
```

Do NOT create separate visual logic for preview and export. This prevents "looks correct in editor" but "looks different after export."

> **[RATIFIED — Ref AR-6.3, AR-14.3]** **EGL/GL context loss and Android configuration changes (rotation, multi-window resize, screen off/on) are normal, expected, recoverable events, not exceptional failure states.** The Renderer's GPU-resident state (textures, FBOs, shader programs, buffers) is **never** the sole record of anything user-visible; `ProjectState` (§10) is always sufficient, on its own, to fully regenerate every GPU resource from scratch. On `onSurfaceCreated`/context recreation, the Renderer performs a full resource rebuild from the current `RenderGraph` derivation of `ProjectState` (§102.1) with zero data loss and no crash. A configuration change that does not actually destroy the EGL context (verified per Android version/device at Phase 1) may skip the full rebuild as an optimization, but must never assume this — the full-rebuild path must always be correct and must be the one exercised by golden/regression tests (§120), with the "no full rebuild needed" path treated purely as a performance optimization on top of it.

### 9. FRAME DETERMINISM

At `timestamp = T`, the scene state must be deterministic. The output should not depend on how the playhead arrived at T, how many times the user pressed play, whether the user scrubbed, whether the project was exported, or whether playback was paused. Temporal effects that intentionally depend on history must explicitly declare temporal state.

#### 9.1 Precise Determinism Definition

> **[RATIFIED — Ref AR-1.3]** This is the binding, precise definition that resolves the apparent tension between "no history" (this section) and stateful features required elsewhere (Attack/Release §28, Peak/Gravity §29, temporal state §41):
>
> **Rendering the frame at time T is a pure function of `(ProjectState, T, AudioAnalysisCache[0..T])`.** It is never a function of "how many times a frame has been requested," "playback order," "wall-clock render invocation count," or any other ambient/ephemeral counter.
>
> Any modulator with temporal behavior (envelope follower, peak/gravity, smoothing) must be implemented as a **deterministic integration from a fixed epoch** (project start, or the modulator's last explicit reset/keyframe boundary — never "whenever this code path first happened to run") over the immutable `AudioAnalysisCache`. Concretely: computing the resolved value of such a modulator at time T must give the bit-identical result whether it is the first frame ever requested for this project or the ten-thousandth, and regardless of what other timestamps were requested before it, in what order.
>
> **The fixed epoch is `t=0` of the raw audio asset (absolute audio-source time), never `t=0` of the trimmed timeline.** All temporal modulator integration (§27.2's Resolved Modulation Cache included) is indexed in absolute audio-source time, exactly like the `AudioAnalysisCache` it reads from (§17.2, §18.1). The Renderer/`ParameterResolver` (§22.1) is solely responsible for translating a requested timeline timestamp `T` into audio-source time via `audioT = T + trimIn` (§14.1) before querying any temporal modulator or the Resolved Modulation Cache — the cache itself never stores or is keyed by timeline time. One direct consequence: **dragging a trim handle (changing `trimIn`/`trimOut`) never invalidates the Resolved Modulation Cache** — it only changes the offset used to query it, which is exactly what makes real-time trim-handle dragging (§16's "immediate visual feedback") affordable. This is the binding resolution of an ambiguity the Review did not fully close (audio-source-time vs. timeline-time indexing was previously unstated); see §27.2 for the corresponding cache-key specification.
>
> This is made computationally tractable (not merely correct-but-slow) by the **Resolved Modulation Cache** (§27.2), which precomputes each modulator's full trajectory once per configuration and serves lookups in O(1). Determinism is a correctness property; the cache is a performance property that must never be allowed to change the result, only the cost of obtaining it.
>
> A "temporal effect" that intentionally depends on cross-session history (none are specified in this document as of v3.0) would require an explicit, separately-declared, versioned state blob in `ProjectState` — never ambient mutable fields on a live object — and is out of scope unless a future revision adds one.

### 10. PROJECT MODEL

Project contains: `ProjectMetadata`, `Canvas`, `Audio`, `Assets`, `Timeline`, `Layers`, `Analyzers`, `Plugins`, `RenderSettings`, `ExportSettings`, `ProjectVersion`.

> **[RATIFIED — Ref AR-10.1]** `ProjectMetadata`/`Assets`/etc. as serialized in the portable project file **exclude** the `AudioAnalysisCache` payload and any derived preview-only data (waveform peaks, video proxies). These are external, content-addressed, regenerable caches (§18.1, AR-14.1 (Review)/§82.1) keyed off asset identity, never part of the project file itself. See §81 for the full file-format implication.

**Core Data Model (binding schema — the schema below is the complete, normative v1 schema; PROJECT_FORMAT.md, §126, restates it for reference but is never a separate or additional source of truth):**

```
Project
 ├─ metadata: {id, name, createdAt, modifiedAt, schemaVersion}
 ├─ canvas: {logicalWidth, logicalHeight, aspectPresetId}      # §13.1 — logical units, not pixels
 ├─ audio: {assetRef, trimIn, trimOut, gain, analysisConfigHash} # single track — §1; analysisConfigHash matches §18.1's cache key exactly (same name, not a separate "Ref")
 ├─ assets: [AssetRef {id, uri, type, hash, persistedPermission}]
 ├─ timeline: {playheadDefault, zoom}
 ├─ layers: [Layer]
 ├─ analyzers: [AnalyzerInstance {pluginId, pluginVersion, config}]
 ├─ plugins: [PluginRef {pluginId, pluginVersion, enabled}]
 ├─ renderSettings: {previewQuality}
 └─ exportSettings: {presetId, resolution, fps, codec}

Layer
 ├─ id, name, type, pluginId?, pluginVersion?
 ├─ enabled, visible, locked, opacity, blendMode
 ├─ transform: {x,y,scaleX,scaleY,rotation,anchorX,anchorY}     # logical units — §13.1
 ├─ crop, mask: Mask?
 ├─ effects: [EffectInstance]
 ├─ parameters: [Parameter]
 ├─ timelineRange: {start, end}                                 # visibility gate only — §14.2
 └─ zIndex

Parameter
 ├─ id, type (Float/Int/Bool/Enum/Color/Angle/Vec2/Vec3/Freq/FreqRange/Curve/
 │            Gradient/String/AssetRef)
 ├─ unit: LogicalUnit | Normalized01 | RawPixel                  # §13.1 — mandatory per parameter
 ├─ baseValue
 ├─ keyframeTrack: Curve?                                        # base-value source — §27.1
 └─ reactiveMappings: [ReactiveMapping]                          # ordered — §27.1

ReactiveMapping
 ├─ source: ReactiveSourceRef
 ├─ sourceRange, targetRange, gain, offset, threshold, deadZone
 ├─ attack, release, smoothing, curve, clamp, invert, falloff
 ├─ beatMultiplier, phaseOffset
 └─ combineOp: Add | Multiply | Max | Min | Override              # §27.1 — mandatory per mapping
```

### 11. LAYER SYSTEM

Every layer is an independent entity. Required fields: `id, name, type, enabled, visible, locked, opacity, blendMode, transform, anchor, crop, mask, effects, reactiveMappings, keyframes, timelineRange, zIndex, pluginId, pluginVersion`.

### 12. BUILT-IN LAYER TYPES

At minimum: `ImageLayer, VideoLayer, TextLayer, SpectrumLayer, WaveformLayer, OscilloscopeLayer, ParticleLayer, ShapeLayer, GradientLayer, SolidColorLayer, ShaderLayer, GroupLayer`. `GroupLayer` must eventually support nested composition.

#### 12.1 True Engine-Native Layer Set vs. First-Party Plugin Layer Types

> **[RATIFIED — Ref AR-18.1, AR-18.3]** This subdivides the §12 list per the ratified core-vs-plugin boundary:
>
> - **Engine-native (hard-coded in core, no plugin mechanism involved):** `ImageLayer, VideoLayer, TextLayer, ShapeLayer, GradientLayer, SolidColorLayer, GroupLayer`. These require no novel render algorithm — they are direct consumers of the compositor's primitive drawing operations — and are the only layer types the core engine implements natively.
> - **First-party plugin layer types (built on the public Plugin API, §43–§59, bundled with the app but mechanistically indistinguishable from a third-party plugin):** `SpectrumLayer, WaveformLayer, OscilloscopeLayer, ParticleLayer, ShaderLayer`, and any bespoke demonstration/novel layer type (e.g. the "3D Sigil" of §144) used to validate the Layer Plugin mechanism.
>
> This split is mandatory, not a suggestion: it is the mechanism by which the Extension Platform's completeness is continuously validated (if a "built-in" visualizer were secretly special-cased in engine code, §144's golden-project test would not actually be testing what it claims to test). Phase ordering (§20.O of the Review, folded into §128–§140 below) requires the first-party plugin layer types to be built as real plugins from the start, exercising the Plugin API before any third-party plugin exists.

### 13. TRANSFORM SYSTEM

Every layer supports: `X, Y, Width, Height, ScaleX, ScaleY, UniformScale, Rotation, AnchorX, AnchorY, Opacity, Crop, FlipHorizontal, FlipVertical, Perspective` (where supported). Gesture controls: drag, pinch, rotate. Additional: snapping, center guides, safe areas, edge guides.

#### 13.1 Coordinate & Unit System

> **[RATIFIED — Ref AR-2.1]** This is a new, mandatory, cross-cutting requirement with no v2.0 precedent — it did not exist in v2.0 and must be treated as a load-bearing addition, not an optional refinement:
>
> - **Canvas space is resolution-independent**, normalized so that canvas height = 1.0 logical unit regardless of the actual preview or export pixel resolution. (Equivalently: a fixed logical resolution, e.g. 1080 logical units tall, may be used as the working reference; the binding requirement is that layer transforms, positions, and sizes are never expressed or stored in raw device/export pixels.)
> - A single, explicit scalar `pixelsPerLogicalUnit = actualRenderResolution / logicalResolution` is threaded through every shader or CPU computation that needs a true pixel radius (blur kernel radius, glow radius, pixelation cell size, scanline spacing, etc.).
> - **Every Parameter (§49) of type Float/Vec2/Vec3 must declare its `unit`** as one of: `LogicalUnit` (canvas-relative, scales with `pixelsPerLogicalUnit`), `Normalized01` (0–1 range, e.g. opacity, mix amounts), or `RawPixel` (rare — only for parameters that are deliberately resolution-dependent by design, and must be explicitly justified in the plugin/effect's documentation).
> - This applies identically to core built-in effects and to plugin-declared parameters (§49) — the Plugin Parameter API's type table must expose the `unit` field as mandatory metadata, and the Plugin Validator (§63) must reject a manifest that omits it for any Float/Vec2/Vec3 parameter.
>
> **Consequence:** a project authored/previewed at draft resolution and exported at 4K must render identical composition geometry and identical *relative* effect scale (a blur that looks like "medium blur" in preview must look like the same relative blur at export resolution, not four times sharper or four times blurrier).

### 14. TIMELINE

Timeline contains: Audio track, Layer tracks, Playhead, Time ruler, Zoom, Pan. Operations: play, pause, seek, frame step, jump start, jump end, trim, move, duplicate, delete, reorder.

#### 14.1 Clock Authority

> **[RATIFIED — Ref AR-2.3]** New, mandatory requirement resolving an unstated ambiguity in v2.0:
>
> - **During interactive preview**, the **audio playback clock is master**. The media playback position (from the audio decoder/player, e.g. an `AudioTrack`/`ExoPlayer`-style position) drives the render timestamp `T` requested from the RenderGraph each frame. Dropped video frames are acceptable (the renderer simply renders fewer of the requested timestamps); dropped or resampled audio is not acceptable under any circumstance.
> - **During export**, there is no live audio playback clock. Export is driven by a **virtual frame clock** — `T = frameIndex / exportFps` — and every single frame at every such `T` is rendered with no drops. Export audio is rendered/mixed independently (not synchronized to a live clock) and muxed against the video stream using the same nominal timestamps. This is intentionally a *different* clock-authority regime from preview, and this difference is safe specifically *because* export never drops frames — sample-accurate sync is achieved at mux time, not by sharing a clock object with preview.
> - Both regimes are equally deterministic per §9.1: whichever clock is master, the frame at timestamp T is still the pure function defined in §9.1.
> - The timestamp `T` above (in both regimes) is **timeline time**, relative to the trimmed selection's own `t=0`. Per §9.1, all audio-analysis-derived modulation is indexed in absolute **audio-source time**; the translation `audioT = T + trimIn` happens exactly once, inside `ParameterResolver` (§22.1), and nowhere else — layers, effects, and plugins only ever receive already-resolved values for timeline time `T` and never reason about `trimIn` themselves.
> - **[RESOLVED — P-5] Scope of "and nowhere else."** That constraint governs the **render / parameter-resolution pipeline**: within it, `ParameterResolver` is the sole translation site, so no layer, effect, plugin, or renderer ever applies `trimIn` itself. It does **not** govern editor-internal coordinate/time conversion required for display. The Trim Editor (§16) inherently works in audio-source time — it displays the whole asset, with the trim handles and playhead drawn onto it — and its display-space conversions are expected and permitted. An editor converting between domains to draw a waveform is not a violation of this rule; a renderer or plugin applying `trimIn` inside the parameter pipeline is.

#### 14.2 Layer Visibility Is a Compositing Gate, Not a Modulator Reset

> **[RATIFIED — Ref AR-4.5]** `timelineRange` (the per-layer active window, §11) is implemented as a **final compositing visibility mask only**. A layer's `ReactiveMapping` modulators (envelope followers, peak/gravity trackers) always see the **true, full audio history** from the fixed epoch (§9.1), regardless of whether the layer itself is currently visible. Layer visibility must never reset, pause, or short-circuit modulator computation — it is applied strictly after modulation resolution, as the very last step before the layer's output is composited (or skipped) into the parent frame. This guarantees a layer that becomes visible mid-track shows a "warm," historically-correct modulator state on its very first visible frame (e.g. a peak-hold value already reflecting prior bass hits), not a cold-started one.

### 15. AUDIO IMPORT

Minimum formats: WAV, MP3, M4A/AAC, FLAC, OGG. Architecture: `AudioDecoder`, `AudioPlayback`, `AudioAnalyzer`, `AudioAnalysisCache`, `AudioExport`. These components must be independent.

> **[RATIFIED — Ref AR-19.1]** Confirms and cross-references §1: v1 supports exactly **one** audio asset per project. `Audio` in the Project Model (§10) is a single object, not a list. A future revision may add multi-track mixing; until then, no subsystem (Timeline, Reactive Engine, Export) should be designed assuming more than one concurrent audio source.

### 16. AUDIO TRIMMING

Professional waveform trimmer. Required: waveform, in point, out point, playhead timestamp, zoom, pan, snap, preview loop, selection fade in, fade out, volume, mute, reset. Dragging handles must provide immediate visual feedback.

> **[RATIFIED — Ref AR-14.1 (Review)]** "Immediate visual feedback" is satisfied by reading from the **derived preview cache** (waveform peaks decoded once at import time, §82.1), never by re-opening the original SAF-backed asset URI per redraw/scrub frame. This is binding: any implementation that re-decodes or re-opens the source asset on every waveform zoom/scrub interaction does not meet this requirement.

### 17. AUDIO ANALYSIS ENGINE

Analyze once. Reuse everywhere. Do NOT calculate separate FFTs for every layer.

Required global features: RMS, Peak, Energy, Normalized Energy, Loudness Approximation.
Frequency: FFT Magnitude Spectrum, Log Spectrum.
Bands: Bass, Low Mid, Mid, High Mid, Treble.
Musical: Onset, Beat Probability, Beat Phase, Tempo, Spectral Centroid, Spectral Flux, Spectral Rolloff, Spectral Flatness, Chroma.
Custom: Arbitrary frequency band.

#### 17.1 Progressive, Priority-Ordered Analysis

> **[RATIFIED — Ref AR-7.1]** Analysis runs on a background thread pool, **chunked and progressive**, in this mandatory priority order: (1) waveform peaks (near-instant, feeds §16's Trim UI), (2) RMS/peak envelope, (3) FFT magnitude/log spectrum and frequency bands, (4) onset/beat/tempo, (5) spectral centroid/flux/rolloff/flatness/chroma (highest cost, lowest priority). The Trim screen must be usable within ~1 second of import using only stage (1)+(2); deeper analysis continues in the background with a visible, non-blocking progress indicator. Scrubbing/reactive preview for a not-yet-analyzed time region must never block the UI thread — it displays an explicit "analyzing…" state (consistent with §97's non-generic-error principle, applied here to progress) and falls back to the nearest available cached sample.
>
> **Export precondition:** unlike preview, export must have **100% of required analysis precomputed before the first export frame renders** — this is always achievable (export is not real-time) and must be enforced as a hard precondition check by the Export Engine (§92), which blocks export start and shows explicit progress if analysis is incomplete, rather than exporting frames against partial/interpolated analysis.

#### 17.2 Analysis Cache Resolution & Interpolation

> **[RATIFIED — Ref AR-2.6]** New, mandatory requirement: the `AudioAnalysisCache` (§18) stores all derived features on a **common fixed timeline at 100Hz (10ms hop)**. The underlying FFT window size is independently configurable (§106, "FFT size," default 2048 samples — see §17.3 for the ratified canonical sample rate that fixes this window's temporal duration) but every feature — regardless of its native frame rate — is resampled/aligned to the 100Hz storage timeline before caching. Any consumer (Reactive Engine, UI) requesting a value at an arbitrary timestamp T linearly interpolates between the two adjacent 100Hz cache samples. This single decision anchors cache file size estimates, the concurrency contract (§18.1), and the Resolved Modulation Cache's own resolution (§27.2), and must not be changed without re-validating all three.
>
> **Note on the FFT hop:** v2.0's parenthetical named a "50% overlap" default alongside this 100Hz storage timeline. Those two figures are arithmetically incompatible once §17.3 fixes the canonical rate at 48 kHz, and the conflict is resolved in §17.5: **the canonical hop is 480 samples**, giving exactly 100 native analysis frames per second. **The "50% overlap" figure is superseded and is no longer the canonical configuration** — it must not be cited, restored, or assumed anywhere. Spectral frames are therefore *measured* at the storage rate and are never interpolated or upsampled to reach it.

#### 17.3 Canonical Analysis Signal and Sample Rate

> **[RESOLVED — P-1, P-2]** All analysis in §17 operates on a single, deterministic **canonical analysis signal**. This signal is an analysis-only derivation: it never replaces, alters, or is substituted for the source audio.
>
> **Channel policy (P-1) — the canonical analysis signal is MONO.**
> - Stereo source: `mono[n] = 0.5 × left[n] + 0.5 × right[n]`.
> - Mono source: passed through unchanged.
> - *Derived generalization (flagged, not independently ratified):* sources with more than two channels are downmixed by equal-weight averaging across all channels — the arithmetic generalization of which the ratified stereo rule is the exact two-channel case. Named here explicitly so it is a documented rule rather than a silent assumption; override it if a different multichannel policy is wanted.
> - **The source is not modified.** The original channel count, channel layout, and sample rate are preserved in the Asset Registry (§82) and in decoder metadata (§15).
> - **Playback (§14.1, §16) and any future export (§136) use the original source audio, never the canonical mono signal.** Mono is an analysis domain, not an output format.
> - Every scalar analysis feature in §17 operates on this canonical mono signal unless a future, explicitly ratified feature states otherwise.
> - §119's stereo fixture is mandatory and must verify deterministic channel handling — specifically that L/R-differing input produces the exact downmix above, repeatably.
>
> **Canonical sample rate (P-2) — 48,000 Hz.**
> - All source PCM whose rate differs from 48 kHz is deterministically resampled to 48 kHz **before** analysis. A source already at 48 kHz is passed through bit-exact, with no resampling stage applied.
> - The resampler must be a **fixed, software, deterministic** implementation (windowed-sinc polyphase, coefficients defined in code). It must **never** delegate to platform or hardware resampling, whose behavior varies across devices — that would break §9.1's determinism guarantee and make golden vectors device-dependent.
> - The resampler's coefficient set and algorithm are covered by the analysis algorithm/schema version in §18.2, so any change to it invalidates dependent cache entries.
> - The canonical analysis sample rate is part of analysis configuration identity (§18.2).
>
> **Consequences of fixing the canonical rate:**
> - The §17.2 default 2048-sample FFT window has exactly one temporal duration: `2048 / 48000 s ≈ 42.667 ms`.
> - Bin width is `48000 / 2048 = 23.4375 Hz`.
> - *Informational:* at the default FFT size, §20's lowest default band (20–60 Hz) is covered by roughly two bins. This is an inherent property of a fixed-window FFT, and is why §106 keeps FFT size user-configurable; it is not a defect.

#### 17.4 Retained Spectrum Representation

> **[RESOLVED — P-4]** The full-resolution float32 FFT spectrum is **not** retained. The canonical retained representation is:
>
> - **1024 magnitude bins**, **100 Hz** temporal sampling, **FP16 (IEEE 754 binary16)** storage. Per §17.5 every retained frame is a **measured** frame — produced natively at 100 Hz by the 2048-sample window / 480-sample hop framing — never an interpolated or upsampled one.
> - **Concrete bin mapping** (the arithmetic meaning of "1024 bins" for the default 2048-point FFT at 48 kHz): a real FFT of a 2048-sample window yields 1025 unique bins (DC through Nyquist). The retained set is **bins 1…1024**; the DC bin (0 Hz) is discarded, as it carries no musical information and is contaminated by DC offset. Retained coverage is 23.4375 Hz … 24,000 Hz.
> - **Magnitude scaling:** magnitudes are normalized against the canonical signal's full-scale reference before FP16 conversion. FP16 carries a 10-bit mantissa (~3 decimal digits); §119's "very quiet signal" fixture is the designated test for whether that precision holds at low amplitude. Should the tolerance tests below show it does not, the documented fallback is a dB-domain variant, which is a **format** change and therefore requires a `formatVersion` bump (§18.3) — not a silent reinterpretation.
>
> **Stored separately (computed during analysis at full float32 precision, then stored):** RMS, Peak, Energy, Normalized Energy, Loudness Approximation — the last three defined normatively in §17.6, which also requires Normalized Energy's track-relative reference to be stored as immutable cache metadata — Spectral Centroid, Spectral Flux, Spectral Rolloff, Spectral Flatness, Chroma, Onset strength, Beat probability, Beat phase, Tempo, and the §20 **default** band set. These are stored rather than derived because computing them from the reduced FP16 spectrum at read time would be both less accurate and more expensive than computing them once from the full-precision spectrum during analysis.
>
> **Derived at read time from the retained spectrum (never re-analyzed):**
> - **Arbitrary/custom frequency bands (§20).** Binding: a custom band **must** be derivable from the retained spectrum **without re-running the FFT and without re-decoding the source**. §22.2's per-frame source deduplication computes each unique custom band once per frame.
> - **Log spectrum (§17).** A re-binning of the retained linear magnitude spectrum — an axis transform, not new information.
>
> **Precision testing (mandatory):** deterministic golden and tolerance tests must establish and document the accepted precision of the FP16 representation, across §119's full fixture set, including the very quiet and clipping cases. The measured tolerances are recorded in `PERFORMANCE.md` and become the thresholds the CI tier (§77.1) enforces.
>
> **Size consequence (binding input to §18.3's disk budget):** 1024 bins × 2 bytes × 100 Hz ≈ **200 KiB/s ≈ 11.7 MB per track-minute** for the spectrum, plus roughly 0.7 MB per track-minute for the stored scalars — **≈ 12.4 MB per track-minute**, so a five-minute track costs ≈ **62 MB**. This figure, not an arbitrary round number, is what §18.3's budget is derived from.

#### 17.5 Canonical Analysis Framing — FFT Window, Hop, and Frame Rate

> **[RESOLVED — U-21]** The canonical analysis framing is three **separate** quantities, and they must be kept distinct in specification text, configuration, code, and tests. Conflating any two of them is the defect this section exists to prevent:
>
> | Quantity | Canonical value | Notes |
> |---|---|---|
> | **FFT window size** | **2048 samples** | Unchanged from §17.2. At the §17.3 canonical rate this is a `2048/48000 s ≈ 42.667 ms` window; bin width `23.4375 Hz`. |
> | **Analysis hop** | **480 samples** | The distance between successive analysis frames. `480/48000 s = 10 ms` exactly. |
> | **Native analysis frame rate** | **100 Hz** | `48000/480 = 100` frames per second, exactly — identical to §17.2's storage timeline. |
>
> Resulting window overlap is `(2048 − 480)/2048 = 76.5625%`. **Overlap is a derived quantity, never an input:** the hop is canonical, and overlap is whatever the window and hop imply. The former **"50% overlap"** figure inherited from v2.0 is **superseded and must not be retained, cited, or described as the canonical configuration** anywhere in this specification, in code, or in tests.
>
> **Binding consequence — no interpolation to reach the storage rate.** Because the native analysis frame rate is exactly the §17.2 storage rate, FFT-derived frames are written to the cache one-for-one as **measured** frames. Spectral frames must **never** be interpolated or upsampled merely to satisfy the 100 Hz timeline. §17.2's "resampled/aligned to the 100 Hz storage timeline" rule continues to govern any feature whose *own* native rate differs (for example a tempo estimate produced over a longer window); it is inapplicable to, and must not be applied to, the FFT path, which is already native.
>
> **Derived framing consequences** (mechanically forced by the values above; recorded so they are specified rather than assumed):
> - **Frame anchoring: a frame's timestamp is its window START.** Analysis frame `n` covers input samples `[n·480, n·480 + 2048)` and is stored at cache timestamp `n · 10 ms`, measured from the §9.1 epoch. Any other anchoring — window centre, for instance — would place frames at `n·10 ms + 21.333 ms`, which never lands on the 10 ms grid and would reintroduce exactly the interpolation this ratification forbids. Start-anchoring is therefore forced, not chosen.
> - **Tail handling:** frame count is `N = ceil(totalSamples / 480)`; the final windows are zero-padded where the window extends past the end of the asset. This guarantees every part of the asset is covered by at least one frame and makes `N` a pure function of asset length.
> - **Onset/beat temporal resolution (§21) is 10 ms**, not 21.3 ms.
>
> **Cache identity:** the hop is an `analysisConfigHash` input in its own right (§18.2 item 5), independent of FFT size and of frame rate. Changing any of the three invalidates dependent cache entries.



#### 17.6 Scalar Feature Definitions — Energy, Normalized Energy, Loudness Approximation

> **[RESOLVED — T-9, T-7, T-8]** §17 requires Energy, Normalized Energy and Loudness Approximation
> as global features and §17.4 requires all three to be **stored**, but v3.0 defined none of them.
> This section is that definition and is normative. Throughout, `x` is the §17.3 canonical mono
> signal, `N = 2048` is the §17.5 analysis window, and `n` indexes the §17.5 analysis frames on
> §17.2's 100 Hz timeline.
>
> **Energy [T-9].**
>
> ```
> Energy[n] = Σ x²   over the 2048-sample analysis frame n
> ```
>
> The sum-of-squares convention, not mean square. Because `N` is fixed by §17.5, the two
> conventions interconvert exactly (`mean = Energy / 2048`) as a read-time transform, so this
> choice is reversible without re-analysis. Recorded so the convention is a decision rather than
> an accident.
>
> **Normalized Energy [T-7].**
>
> ```
> NormalizedEnergy[n] = Energy[n] / max_m Energy[m]        (maximum over the whole track)
> ```
>
> The reference is **track-relative**, which makes the feature invariant to source gain: scaling
> `x` by `k` scales every `Energy` by `k²` and leaves the ratio unchanged. This is what
> distinguishes Normalized Energy from Energy as a separate §17 feature — an absolute full-scale
> reference would make it exactly `RMS²`, a unit change rather than a second measurement.
>
> Two binding consequences:
>
> - **The reference must be finalized before cache publication.** `max_m Energy[m]` is computed by
>   a whole-track pass that completes before any stage-2 frame is published. **Incremental
>   refinement of the reference is forbidden**: a running maximum that grows during analysis would
>   make every already-written frame wrong, and revising written frames contradicts §18.1's
>   "immutable-once-written and append-only" contract.
> - **The reference is stored as immutable cache metadata.** It is written once, in the cache
>   header, and never updated. Storing it keeps the value auditable and lets a reader recover raw
>   `Energy` from a stored ratio; a bare ratio would not.
>
> **Note the deliberate asymmetry with §17.4.** §17.4 normalizes retained *spectral magnitudes*
> against "the canonical signal's full-scale reference" — an **absolute** reference. Normalized
> Energy uses a **track-relative** one. The two references are different by design and must not be
> conflated when comparing features.
>
> **Loudness Approximation [T-8].**
>
> ```
> Loudness[n] = max( −70.0 ,  −0.691 + 10·log₁₀( (1/N) · Σ y² ) )     over analysis frame n
> ```
>
> where `y` is the §17.3 canonical mono signal after the ITU-R BS.1770 **K-weighting** pre-filter
> (high-shelf followed by RLB high-pass). The ratified parameters are:
>
> | Parameter | Ratified value |
> |---|---|
> | Weighting | **K-weighting** (BS.1770 shelf + RLB high-pass) |
> | Gating | **None** (ungated) |
> | Measurement window | **Per analysis frame** — the §17.5 framing, natively at 100 Hz |
> | Channel basis | **Canonical mono** (§17.3) |
> | Output units | **dBFS** |
> | Silence floor | **−70.0 dB** |
> | Loudness target normalization | **None** |
>
> Every parameter above is fixed here and is therefore *not* a configurable input. Because §17.3
> fixes the analysis rate at 48 kHz, the K-weighting coefficients are the published BS.1770
> constants — no rate-dependent filter design, and no vendor variation.
>
> The definition is deliberately **causal and per-frame**: each value depends only on its own frame
> plus a fixed filter state, so §18.1's immutability and §17.1's progressive stage order hold with
> no prepass, and the value lands natively on §17.2's 100 Hz timeline with no realignment. It is
> BS.1770's momentary measure without the 400 ms window and without gating — an *approximation*,
> exactly as §17 names it. Measuring on a mono downmix is likewise approximate by construction:
> BS.1770 defines per-channel weights for multichannel material, and §17.3 fixes analysis to mono.
>
> **No loudness target participates in analysis.** There is no target level, no `targetLufs`, and
> no gain applied to reach one. Any such parameter is outside this specification and **must not**
> be a member of `analysisConfigHash` (§18.2 item 8).

### 18. AUDIO ANALYSIS CACHE

Create reusable `AudioAnalysisCache`. Store: `timestamp, RMS, Peak, FFT, Bands, Onset, Beat, Centroid, Flux, Chroma`. Prefer efficient binary storage for large analysis data.

Changing image position MUST NOT invalidate analysis. Changing effect blur MUST NOT invalidate analysis. Changing audio file or analysis settings MUST invalidate appropriate analysis data.

#### 18.1 Concurrency Contract and Storage Scope

> **[RATIFIED — Ref AR-6.1, AR-10.1]**
>
> **Concurrency:** `AudioAnalysisCache` is **immutable-once-written and append-only**, randomly readable by timestamp, structured so the render thread can read it **lock-free** (e.g. a versioned array/ring buffer behind an atomic "highest-complete-index" marker) and **never blocks** waiting for analysis to catch up during preview. If analysis for time T is not yet available, the renderer uses the nearest available cached sample and flags the frame in Diagnostics (§98) as "analysis pending" — it never stalls the render thread. During export, the §17.1 precondition guarantees this situation cannot occur.
>
> **Storage scope:** the cache is **content-addressed**, keyed by `(assetHash, analysisConfigHash)` — where `assetHash` is the asset's content hash as recorded on its Asset Registry entry (§82's `hash` field), obtained by resolving the project's `audio.assetRef` (§10) through the Asset Registry, never the raw `assetRef` value itself (see §27.2 for the identical rule applied to the Resolved Modulation Cache) — and stored **external to the portable project file** in an app-managed cache directory — never embedded in the JSON project (§10, §81). It is disposable and regenerable: if missing (fresh install, cleared cache, moved project), it is silently regenerated on first use following the §17.1 progressive/priority order. Project backup/export/share bundles (§111) **never** include analysis cache payloads.

#### 18.2 `analysisConfigHash` Membership (Normative)

> **[RESOLVED — P-3]** §18 requires that changing "analysis settings" invalidate analysis data but never enumerated them. This section is that enumeration, and it is normative in both directions.
>
> **Governing test:** *if changing an input changes a number stored in the cache, it is in the hash; if it only changes how an already-stored number is consumed, it is not.*
>
> **The hash MUST include (minimum, non-exhaustive):**
> 1. Canonical analysis sample rate (§17.3).
> 2. Channel policy (§17.3).
> 3. FFT size (§106) — canonically 2048 samples (§17.5).
> 4. FFT window function.
> 5. Analysis hop (§17.5) — canonically 480 samples. A distinct input from FFT size and from frame rate; overlap is derived from window and hop and is never itself an input.
> 6. Analysis frame rate (§17.5) — canonically 100 Hz, matching §17.2's storage timeline.
> 7. **Default** frequency-band definitions (§20) — i.e. the band set whose values are *stored*.
> 8. Normalization algorithm and configuration (§19, §106 "Normalization"). Per §17.6 [T-8] no loudness *target* exists, so no target level may be a member; the Loudness Approximation parameters are fixed by §17.6 rather than configured.
> 9. Beat-analysis configuration (§21, §106 "Beat detection").
> 10. Analysis-quality level (§106 "Analysis quality").
> 11. Analysis algorithm/schema version — covering resampler coefficients, window-function implementation, and any DSP change that alters output for identical input.
>
> **The hash MUST NOT include (normative exclusions):**
> - **Reactive/mapping parameters (§23):** gain, offset, curve, threshold, deadZone, attack, release, smoothing, invert, falloff, clamp, `combineOp`, `beatMultiplier`, `phaseOffset`. These govern consumption, not computation.
> - **Master Sensitivity and master Smoothing (§106).** Both are consumption-side: raw features are cached, and sensitivity/smoothing are applied at read time. Placing either in the hash would trigger a full re-analysis on a slider nudge, which the governing test forbids.
> - **User-defined custom bands (§20).** Per §17.4 these are derived from the retained spectrum at read time and change no stored value. Including them would make every new custom band a full re-analysis — the single most user-visible way to get this wrong.
> - **Trim in/out, gain, fades, mute (§16).** These affect playback and future export only. §9.1 already fixes the analysis epoch at the raw asset's `t=0`, making trim analysis-independent; this states it explicitly.
> - **Preview quality and render settings (§86), and every keyframe (§41).**
>
> This split is what makes §27.2's `audioAnalysisCacheKey` correct: the Resolved Modulation Cache inherits this hash, so an over-broad hash would cascade spurious modulation recomputation, and an under-broad one would serve stale trajectories — the exact defect §27.2 exists to prevent.

#### 18.3 Cache Format Version, Disk Budget, and Eviction

> **[RESOLVED — P-6]**
>
> **Format version.** Every cache file carries a `formatVersion` in its header, **independent of `analysisConfigHash`**. The two answer different questions: `analysisConfigHash` identifies *what was computed*; `formatVersion` identifies *how it is laid out on disk*. A reader encountering an unknown or newer `formatVersion` **rejects the file outright, deletes it, and regenerates** — it never attempts a partial or best-effort parse.
>
> **Disposability (strengthening §18.1).** The cache is disposable in the strongest sense: **project data never depends on cache presence, and cache eviction can never invalidate, degrade, or alter project state.** Losing the entire cache directory costs time, never work. This is what makes an aggressive eviction policy safe.
>
> **Bounded budget.** Default **1 GB**, user-configurable **256 MB – 8 GB**. Derived from §17.4's measured ≈12.4 MB per track-minute: 1 GB ≈ 80 track-minutes ≈ 16 five-minute tracks. The default is stated as a derivation rather than a round number so it can be re-derived if §17.4's representation or U-21's hop changes the per-minute cost.
>
> **Deterministic eviction.**
> - **LRU by last-access timestamp**, evicting **whole `(assetHash, analysisConfigHash)` entries only**. Partial eviction is forbidden: a half-present entry would violate §18.1's immutability and completeness contract.
> - The entry for any audio asset referenced by a **currently-open project is never evicted** while that project is open.
> - Eviction is idempotent and safe to interrupt; an interrupted eviction leaves only whole entries.
> - OS low-storage signals are honored in addition to the budget. This is disk pressure and is distinct from §98.1's memory-pressure response, which releases resident pages without deleting files.

### 19. AUDIO NORMALIZATION

*(Unchanged from v2.0.)* Visual reaction must be independent of device playback volume. Internal analysis operates on normalized signal characteristics.

### 20. FREQUENCY SYSTEM

Support logarithmic frequency visualization. Default bands: `20–60, 60–120, 120–250, 250–500, 500–1000, 1k–2k, 2k–4k, 4k–8k, 8k–16k`. Users may create custom bands.

### 21. BEAT SYSTEM

Expose: `beatConfidence, beatPhase, tempo, onsetStrength`. Do not assume all music has stable BPM. Beat detection is supplementary. It must never replace raw spectral analysis.

> **[RATIFIED — Ref AR-7.2; CLARIFIED — Ref AR-1.4 (marked N / clarify-only in the Review, not a required spec change, but incorporated here as a non-binding clarification)]** This resolves the tension between "beat is supplementary" and the UI treating Beat as an equally-weighted first-class reactive source (§105, §107, §149):
>
> - Beat/tempo/onset are exposed identically to any other `ReactiveSource` (Bass, Mid, RMS, etc.) in every reactive-mapping picker — there is no special-cased "less important" UI treatment.
> - However, **every `ReactiveSource` carries a `reliability`/`confidence` channel** (not just beat — this generalizes to any source that can legitimately be unreliable for certain input, e.g. a custom analyzer plugin on silence). For Beat specifically, when `beatConfidence` is below a documented threshold for a sustained window (e.g. persistently low on ambient/rubato/beatless music, an explicitly supported genre per §110's "Dark Ambient" template), the resolved beat-driven modulator output **holds its last stable value or decays toward the mapping's configured neutral point** — it never free-runs into noisy false triggers.
> - The Reactive Mapping UI (§105) surfaces a persistent, visible low-confidence indicator on any mapping whose source has sustained low reliability, prompting (not forcing) the user to consider Onset or RMS instead.

### 22. REACTIVE ENGINE

Any animatable parameter can receive audio modulation. Example: Scale, Source: Bass, Mapping: 0.85 → 1.10, Attack: 0.03s, Release: 0.30s, Curve: Exponential.

#### 22.1 ParameterResolver Contract

> **[RATIFIED — Ref AR-4.1]** New, mandatory interface — this is the actual mechanism (not previously specified in v2.0) by which "any parameter, any target" (§2, §25, §26) is achieved consistently across core and plugin code:
>
> Every renderable (layer property, effect uniform, plugin-declared parameter) holds a `ParamRef` — a reference, never a raw resolved value. The Renderer, once per frame, calls:
>
> ```
> ParameterResolver.resolve(parameterId: ParamRef, timestampT: Time) -> ResolvedValue
> ```
>
> exactly once per distinct `ParamRef` actually referenced that frame (memoized within the frame — see §22.2 for source-level deduplication beneath this), **before** any draw call is issued. No layer, effect, or plugin ever reads audio/analysis state directly or maintains its own modulation logic — resolution is centralized. This is what makes the plugin write-only injection contract (§50.1) possible and is the seam the Renderer (§8), Reactive Engine (this section), and Plugin Runtime (§44) all depend on.

#### 22.2 Source Evaluation Deduplication

> **[RATIFIED — Ref AR-7.3]** Within a single frame's resolution pass (§22.1), the Reactive Engine computes each **unique `ReactiveSource` definition** (e.g. a specific custom FFT band, or Bass, or a specific plugin Analyzer output) **exactly once**, regardless of how many `ReactiveMapping`s or layers consume it. All consumers of an identical source definition read the same single computed value for that frame. This is mandatory to keep total per-frame Reactive Engine cost at `O(unique sources) + O(mappings)` rather than `O(layers × mappings)`, which is required to stay inside the CPU budget in §100.1.

### 23. REACTIVE MAPPING

`ReactiveMapping` contains: `source, sourceRange, targetRange, gain, offset, threshold, attack, release, smoothing, curve, clamp, invert, deadZone, falloff, beatMultiplier, phaseOffset`.

> See §27.1 for the mandatory `combineOp` field added to every mapping, and the Core Data Model in §10 for the full binding schema.

### 24. REACTIVE SOURCES

Built-in: `RMS, Peak, Bass, Low Mid, Mid, High Mid, Treble, Custom FFT Band, Onset, Beat, Beat Phase, Spectral Centroid, Spectral Flux, Chroma`. Extension plugins may introduce additional sources (§55, §56).

### 25. REACTIVE TARGETS

Any parameter exposed as animatable may become a target. Examples: `Scale X/Y, Rotation, Opacity, Blur, Brightness, Contrast, Saturation, Hue, Glow, Glow Radius, Displacement, Chromatic Aberration, RGB Split, Noise, Distortion, Particle Size, Particle Speed, Particle Count, Spectrum Height, Waveform Amplitude, Waveform Thickness, Text Size, Text Opacity, Shader Uniforms`.

> **[RATIFIED — Ref AR-11.1]** Every such target, wherever declared (core built-in effect or plugin parameter), must be classified as either a **quality knob** (safe to differ between draft/full preview quality and export — e.g. blur kernel size at low vs. full precision, MSAA level) or a **content knob** (must be bit-identical in value across draft preview, full preview, and export — e.g. Particle Count, any procedural/random seed). This classification is mandatory metadata on the Parameter type (§13.1's `unit` field sits alongside it), enforced by the Plugin Validator (§63) for plugin-declared parameters and by code review for built-ins. See §86.1 for the binding rule this classification feeds.

### 26. MULTIPLE MODULATORS

A single parameter may have multiple modulation sources. Example: `Rotation: Bass × 0.5 + Beat × 2.0 + Noise × 0.1`. The modulation system must support compositing multiple modifiers.

> **[RATIFIED — Ref AR-3.1]** **v1 modulation is a flat, non-recursive list.** A parameter has an ordered list of `ReactiveMapping`s, each independently reading a `ReactiveSource`; modulators are never modulated by other modulators (no "Bass modulates the gain of the Beat modulator" in v1). This bounds Reactive Engine per-frame cost to `O(layers × parameters × mappings)`, required for the frame budget (§87, §100.1). Meta-modulation (a modulator's own parameters being themselves modulated) is explicitly deferred to a future API major version and, if ever introduced, must be structured as a DAG with mandatory cycle detection at validation time — a modulation cycle must never be constructible, in v1 or any future version.

### 27. MODULATION PIPELINE

Recommended: `Base Value → Keyframe Modifier → Audio Modifier → Noise/Random Modifier → Additional Modifiers → Clamp → Final Value`. This is a fundamental extensibility mechanism.

#### 27.1 Ratified Pipeline Semantics (Binding, Not "Recommended")

> **[RATIFIED — Ref AR-3.2, AR-2.4]** This upgrades the pipeline from "recommended" to **binding and fully specified**, resolving two ambiguities:
>
> 1. **Keyframes define the base value.** The `keyframeTrack` (§10's Parameter schema), interpolated per its easing curve (§41.1) at timestamp T, is the *base value* input to the pipeline — never an additive layer on top of a separately-tracked base. All reactive/noise modifiers apply as deltas on top of this interpolated base value. (If no keyframe track is present, the base value is simply the static `baseValue`.)
> 2. **Multiple `ReactiveMapping`s on one parameter combine via each mapping's own `combineOp`** (`Add | Multiply | Max | Min | Override`), evaluated in the mapping list's declared order (top-to-bottom = evaluation order, exactly analogous to layer effect-chain ordering, §36). Formally: `resolvedPreClamp = foldl((acc, mapping) -> combine(acc, mapping.combineOp, mapping.resolve(T)), keyframeBaseValue, orderedMappings)`, followed by `Clamp` to the parameter's declared valid range to produce `ResolvedValue`.
>
> The full binding pipeline is therefore: `KeyframeTrack(T) [base] → fold(ReactiveMapping[], combineOp, in declared order) → Clamp → ResolvedValue`. The UI (§105, "Advanced: multiple modulators") must expose both the mapping list's order and each mapping's `combineOp`, with reordering support.

#### 27.2 Resolved Modulation Cache

> **[RATIFIED — Ref AR-8.2, AR-11.2]** New, mandatory performance/correctness mechanism: for every parameter's ordered list of `ReactiveMapping`s, the **audio-driven modulator delta trajectory** — i.e. the fold of `ReactiveMapping`s per §27.1, evaluated in absolute audio-source time per §9.1 — is **precomputed once, at the same 100Hz resolution as the AudioAnalysisCache (§17.2)**, and cached.
>
> **What is cached, precisely:** only the audio-driven delta (the reactive-mapping fold). The `keyframeTrack` base value (§27.1) is **never** part of this cache — it is cheap to evaluate (O(1) curve interpolation) and is always evaluated live at query time, then combined with the cached delta per §27.1's pipeline. Consequently, editing a `keyframeTrack` never invalidates this cache.
>
> **Cache key, precisely:** `(parameterId, mappingConfigurationHash, audioAnalysisCacheKey, analyzerSourceVersionKey?)`, where:
> - `mappingConfigurationHash` is a hash of the parameter's full, ordered `ReactiveMapping[]` list — including each mapping's `source` reference, not just its tuning fields (gain/attack/release/curve/etc.).
> - `audioAnalysisCacheKey` is exactly the `(assetHash, analysisConfigHash)` key the underlying `AudioAnalysisCache` is itself keyed by (§18.1) — never omitted, since without it a change of audio asset or analysis settings (FFT size, §106) would not invalidate this downstream cache, silently serving the old audio's trajectory against new audio, in direct violation of §18's rule that changing the audio file or analysis settings "MUST invalidate appropriate analysis data." **`assetHash` is obtained by resolving `audio.assetRef` (§10) through the Asset Registry (§82) to that asset's `hash` field — `assetRef` is only a project-local identifier, never itself a content hash, and must never be used directly as (or in place of) `assetHash` in this key.**
> - `analyzerSourceVersionKey` is `(pluginId, pluginVersion)` (or a content hash of the plugin's WASM module) for any mapping whose `source` resolves to a Tier-2 Analyzer Plugin output (§55/§56.1) — omitted only when every mapping's source is a built-in feature. Without this, updating an Analyzer Plugin (which can change its algorithm/output for identical audio) would not invalidate cache entries computed under the plugin's previous version.
>
> **Invalidation rule:** the cache entry is invalidated and recomputed whenever **any** component of this key changes — a mapping is edited or reordered, the audio asset is swapped/re-imported, analysis settings change, or a contributing Analyzer Plugin is updated. It is invalidated by **none** of: scrubbing, seeking, playback, dropped frames, keyframe edits, or trim-handle dragging (§9.1 defines why trim is excluded). No cache entry may remain valid when any of its semantic inputs above has changed, without exception.
>
> Consequences, both binding:
>
> - **Scrubbing/seeking becomes an O(1) cache lookup**, not an O(t) re-integration from epoch, keeping Timeline interactions (§14) feeling instant regardless of track length.
> - **Preview frame drops cannot change a modulator's trajectory** — dropped frames simply mean fewer samples of an already-fixed, precomputed trajectory are displayed. This is the concrete mechanism that guarantees preview-vs-export parity under performance variation (resolves the Review's determinism-under-frame-drop concern).

### 28. ATTACK / RELEASE

Implement proper envelope following. Attack: rapid response. Release: controlled decay. Required because raw amplitude mapping produces cheap, jittery visual behavior.

> Envelope followers are implemented per the §9.1 determinism definition (deterministic integration from a fixed epoch) and precomputed into the §27.2 Resolved Modulation Cache.

### 29. PEAK / GRAVITY MODEL

Support: Main Value + Peak Value. Peak: rises quickly; falls slowly; may temporarily float above the main value; is pulled upward when main value reaches it; has configurable decay; has configurable maximum. Required especially for spectrum peaks and meters.

> Peak/gravity trackers are likewise implemented per §9.1 and precomputed into §27.2's cache — identical treatment to Attack/Release.

---

## PART III — VISUALIZERS, LAYERS, EFFECTS

### 30. BUILT-IN VISUALIZERS

V1: Vertical Spectrum, Horizontal Spectrum, Radial Spectrum, Mirrored Spectrum, Circular Spectrum.
Controls: bar count, frequency range, spacing, width, height, smoothing, gain, falloff, peak hold, peak decay, interpolation, gradient, opacity, glow, round caps, mirror position.

#### 30.1 Shipped as First-Party Plugins

> **[RATIFIED — Ref AR-18.1]** Per §12.1, every visualizer in this section is implemented as a **first-party plugin** on the public Visualizer Plugin API (§53), bundled by default and non-removable in the default install, but mechanistically identical to any third-party visualizer plugin. `bar count`, `frequency range`, etc. above are the plugin's declared parameters (§49), each carrying `unit` (§13.1) and quality/content-knob classification (§25) metadata like any other plugin parameter (e.g. `bar count` is a **content knob**, per §25/§86.1 — it must never silently differ between draft preview and export).

### 31. WAVEFORM

Modes: Standard, Mirrored, Radial, Circular. Parameters: amplitude, thickness, smoothing, color, opacity, glow, sample count, interpolation, radius, start angle, end angle.

> Ships as a first-party plugin per §30.1's rule, applied identically here.

### 32. OSCILLOSCOPE

Parameters: amplitude, frequency window, thickness, smoothing, color, glow, opacity, phase, sample count.

> Ships as a first-party plugin per §30.1's rule, applied identically here.

### 33. PARTICLE SYSTEM

GPU-oriented. Parameters: count, size, speed, direction, spread, lifetime, gravity, turbulence, noise, attraction, repulsion, opacity, glow, color. Reactive targets: emission, velocity, size, turbulence, brightness, lifetime.

> Ships as a first-party plugin per §30.1's rule. **`count` and any procedural/simulation seed are content knobs (§25)** — the single most important instance of this rule, since particle count is explicitly called out in the Review as the canonical example of a value that must never be silently reduced for draft-preview performance (§86.1). Performance relief for low-end devices must come from the Adaptive Quality Degradation Ladder's manual, user-visible content-level override (§87.2), never an automatic silent reduction.

### 34. IMAGE LAYER

Support: PNG, JPEG, WebP, Alpha. Features: scale, rotation, crop, opacity, blend mode, mask, blur, color adjustment, distortion, audio-reactive transform. **CRITICAL:** Image A may react to audio. Image B may remain completely static. (Enforced by §78's permanent isolation test, ratified as CI-blocking in §77.1.)

### 34A. VIDEO LAYER PROXY WORKFLOW

> **[RATIFIED — Ref AR-19.2]** New, mandatory feature not present in v2.0: imported video assets above a configurable resolution/bitrate threshold automatically receive a **background-generated lower-resolution proxy** used transparently for preview/scrubbing (stored in the derived-data cache tier, §82.1) — never affecting the source asset. **Export always uses the full-resolution original source**, never the proxy. This is required for acceptable timeline-scrubbing performance with 4K+ source video on mid-range hardware (§12.2/§87.2) and is standard practice in professional NLEs, which this tool's positioning (§0) requires it to match.

### 35. TEXT LAYER

Support: font, size, weight, tracking, line spacing, alignment, fill, outline, shadow, glow, opacity, rotation, scale, position. Reactive: scale, opacity, glow, tracking, position, rotation.

### 36. EFFECT SYSTEM

Effects are modular. Every effect exposes: `id, name, version, parameters, render, serialize, deserialize, validate, benchmark, test suite`. Effects can be chained. Example: `Image → Blur → Chromatic Aberration → RGB Split → Glitch → Glow`.

### 37. BUILT-IN EFFECTS

V1: Blur, Glow, Chromatic Aberration, RGB Split, Glitch, Fisheye, Barrel Distortion, Kaleidoscope, Color Distortion, Noise/Grain, Pixelation, Vignette, Scanlines, Displacement, Shake, Zoom Pulse. Every effect must expose sensible parameters.

#### 37.1 Shipped as First-Party Plugins

> **[RATIFIED — Ref AR-18.1]** Per §12.1/§0, every effect in this section is implemented as a **first-party Effect Plugin** (§51/§52) on the public API, bundled by default, mechanistically identical to any third-party effect plugin. This is the primary vehicle by which the Extension Platform's completeness is proven before any third-party plugin exists (§0's ratification note). Each effect's parameters carry mandatory `unit` (§13.1) and quality/content-knob (§25) classification — e.g. Blur kernel radius/quality is a quality knob; Glitch's random seed (if any) is a content knob.

### 37A. COLOR GRADING (POST-V1, ARCHITECTURALLY RESERVED NOW)

> **[RATIFIED — Ref AR-19.3]** Not present in v2.0. Color grading (curves, LUT application, three-way color wheels) as a final-composite adjustment is **explicitly scoped as a post-v1 feature**, but the architecture must confirm *now* that it is expressible as an ordinary Effect Plugin with a special scope: **"operates on the final composite output," not a single layer.** The Plugin/Effect model (§36, §52) must support an effect class whose declared scope is `FinalComposite` rather than `PerLayer`, validated by a golden test fixture before this feature is actually built, even though the feature itself is deferred. See Appendix B, item U-13, for whether/when to schedule its implementation.

### 38. BLEND MODES

Minimum: `Normal, Add, Screen, Multiply, Overlay, Soft Light, Hard Light, Difference, Exclusion, Darken, Lighten`.

> **[RATIFIED — Ref AR-2.2]** All blend-mode math is defined and executed in **linear light color space** — see §90.1.

### 39. MASKS

Required: Rectangle, Circle, Gradient, Image Mask. Future: Vector Mask, Animated Mask.

### 40. COLOR SYSTEM

RGBA, HEX, Opacity, Gradient. Gradient: 2–8 stops.

> **[RATIFIED — Ref AR-2.2]** Color interpolation (for keyframes and gradients alike) is defined in §41.1 and executed in linear space per §90.1.

### 41. KEYFRAME SYSTEM

Any animatable parameter eventually supports keyframes: linear, ease in, ease out, ease in/out. Audio reactivity and keyframes must coexist (see §27.1 for the binding composition rule).

#### 41.1 Per-Type Interpolation Rules

> **[RATIFIED — Ref AR-8.1]** New, mandatory requirement — each Parameter **type** (§10, §49) declares its own `interpolate(a, b, t, easing)` rule as part of its type metadata, not left to ad hoc per-layer implementation:
>
> - **Angle:** shortest-arc interpolation by default (e.g. 350° → 10° interpolates through 360°/0°, a 20° sweep, not backward through 340°); an explicit "long way" / multi-rotation override flag is available for intentional spin effects.
> - **Color:** interpolated in **linear color space** (§90.1) by default; an optional HSV interpolation mode is available for gradient-friendly hue transitions.
> - **Vector2/Vector3:** componentwise linear (or per-component easing) interpolation.
> - **Curve/Gradient:** stop-wise interpolation (each stop's position and value interpolated independently).
>
> This table is part of the Core Data Model (§10) and must be implemented once, centrally, and reused by every layer/effect/plugin — never reimplemented per consumer.

### 42. PRESET SYSTEM

Every compatible layer/effect/visualizer must be serializable. Operations: save, load, duplicate, delete, export, import.

---

## PART IV — EXTENSION PLATFORM

### 43. EXTENSION PLATFORM

THIS IS A FUNDAMENTAL SUBSYSTEM. The application must support post-installation extensions. Extensions must not require rebuilding the host application. Extension types: (1) Effect Plugin, (2) Visualizer Plugin, (3) Generator Plugin, (4) Audio Analyzer Plugin, (5) Layer Plugin, (6) Importer Plugin, (7) Exporter Plugin.

### 44. PLUGIN ARCHITECTURE

```
CORE ENGINE
├── Audio API
├── Project API
├── Render API
├── Reactive API
├── Parameter API
├── Asset API
└── Timeline API
       │
       ▼
EXTENSION API
   ┌───┼────┐
   ▼   ▼    ▼
Effect Visualizer Generator
Plugin  Plugin    Plugin
```

Plugins interact ONLY through documented APIs. Plugins must not access arbitrary internal engine state.

> **[CLARIFIED — Ref AR-5.2 (marked N / implementation detail in the Review, not a required spec change, but incorporated here as a non-binding clarification)]** The Plugin Registry (below) is a **passively queryable** component: it exposes `List<LayerTypeDescriptor>`, `List<ReactiveSourceDescriptor>`, and equivalent descriptor lists for effects/visualizers/generators, observable by the `ui` module (e.g. via a reactive stream). The Registry has **zero dependency on `ui` or Compose**; plugins never push UI elements into the host directly (that would violate the declarative-UI-schema model of §47/§48). The `ui` module polls/observes the Registry to populate menus (Add Layer, Effects list, Reactive Source pickers) — dependency direction is one-way, `ui → plugins.registry`, never the reverse.

### 45. PLUGIN FORMAT

Define a native package format: `.arp` (Audio Reactive Plugin). Example: `chromatic_aberration.arp`. Package: `manifest.json, effect.json, parameters.json, shaders/, preview/, tests/, documentation/`. Optional: `module.wasm`.

> **[RATIFIED — Ref AR-1.2]** `module.wasm` is **not merely optional** for the plugin types that require genuine algorithmic logic. See §60.1 for the binding two-tier execution model this implies.

### 46. PLUGIN MANIFEST

Manifest includes: `pluginId, name, author, version, description, type, apiVersion, minimumAppVersion, maximumTestedAppVersion, permissions, entryPoints, parameters, dependencies, license, signature`.

> **[RATIFIED — Ref AR-9.3, AR-16.1]**
>
> - The manifest additionally includes a mandatory `migrations: List<VersionRange -> MigrationEntryPoint>` field for any plugin with persisted state beyond flat scalar parameters (any Layer, Effect config, or Analyzer config that stores structured data). See §69.1.
> - `signature` is defined precisely: it is a **self-signature used for integrity and update-authenticity only** (detecting tampering between versions, and confirming a claimed "update" actually originates from the same author key as the original install). It is explicitly **not** an authorization or vetting signal — there is no implied central review or app-store-style approval behind it. See §60.2 for the full trust-model statement, which the install-time UI must communicate honestly to the user. The concrete cryptographic scheme and key-management approach for this signature are open — see Appendix B, item U-16.

### 47. PLUGIN UI SCHEMA

Plugins must be capable of defining their UI declaratively. Example: Parameter `amount`, Type: float, min: 0, max: 1, default: 0.25, UI: slider. The host generates the appropriate control. Supported controls: Slider, Dial, Toggle, Dropdown, Color Picker, Gradient Editor, Frequency Range, Curve Editor, Angle Dial, Numeric Input, Button, Section, Separator.

### 48. PLUGIN UI AUTOMATIC GENERATION

A plugin does NOT need to modify Kotlin Compose source merely to create its settings UI. It declares parameters, groups, sections, controls, dependencies, visibility rules. The host renders the UI dynamically.

#### 48.1 Graceful Degradation for Unknown Control Types

> This section and §47 describe the UI Schema's controls and rendering behavior, but not yet a formal grammar (exact JSON keys/shapes for groups, sections, dependencies, and visibility rules) — that grammar is Plugin API surface required before Phase 7 can implement UI-schema generation; see Appendix B, item U-20.
>
> **[RATIFIED — Ref AR-2.5]** New, mandatory requirement: if a plugin declares a UI control type the current host version does not recognize (e.g. a control introduced in a later Plugin API minor/major version, per §68.1's SemVer policy, loaded by an older host), the host **must render a generic fallback control** (a numeric input for scalar parameter types, a JSON text box for opaque/structured types) rather than omitting the parameter entirely. Silently hiding a parameter the user cannot then access is treated as a correctness defect, not a cosmetic one. This fallback path is a named, tested component (§65/§76), exercised by a dedicated fixture plugin declaring a deliberately-unrecognized control type.

### 49. PLUGIN PARAMETERS

Parameter types: `Float, Integer, Boolean, Enum, Color, Angle, Vector2, Vector3, Frequency, FrequencyRange, Curve, Gradient, String, AssetReference`.

> **[RATIFIED — Ref AR-2.1, AR-11.1]** Every `Float`/`Vector2`/`Vector3` parameter **must** additionally declare: (a) its `unit` (`LogicalUnit | Normalized01 | RawPixel`, §13.1), and (b) its quality/content-knob classification (`QualityKnob | ContentKnob`, §25/§86.1). The Plugin Validator (§63) rejects any manifest omitting either field for an applicable parameter type.

### 50. PLUGIN AUDIO REACTIVITY

Any plugin parameter can declare `supportsReactive = true`. The host automatically exposes the complete Reactive API. Therefore a plugin author does NOT implement FFT, RMS, Beat, Bass, Smoothing, or Attack/Release unless the plugin specifically implements a new analyzer.

#### 50.1 Write-Only Injection Contract

> **[RATIFIED — Ref AR-4.3]** New, mandatory interface making the above concretely true rather than aspirational: a plugin parameter marked `supportsReactive = true` is, from the plugin's own code, a **write-only sink**. The plugin defines (or, for shader-backed parameters, simply names) an entry point such as `setParameter(id, value)` — or for GLSL uniforms, declares which uniform name binds to which declared parameter id, and the host writes that uniform directly. **The plugin never queries audio/analysis state itself, never calls a "get audio feature" function, and never has any handle to the Reactive Engine.** The host's `ParameterResolver` (§22.1) computes the fully modulated value for frame T and pushes it into the plugin's declared sink *before* the plugin's `render()` entry point is invoked. This is the precise mechanism that makes §50's promise and §60/§62's sandboxing constraint simultaneously true: the plugin gets full reactivity with zero access to engine internals.

### 51. EXAMPLE: CHROMATIC ABERRATION PLUGIN

*(Unchanged from v2.0 — illustrative, non-normative example, now understood to be built exactly like the first-party Chromatic Aberration effect of §37.1.)* Parameters: `amount, direction, angle, centerX, centerY, falloff, redOffset, greenOffset, blueOffset, blend, mask`. Reactive targets: `amount, angle, redOffset, greenOffset, blueOffset`. The plugin automatically receives Bass, Mid, Treble, Beat, Onset, Custom FFT Band, etc.

### 52. PLUGIN EFFECT EXAMPLE

*(Unchanged from v2.0.)* User installs `chromatic_aberration.arp`. Then `Effects → Chromatic Aberration` appears automatically. The host displays Amount, Direction, Angle, Center, Falloff, R/G/B Offset, Blend, and Audio Reactive (Source, Sensitivity, Attack, Release, Curve, Min, Max, Invert). No host source-code modification is required.

### 53. PLUGIN VISUALIZER

*(Unchanged from v2.0.)* A Visualizer Plugin may provide Spectrum, Waveform, Geometry, Shader, Particle system, or Procedural animation. It may create its own Layer type.

### 54. PLUGIN GENERATOR

*(Unchanged from v2.0.)* A Generator creates visual content without requiring source media. Examples: Fog, Starfield, Plasma, Clouds, Procedural Fire, Noise Field. Generator output is still a Layer.

> A Generator whose logic is purely a shader (no CPU-side state machine) is a **Tier 1 (Declarative)** plugin per §60.1. A Generator requiring genuine CPU-side procedural logic (e.g. a stateful noise-field simulation) is a **Tier 2 (WASM)** plugin per the same section.

### 55. PLUGIN AUDIO ANALYZER

An Analyzer Plugin may introduce new audio features. Example: Kick Detector → output `kickEnergy`. This immediately becomes available to every reactive parameter in the application. Another: Transient Detector → `transientStrength`. Another: Vocal Energy Detector → `vocalEnergy`.

> **[RATIFIED — Ref AR-1.2]** Analyzer Plugins are, without exception, **Tier 2 (Sandboxed WASM)** plugins per §60.1 — genuine audio-analysis algorithms cannot be expressed as a declarative shader/parameter manifest. See §56.1 for the binding Analyzer WASM ABI.

### 56. ANALYZER API

Analyzer plugin receives: `AudioBuffer, SampleRate, Timestamp, AnalysisContext`. It may expose `NamedFeature` (e.g. `kickEnergy: 0.0–1.0`). The Reactive Engine then treats it exactly like Bass, RMS, or Beat.

#### 56.1 Analyzer WASM Host ABI (Binding, Minimal, Capability-Typed)

> **[RATIFIED — Ref AR-16.3, AR-9.1]** New, mandatory, security-critical specification — this is the actual attack surface of the Analyzer plugin tier and must be treated with syscall-table rigor:
>
> - The host-function (import) surface exposed into an Analyzer's WASM linear memory sandbox is an explicit, minimal, **versioned, capability-typed** function table — never a generic `read_memory(ptr, len)` or a host-object callback pattern. Example shape: `get_audio_buffer(outPtr: u32, maxLen: u32) -> u32 (bytes written)`, `get_sample_rate() -> u32`, `emit_feature(name: FeatureId, value: f32)`. Every host function is individually documented and security-reviewed in PLUGIN_SECURITY.md before the Analyzer plugin type ships — the three functions above are illustrative, not the complete table; the complete, closed function table is part of the same Appendix B item U-19 gate described at §57, and Phase 7 must not begin Analyzer plugin implementation until it is authored and reviewed.
> - Each Analyzer WASM module instance is instantiated with a **hard linear-memory cap** (default 32MB), no filesystem/network/IPC/reflection capability of any kind, and a **fuel-limited (instruction-count-bounded) execution budget per invocation** — a runaway analyzer is killed (fuel exhausted) and that specific invocation fails gracefully (the Reactive Engine treats it like a momentary audio dropout: hold last value), never hanging the analysis or render thread.
> - See §63.1 for the mandatory validation stages this ABI must pass before an Analyzer plugin is certified, and §60.2 for the overall trust model this sits inside.

### 57. CUSTOM LAYER PLUGIN

Plugin may register a `LayerType` (e.g. "3D Sigil") defining parameters, render method, UI, serialization, reactive targets. The user then sees `Layers → 3D Sigil`.

> **[RATIFIED — Ref AR-1.2]** A Custom Layer Plugin whose render method requires genuine CPU-side logic (not purely a shader driven by declared parameters) is a **Tier 2 (WASM)** plugin per §60.1, using an ABI scoped to vertex/geometry/texture-descriptor output rather than audio features, following the same capability-typed, versioned, security-reviewed discipline as §56.1 — no new ABI may be added to the platform without following that discipline.
>
> **This ABI is not yet specified in this document and is a hard gate on Phase 7, not an implementation detail to be invented while coding.** Unlike §56.1's Analyzer ABI (which already gives concrete example host functions and resource limits), no function table exists here at all. See Appendix B, item U-19: Phase 7 (§135) must not begin implementing any Tier-2 Custom Layer or CPU-logic Generator plugin until a complete, versioned, capability-typed function table for this category has been authored and has passed the same security-review rigor §56.1 requires of the Analyzer ABI.

### 58. PLUGIN IMPORTER

*(Unchanged from v2.0.)* Import plugins may register new asset types. Examples: SVG, Lottie, Animated PNG, specialized animation.

### 59. PLUGIN EXPORTER

*(Unchanged from v2.0.)* Future exporters may provide GIF, WebM, specialized image sequences, other formats.

### 60. SECURITY MODEL

DO NOT use arbitrary runtime C#/Kotlin code loading as the default plugin mechanism. Do not implement `.cs → runtime compilation → arbitrary executable code → unrestricted Android access`. The extension system must be sandbox-oriented. Primary plugin model: declarative manifest + shader code + controlled extension API.

#### 60.1 Two-Tier Plugin Execution Model (Binding, Not Deferred)

> **[RATIFIED — Ref AR-1.2]** This resolves the contradiction between "no arbitrary code" and Analyzer/Layer plugins requiring real algorithms. v2.0's closing clause ("if arbitrary computational logic is eventually required, evaluate WASM") is **ratified as required now, for v1**, not deferred:
>
> - **Tier 1 — Declarative.** Effect and Visualizer plugins (and any Generator whose logic is purely shader-driven) are expressed *only* as a parameter schema (§49) plus shader code (GLSL, §89). No arbitrary host-callable logic. Validated entirely by manifest/schema/shader-static-analysis (§63, §16.2). Cannot escape the GPU sandbox by construction.
> - **Tier 2 — Sandboxed WASM.** Analyzer Plugins (always, §55), Layer Plugins whose render method needs genuine CPU logic (§57), and Generators with CPU-side logic (§54) run inside a WASM runtime (host integration e.g. via JNI to a Rust/C WASM engine) with **no filesystem, network, reflection, or Android system API access of any kind**, communicating with the host exclusively through the fixed, capability-typed, versioned host-function ABI defined per plugin category (§56.1 for Analyzers; an equivalent must be defined for Custom Layer/Generator plugins before that plugin type ships, following the same discipline).
>
> This is a mandatory v1 engineering commitment for the Plugin Runtime, not a "nice to have someday" — Analyzer and CPU-logic-bearing Layer/Generator plugins are **unimplementable** without it, and §55/§57's promises would otherwise be unfulfillable.

#### 60.2 Plugin Trust Model

> **[RATIFIED — Ref AR-16.1]** New, mandatory, binding statement: there is **no centrally-vetted plugin store implied anywhere in this specification.** The `.arp` manifest `signature` (§46) is integrity/authorship-continuity only (detects tampering, confirms update provenance) — it is explicitly **not** an authorization or review signal. **The actual security boundary is the sandbox**: Tier 1's shader-only/no-arbitrary-logic constraint, and Tier 2's WASM fuel/memory/capability limits with no `NETWORK` permission available to any plugin type (§61.1). The plugin-install UI must state this honestly to the user at install time, e.g.: *"This extension has not been reviewed by [publisher]; it runs in a restricted sandbox with no file, network, or system access."* Implying app-store-style vetting that does not exist is a defect.

### 61. PLUGIN PERMISSIONS

Plugins declare permissions. Examples: `GPU_RENDER, AUDIO_ANALYSIS, ASSET_READ, PROJECT_READ, PROJECT_WRITE, FILE_EXPORT`. **`NETWORK` has been removed from this list entirely — it is not a valid permission for any plugin type; see §61.1.** A plugin should receive only the capabilities it needs.

#### 61.1 NETWORK Removed From the v1 Permission Set

> **[RATIFIED — Ref AR-9.4]** `NETWORK` is **removed from the v1 permission set entirely** — it is not merely default-off, it does not exist as a grantable permission for any plugin type defined in this document (Effect, Visualizer, Generator, Analyzer, Layer, Importer, Exporter). No plugin type described in §43–§59 has a legitimate v1 use case for network access, and offering it as a checkbox permission alongside `GPU_RENDER` meaningfully and needlessly expands the attack surface of a system designed to run untrusted sideloaded content (contradicting the Privacy stance, §114). If a genuinely networked plugin use case emerges later (e.g. a cloud-render exporter), it requires a **distinct, separately-designed, heavily-scrutinized plugin class** with its own manifest signature/review requirements — not a re-added checkbox on the existing permission set. See Appendix B, item U-14.

### 62. PLUGIN SANDBOX

Plugin must NOT automatically receive: filesystem access, network access, microphone access, camera access, Android system APIs, shell access. Plugin interacts with the host through controlled interfaces.

> Concretized for Tier 2 (WASM) plugins by §56.1's capability-typed host ABI and §60.1's tier definitions; concretized for Tier 1 (declarative) plugins by §16.2's shader static-analysis and GPU watchdog requirements (§63.1).

### 63. PLUGIN VALIDATION

Installing a plugin triggers: `Manifest Validation → API Compatibility → Schema Validation → Dependency Validation → Shader Compilation → Parameter Validation → Sandbox Render → Memory Test → Performance Test → Regression Test → Installation`. Only valid plugins are activated.

#### 63.1 Fast-Path / Slow-Path Split and WASM Validation Stages

> **[RATIFIED — Ref AR-17.3, AR-9.1, AR-16.2]** This splits and extends the pipeline above into two mandatory phases, and adds WASM-specific stages:
>
> **Fast-path (synchronous, sub-second, blocks install completion):** Manifest Validation, API Compatibility, Schema Validation, Dependency Validation (transactional — §70.1), Shader Compilation, **Shader Static Analysis** (§16.2 — reject any GLSL loop without a compile-time-constant, host-enforced-maximum iteration bound), Parameter Validation (including the mandatory `unit` and quality/content-knob fields, §49), and — for Tier 2 plugins — **WASM ABI Conformance** (exported/imported function signatures match the declared entry points) and **Static Resource Limits** check (linear memory cap declared and within host maximum). A plugin passing fast-path is immediately installed and usable in a **`Validated (fast-path)`** state (§66.1).
>
> **Slow-path (asynchronous, runs after install, never blocks first use):** Sandbox Render (under a GPU watchdog timeout, §16.2 — a single draw call exceeding a bounded GPU time is treated as an automatic validation failure, not merely a later runtime quarantine trigger), Memory Test, Performance Test, Regression Test against fixture projects, and — for Tier 2 plugins — a **Fuel-Limited Execution Test** (run against fixture audio with a hard CPU-budget ceiling, §56.1) and a **Determinism Check** (identical input buffer twice → bit-identical output, a golden-test-style check specific to Analyzers, required because §9.1's determinism guarantee depends on it). A plugin passing slow-path is upgraded, silently, to **`Certified`** (§66.1); a plugin *failing* slow-path after already being used is downgraded with an explicit user-facing notice, never silently.

### 64. PLUGIN LABORATORY

*(Unchanged from v2.0.)* Internal developer feature: `Settings → Developer → Plugin Laboratory`. Actions: Import Plugin, Create Plugin Template, Validate Plugin, Run Tests, Render Test, Benchmark, Inspect Manifest, Inspect Parameters, Disable Plugin, Uninstall Plugin, Export Diagnostic Report.

### 65. PLUGIN TEST SUITE

*(Unchanged from v2.0.)* Every plugin should be able to contain `tests/`. Examples: `basic.json, reactive.json, edge_cases.json, performance.json`.

> Additionally exercises the §48.1 unknown-control-type fallback path and, for Tier 2 plugins, the §63.1 determinism-check fixture.

### 66. PLUGIN CERTIFICATION

Plugin states: `Installed, Validated, Certified, Disabled, Incompatible, Failed, Quarantined`. A Certified plugin has passed API, Shader, Render, Parameter, Memory, Performance, Serialization checks.

#### 66.1 Ratified State Machine

> **[RATIFIED — Ref AR-17.3]** The state list is refined to: `Installed → Validated (fast-path) → Certified (slow-path passed) → {Disabled | Incompatible | Failed | Quarantined}`, per §63.1's fast/slow split. A plugin is fully usable (all menus, all reactive targets) at `Validated (fast-path)`; `Certified` is a trust upgrade that happens silently in the background and is surfaced to the user only informationally (e.g. a badge in the Plugin Laboratory), never gating first use.

### 67. PLUGIN QUARANTINE

If a plugin repeatedly crashes the renderer or violates runtime constraints: automatically disable it. Do not allow one plugin to make the entire application unusable. On next launch: "Plugin X was disabled after repeated failures."

#### 67.1 Two-Phase Quarantine and Global, Transparent Failure Tracking

> **[RATIFIED — Ref AR-1.5, AR-3.3]**
>
> - **Two-phase quarantine:** (1) an **immediate soft-disable** — the offending plugin's render calls are skipped for the remainder of the current session/frame, and its layer/effect renders as a "Missing/Disabled" placeholder (visually and mechanically identical to the "Missing Plugin" placeholder of §72) — with **no GL context teardown attempted mid-frame**; (2) a **deferred hard rebuild** — at the next safe restart boundary (app relaunch, or an explicit user-triggered "Reload Renderer" action), the GL context is recreated cleanly per §8's context-loss-is-routine model, and the quarantined plugin is excluded from initialization. The system must never attempt to resurrect or partially reset a GL context mid-frame in response to a plugin fault.
> - **Failure tracking is global, per plugin id+version** (the plugin *code* is suspect, not any single project) — but full transparency is mandatory: the user-facing message names the failure count and every project/layer context where it occurred, e.g. *"Plugin X failed 3 times across 2 projects, most recently in 'Project A' at Layer 'Logo'."* A manual "re-enable and retry" action is always available. Quarantine is never silent or contextless.

### 68. PLUGIN VERSIONING

Plugin API is versioned. Example: `API 1.0, API 1.1, API 1.2, API 2.0`. Plugins declare minimum API, tested API. The application must not silently load incompatible plugins.

#### 68.1 SemVer Policy and Compatibility Regression Suite

> **[RATIFIED — Ref AR-3.4, AR-15.3]**
>
> - The Plugin API follows **SemVer**: MINOR and PATCH versions are strictly additive/non-breaking — any plugin certified against a given MAJOR.MINOR keeps working against every later MINOR/PATCH of the same MAJOR, forever, with no exceptions. MAJOR versions may break compatibility but must ship with (a) an automatic compatibility shim for at least N-1 prior major versions where mechanically possible, and (b) mandatory migration-note tooling for plugin authors. A plugin declares the `apiVersion` (major.minor) it targets; the host refuses to load a plugin whose declared major has no available shim.
> - **Plugin API Compatibility Regression Suite (mandatory, permanent, CI):** every certified plugin version ever released (including internal/first-party ones) is archived forever as a regression fixture. On every host release, CI re-runs the full validation + golden-render pipeline (§63.1) against every archived plugin version, flagging any newly-failing case as a host API regression to be fixed before release. This is the only mechanism that makes the "plugins keep working indefinitely" promise (§150–§156) actually enforced rather than aspirational.
> - The exact shim-support window (how many major versions, how many years) is a policy decision — see Appendix B, item U-4.

### 69. PLUGIN MIGRATION

If a plugin changes parameter IDs (e.g. `chromaticAmount` → `amount`), migration must be supported. The plugin provides `migration v1 → v2`. Projects must retain compatibility.

#### 69.1 Structural (Not Just Per-Parameter) Migration Hooks

> **[RATIFIED — Ref AR-9.3]** Per-parameter-id renaming is one instance of a broader requirement: **every plugin-contributed serializable type** (a Layer type, an Effect config, an Analyzer config — anything with structured, not-flat-scalar, persisted state) implements a `migrate(fromVersion, rawData) -> rawData` hook, declared in the manifest's `migrations` field (§46). The host invokes this hook during project load whenever the saved `pluginVersion` for a layer/effect/analyzer instance is older than the currently-installed plugin's version. This is validated by the same test-harness discipline as the app's own schema migration chain (§84, §10.2/AR). Without this, a Layer plugin author changing their own serialized shape between versions (a much larger blast radius than a renamed float) would silently corrupt or drop project data — this hook is mandatory, not optional, for any plugin with structured state.

### 70. PLUGIN DEPENDENCIES

Plugins may depend on another plugin, a minimum API, or specific renderer capabilities. Dependency graph must be validated before activation.

#### 70.1 Transactional Activation

> **[RATIFIED — Ref AR-9.2]** Plugin activation is **all-or-nothing (transactional)**: the full dependency closure required for a plugin being enabled is resolved and validated together. If any dependency in the closure fails validation, **none** of the closure activates — the user sees one clear error naming the specific failing dependency. Partial activation (some of a dependency closure active, some not) is explicitly forbidden, as it would silently violate the plugin-isolation guarantee (§80) even while appearing "not crashed."

### 71. PLUGIN UPDATE SAFETY

Before updating a plugin: backup current plugin. If new version fails: rollback. Projects must not become unreadable merely because a plugin was updated.

### 72. PLUGIN REMOVAL

If a project contains a removed plugin: project must NOT corrupt. Instead: `[Missing: Chromatic Aberration v1.2]` placeholder. The project remains editable.

> This placeholder mechanism is shared with (and visually/mechanically identical to) the quarantine soft-disable placeholder (§67.1) and the "Missing Asset" placeholder (§82.1) — one consistent placeholder pattern is used across all three "something referenced is currently unavailable" cases.

### 73. PLUGIN SOURCE DEVELOPMENT WORKFLOW

*(Unchanged from v2.0.)* Intended workflow: User request → Claude receives Plugin SDK → Claude creates plugin → static validation → tests → render test project → check output → benchmark → package plugin → User installs plugin.

### 74. PLUGIN SDK

Repository must contain `VisualizerPluginSDK/`: `API/, Examples/, Templates/, Documentation/, TestHarness/, CLI/`. Templates: `BasicEffect, AudioReactiveEffect, Visualizer, Generator, Analyzer, CustomLayer, Importer, Exporter`.

> **[RATIFIED — Ref AR-1.2]** The SDK's `Analyzer`, `CustomLayer` (where CPU logic is needed), and CPU-logic `Generator` templates target **Tier 2 (WASM)** per §60.1 from day one — they are not stubs awaiting a future WASM *tier* decision, which is ratified and final. **They are, however, stubs awaiting the WASM host ABI itself: no Tier-2 template in this SDK is ready to implement against until Appendix B item U-19 is resolved (§57, §56.1)** — the concrete, versioned, capability-typed function table these templates call does not yet exist, and must not be invented ad hoc while filling in a template. `BasicEffect`/`AudioReactiveEffect`/`Visualizer` templates target **Tier 1 (Declarative)** and are unaffected by U-19.

### 75. PLUGIN CLI

*(Unchanged from v2.0.)* Provide a development CLI. Commands conceptually: `plugin create, plugin validate, plugin test, plugin render, plugin benchmark, plugin package, plugin inspect`. This allows Claude to autonomously verify plugins.

### 76. PLUGIN TEST RENDER

*(Unchanged from v2.0.)* Every plugin must be renderable against deterministic fixture projects. Example: 320×180 for fast testing. Compare against expected output (see §120.1 for the ratified comparison methodology).

---

## PART V — TESTING, GOLDEN TESTS, ISOLATION

### 77. GOLDEN TEST SYSTEM

Create golden renders for: Basic Image, Two Layers, Independent Reactive Layers, Effects, Particles, Masks, Blend Modes, Keyframes, Plugin Effects, Plugin Visualizers, Plugin Analyzers.

#### 77.1 Test Tiering

> **[RATIFIED — Ref AR-15.1]** Golden tests are split into two mandatory, distinct tiers, which must never be conflated:
>
> - **CI Golden Tests:** run on a **pinned reference environment** (a specific emulator/CI device image with a fixed GPU/driver combination), compared via a **perceptual metric (SSIM or equivalent) with a documented per-test-category threshold** — never exact/bit-pixel match, since GPU rendering is not bit-exact across vendors/drivers even for identical shader code. Because rendering is deterministic (§9.1), tolerance only needs to absorb negligible floating-point noise on the fixed reference environment, not cross-device variance. These tests are release-blocking (§148).
> - **Device Matrix Smoke Tests:** run across the real/varied hardware of §122, checking crash-freedom, rough visual correctness, and performance — never held to the strict SSIM threshold used in CI.
>
> Exact SSIM threshold values per test category are set empirically during Phase 1–3 implementation — see Appendix B, item U-5.

### 78. CRITICAL LAYER ISOLATION TEST

Project: Background, Artwork, Logo, Spectrum. Mappings: Background → Bass, Artwork → Mid, Logo → Beat, Spectrum → Full FFT. Changing Logo mapping must not alter Background, Artwork, or Spectrum. **This test is permanent.**

### 79. CRITICAL EFFECT ISOLATION TEST

Layer A: Artwork. Layer B: Logo. Apply Glitch → A. Layer B output must remain unchanged.

### 80. CRITICAL PLUGIN ISOLATION TEST

Install Plugin A. Install Plugin B. Plugin A must not modify Plugin B state. Plugin A failure must not corrupt the project.

#### 80.1 Isolation Tests Are Permanent, Named, CI-Blocking Regression Tests

> **[RATIFIED — Ref AR-15.2]** §78, §79, and §80 above are not one-time manual verifications — they are **automated, permanent, named CI test cases** (`test_layer_isolation_golden`, `test_effect_isolation_golden`, `test_plugin_isolation_golden`), using the exact fixture projects from §141/§142, run on **every commit**, using the §77.1 CI Golden Test infrastructure, and are **release-blocking** per the Production Readiness Gate (§148). They are never removed, never skipped, and never downgraded to a manual checklist item — this is the single most architecturally load-bearing guarantee in the entire specification (§2), and it is tested continuously, not once.

---

## PART VI — PROJECT, RENDERER, PERFORMANCE, EXPORT

### 81. PROJECT FORMAT

Use versioned JSON-based project format. Example: `project { version, metadata, canvas, audio, assets, timeline, layers, analyzers, plugins, renderSettings }`.

> **[RATIFIED — Ref AR-10.1]** Confirms and binds: the project JSON **never** contains `AudioAnalysisCache` payloads or derived preview-only data (waveform peaks, video proxies) — see §18.1, §82.1. This keeps autosave (§83) latency bounded by JSON size alone, independent of audio track length.

### 82. ASSET REGISTRY

Assets identified by stable IDs. Never depend permanently on raw filesystem paths. Asset: `assetId, uri, type, metadata, duration, dimensions, hash, cache, decodeInfo`. Use Android Storage Access Framework appropriately.

#### 82.1 Missing-Asset Recovery and Three-Tier Caching

> **[RATIFIED — Ref AR-4.4, AR-14.1 (Review)]**
>
> - **Missing-asset recovery:** each `AssetRef` additionally tracks `persistedPermissionTaken: Boolean` and `lastKnownHash`. On project load, assets are validated **lazily** (on first actual use, not eagerly for the whole project). On failure (SAF permission revoked, file moved/deleted externally), the host shows a **"Missing Asset — Relink"** placeholder (mechanically consistent with §72's "Missing Plugin" and §67.1's quarantine placeholder) — never a crash, and the project remains otherwise editable.
> - **Three-tier asset caching (mandatory):** (1) the **original SAF-backed asset**, source of truth for identity/provenance, re-read only for full decode (playback, export); (2) a **derived preview-data cache** (waveform peaks from §16, video proxies from §34A, thumbnails) — decoded once at import, used for all fast interactive UI operations; (3) the **AudioAnalysisCache** (§18.1) — content-addressed, external, regenerable. Each tier has its own, independently documented invalidation rule; they are never conflated into a single cache.

### 83. PROJECT RECOVERY

Autosave: after significant changes, at interval, before export, before close. Maintain last valid project + recovery project. On crash: offer recovery.

> Autosave cost is bounded by JSON project size alone (§81's ratification) — analysis/derived caches are excluded and regenerated independently.

### 84. PROJECT MIGRATION

Projects contain `schemaVersion`. Migration chain: v1 → v2 → v3. Never silently break old projects.

#### 84.1 Mandatory Migration Golden-Render Tests

> **[RATIFIED — Ref AR-10.2]** For **every** `vN → vN+1` schema migration, CI must maintain a fixed fixture project saved under schema vN and render it (a) under the original vN-compatible renderer where still available in the test harness, and (b) after migration to vN+1 — then diff the two renders within the §77.1 CI Golden Test tolerance. This is a distinct, mandatory test category (**"Migration Golden Render"**), not implied by §77's generic golden tests — schema migrations are exactly the change most likely to silently alter rendered output (e.g. a changed default during migration), and this is the only mechanism that catches that class of regression before a user reopens a year-old project and finds it altered.

### 85. UNDO / REDO

Command-based. Commands: `AddLayer, DeleteLayer, MoveLayer, SetParameter, AddEffect, DeleteEffect, SetReactiveMapping, InstallPlugin, RemovePlugin`. Continuous gestures are one logical undo operation.

#### 85.1 Command-Coalescing Rule and RenderGraph Diff Protocol

> **[RATIFIED — Ref AR-8.3, AR-4.2]**
>
> - **Undo coalescing:** any sequence of `SetParameter` commands on the **same parameter path**, with no intervening command of a different type, occurring within a short debounce window (default 400ms of inactivity ends the coalescing group), merges into a single undo step. This generalizes "continuous gesture" (a drag) to any rapid same-target edits (repeated slider nudges, taps), giving uniform, predictable undo granularity.
> - **Commands double as the ProjectState→RenderGraph diff protocol:** the Renderer maintains a **derived `RenderGraph`** (a compiled, GPU-resource-bound representation, §8) that is **never rebuilt wholesale** except on project load — it is incrementally updated via the same Command stream that drives Undo/Redo. Every Command type (`AddLayer`, `SetParameter`, etc.) has a corresponding, well-defined RenderGraph patch operation. This is why Commands must be designed as a first-class, complete data model from Phase 1 — they are not merely a UI convenience feature bolted on later.
>
> **Scope guard for Phase 1.** "First-class from Phase 1" means the Command data model, its apply/invert contract, the in-memory undo stack, and the coalescing policy above exist and are tested in Phase 1. It does **not** authorize building, in Phase 1, any of: timeline editing (§134/Phase 6), the RenderGraph or any renderer (§130/Phase 2), reactive evaluation (§131/Phase 3), or product UI (§103). The Command model existing early is what lets those phases attach to it without a rewrite; it is not a licence to start them.

### 86. PREVIEW

Quality modes: Low, Medium, High, Ultra. Draft mode: reduced resolution, reduced particles, reduced expensive effects.

#### 86.1 Quality Knobs vs. Content Knobs (Binding Rule)

> **[RATIFIED — Ref AR-11.1]** This **replaces** "reduced particles" in the paragraph above, which is now understood to have been imprecise in v2.0 and is corrected here:
>
> - **Quality knobs** (safe to reduce in Draft/Low preview modes, always restored to full at export and in Ultra preview): internal render resolution scale, MSAA/supersampling level, expensive post-effect quality tiers (blur kernel size, bloom pass count, shadow/glow sample counts). These have a well-defined "same result, less precise" relationship to full quality.
> - **Content knobs** (must be **identical** across every preview quality mode and export — never silently reduced): particle **count**, any procedural/random **seed**, and any other value that changes *what is simulated* rather than *how precisely it is rendered*. Reducing these is not a quality reduction, it is rendering different content — the exact "looks correct in editor, different after export" failure §9 forbids.
> - If a content-knob-driven feature (e.g. particle count) is genuinely too expensive for a given device, the **only** permitted relief mechanism is the Adaptive Quality Degradation Ladder's explicit, user-visible, **manual** content-level project setting (§87.2) — affecting preview and export identically — never an implicit preview-only optimization applied without the user's knowledge.
> - Every effect/generator/particle-system parameter (built-in or plugin-declared) must carry this classification as mandatory metadata (§25, §49), checked by the Plugin Validator (§63.1) for plugins and by code review for built-ins.

### 87. PERFORMANCE

Target: 60 FPS preview on modern high-end Android devices. Frame budget: 16.67ms. Monitor: CPU time, GPU time, memory, draw calls, dropped frames.

#### 87.1 Dropped-Frame Determinism

> **[RATIFIED — Ref AR-11.2]** Because every modulator's value at a given timestamp T is a pure, precomputed lookup (§27.2's Resolved Modulation Cache), dropped preview frames mean only that fewer *samples* of an otherwise fixed trajectory are displayed — never a *different* trajectory. This must hold regardless of device performance, and is the reason §27.2's cache is a hard requirement, not a performance nicety.

#### 87.2 Adaptive Quality Degradation Ladder

> **[RATIFIED — Ref AR-12.1]** New, mandatory, explicit, ordered degradation policy (replacing any ad hoc/implicit per-effect degradation logic) — driven by hardware capability detection (§96) and live frame-time monitoring:
>
> 1. Internal render resolution scale (quality knob).
> 2. MSAA/post-effect quality tier (quality knob).
> 3. Content-level settings — **only via an explicit, manual, user-visible project setting** (never automatic, never silent; per §86.1, content knobs are never reduced without the user's explicit action). If the ladder reaches this step automatically, the system instead shows a one-time notice ("this project may not run smoothly on this device") with a manual override the user can choose to apply.
> 4. Frame-rate target itself (e.g. 30fps preview fallback) — last resort before uncontrolled dropped frames.
>
> The exact measured frame-time/device-tier thresholds that trigger each step are open — see Appendix B, item U-10. This ladder is a documented, tested (§121) subsystem — the **Adaptive Quality Controller** — not implicit per-effect logic scattered through the codebase.

### 88. GPU RESOURCE MANAGEMENT

Central managers: `TextureManager, FramebufferManager, ShaderManager, PipelineManager, BufferManager, ResourceCache`. Never create expensive GPU resources every frame.

#### 88.1 Resource Budget Enforced at Edit Time

> **[RATIFIED — Ref AR-5.3]** New, mandatory requirement: the Renderer enforces a **hard, configurable resource budget** — maximum concurrent FBOs, maximum texture memory, maximum effect-chain depth, maximum `GroupLayer` nesting depth (e.g. depth ≤ 8; exact numbers per device tier are open — see Appendix B, item U-9) — validated **at project-edit time** (the UI warns/blocks adding another effect or nesting another group once the budget is hit), not merely discovered as a runtime failure. `TextureManager`/`FramebufferManager` expose a budget-tracking query API the UI calls before permitting the action. Preview Quality mode (§86) scales this budget down further on lower-end hardware per §96.

#### 88.2 GL Command Queue (Cross-Thread Resource Mutation)

> **[RATIFIED — Ref AR-6.2]** New, mandatory requirement: **all GPU resource allocation/deallocation happens exclusively on the GL thread**, driven by a command queue that UI/edit actions post into — never a direct call into a GL object from another thread (Compose recomposition, ViewModel, background analysis). `ResourceCache` uses a generation-counted pool: FBOs sized to common resolutions are reused across layers rather than allocated per-layer-per-frame. This is an explicit ADR-level requirement: cross-thread renderer mutation is always via message queue, drained once per frame on the GL thread, never via direct object calls from other threads.

#### 88.3 Decode-for-Purpose Asset Policy

> **[RATIFIED — Ref AR-12.2]** New, mandatory requirement: an image/video asset is never GPU-uploaded at a resolution higher than `max(canvas resolution, export resolution) × a small headroom factor (e.g. 1.5×)` for **preview** purposes. A separate, full-resolution decode path is used only at actual **export** time, streamed rather than fully GPU-resident. `TextureManager` implements this as an explicit "decode-for-purpose" policy — preview-resolution and export-resolution are distinct asset variants, never a single decode-once-use-everywhere path.

### 89. SHADER SYSTEM

Shader must be externalized from business logic. Shader package includes: vertex, fragment, uniform metadata, parameter metadata. Shader parameters become reactive targets automatically when declared animatable.

> Shader static analysis and GPU watchdog requirements are specified in §16.2/§63.1 and apply to every shader, first-party and third-party alike.

### 90. COLOR MANAGEMENT

Support SDR. Architecture prepared for wide gamut/HDR. Preview/export color handling must be consistent.

#### 90.1 Linear-Light Compositing (Binding)

> **[RATIFIED — Ref AR-2.2]** New, mandatory requirement resolving an unstated ambiguity: all compositing (blend modes §38, gradients §40, keyframe color interpolation §41.1) happens in **linear light** internally — textures are converted sRGB→linear on sample, and the final composite is converted linear→sRGB on output write. This matches standard, correct compositing practice (Porter-Duff over linear color) and is required for blend modes to look industry-standard-correct rather than washed-out/too-dark. `FramebufferManager` must support float or at-least-10-bit intermediate targets to avoid banding; `ShaderManager` injects the standard sRGB↔linear conversion into every shader template automatically (plugin shaders do not need to implement this themselves — it is applied at the sample/output boundary by the host). HDR/wide-gamut remains a prepared-but-not-built future extension per the original v2.0 wording — see Appendix B, item U-15.

### 91. EXPORT FORMATS

Presets: `16:9 1920×1080, 16:9 3840×2160, 9:16 1080×1920, 1:1 1080×1080, 4:5 1080×1350`. Custom width/height/FPS. Frame rates: `24, 25, 30, 50, 60`. Video: `H.264, H.265/HEVC where device supports`. Audio: `AAC`.

### 92. EXPORT ENGINE

Export must support progress, ETA, cancel, background execution, retry, diagnostics.

> **[RATIFIED — Ref AR-1.1]** Per §7's ratification: Media3 Transformer may be used here **strictly as encode/mux infrastructure**, consuming already-composited frames from the RenderGraph — never as a compositing path.

#### 92.1 GPU-Surface Encoder Hand-Off; Foreground Service

> **[RATIFIED — Ref AR-13.3, AR-3.5]**
>
> - **Encoder hand-off:** export renders directly into the encoder's input `Surface` (`MediaCodec.createInputSurface()`, fed by an EGL context sharing the RenderGraph's GL context/resources) — a **GPU-to-GPU hand-off with no CPU readback** in the steady-state path. A CPU-readback-based path (e.g. via `ImageReader`/byte-buffer feed) would be catastrophically slow for 4K60 export and is not an acceptable implementation choice. This integration must be validated with a dedicated benchmark before Phase 8 (export) is considered complete.
> - **Foreground Service:** export runs inside an Android **Foreground Service** (appropriately typed, e.g. `dataSync`/`specialUse` per current platform policy at implementation time — see Appendix B, item U-6) with a **persistent progress notification** from the moment export starts. This is the only way to satisfy "background execution" (this section) together with "survive backgrounding/screen-off/config changes" (§95) on real Android without the process being killed mid-export.

### 93. EXPORT VALIDATION

After export: verify file, size, container, video stream, audio stream, duration, FPS, resolution, codec, synchronization. Only then: EXPORT COMPLETE.

### 94. EXPORT FAILURE

Never overwrite previous successful output. Use temporary output. If failed: retain diagnostics. Project remains intact.

#### 94.1 Bounded Retry Policy

> **[RATIFIED — Ref AR-13.2]** New, mandatory, bounded policy: export failure triggers **at most one automatic retry**, and only when the failure signature indicates a capability mismatch (e.g. the requested resolution/fps/codec combination is not actually sustainable, matching §96.1's pre-flight probe) — using a safely reduced configuration (e.g. drop to the hardware-confirmed-safe resolution/fps from §96.1). Failures with any other signature (disk full, permission error, corrupted source) are **never** auto-retried and surface immediately as a clear, categorized error (§97) with full diagnostics (§98) and a manual retry action. There is no unbounded or silent retry loop under any circumstance.

### 95. BACKGROUND EXPORT

Export must not depend on Activity lifetime. Use suitable Android background/foreground execution. Support screen off, application backgrounding, configuration changes.

> Satisfied concretely by the Foreground Service requirement of §92.1.

### 96. HARDWARE CAPABILITY DETECTION

Detect: GPU, OpenGL version, Vulkan support, encoders supported, resolutions supported, FPS, RAM, storage. If 4K60 unavailable: offer 4K30 or 1080p60.

#### 96.1 Export-Time Pre-Flight Probing (Not Just Launch-Time Detection)

> **[RATIFIED — Ref AR-13.1]** New, mandatory requirement: hardware capability detection for export must run a **`MediaCodecList`/`VideoCapabilities`-based pre-flight probe at export-configuration time**, not only once at app launch — encoder availability can be resource-contended by other running apps at the moment export actually starts. The Export Settings UI only ever offers resolution×fps×codec combinations the device can sustain **right now**, with degraded fallbacks (4K30, 1080p60) offered proactively — never silently attempted and allowed to fail mid-export.

### 97. ERROR MODEL

Categories: `IMPORT_ERROR, DECODER_ERROR, AUDIO_ANALYSIS_ERROR, GPU_ERROR, SHADER_ERROR, OUT_OF_MEMORY, EXPORT_ERROR, CODEC_ERROR, PROJECT_CORRUPTION, PERMISSION_ERROR, PLUGIN_ERROR, PLUGIN_INCOMPATIBILITY, PLUGIN_VALIDATION_ERROR, UNSUPPORTED_FORMAT`. Never display only "Something went wrong."

> Every export/plugin/asset failure path specified elsewhere in this document (§67.1, §82.1, §94.1) maps to one of these categories explicitly — no failure path in this specification is permitted to bypass this taxonomy with a generic message.

### 98. DIAGNOSTICS

Developer diagnostics: Device, Android version, GPU, RAM, Storage, Renderer, Codec, Project complexity, Layers, Effects, Plugins, Audio duration, Analysis cache, FPS, GPU time, CPU time, Memory. Exportable diagnostic report.

> **[RATIFIED — Ref AR-19.5]** The Diagnostics architecture reserves (but does not activate by default) an **opt-in, off-by-default event-reporting hook** for aggregate crash/plugin-failure telemetry beyond a single device — consistent with the Privacy stance (§114: no upload without explicit future opt-in). This hook is designed now so it is not a bolt-on retrofit later, but it collects and transmits nothing unless a user explicitly opts in. See Appendix B, item U-7, for scheduling its activation.

#### 98.1 Memory Pressure Response

> **[RATIFIED — Ref AR-14.2]** New, mandatory requirement, entirely absent from v2.0: Renderer and Audio caches subscribe to `ComponentCallbacks2.onTrimMemory` and respond proportionally — releasing non-visible-layer GPU resources and prefetched-but-not-yet-needed analysis cache pages first (cheap to regenerate), and **never** releasing the active `ProjectState`/undo history (cheap to keep, catastrophic to lose). This is a basic Android platform-citizenship requirement for a "production-grade" tool and must not be left unaddressed.

### 99. LOGGING

*(Unchanged from v2.0.)* Structured logging. Subsystems: Renderer, Audio, Analyzer, Reactive, Project, Assets, Plugins, Exporter. Production logging must be rate-limited.

### 100. DEBUG OVERLAY

Show FPS, frame time, GPU time, CPU time, audio time, playhead time, layer count, draw calls, texture count, memory.

#### 100.1 Per-Subsystem CPU Time Breakdown

> **[RATIFIED — Ref AR-12.3]** New, mandatory requirement: CPU time is broken down **per subsystem** (indicative split within the 16.67ms budget: Reactive Engine evaluation ≤2ms, RenderGraph diff/command-build ≤2ms, GL driver submission ≤2ms, remainder for GPU-bound wait/vsync — this split is provisional, see Appendix B, item U-11), shown as **separate bars in the Debug Overlay**, not a single aggregate "CPU time" number. Each subsystem self-instruments with named timing spans from day one — this is required so a performance regression is diagnosed against the correct subsystem rather than "fixed" by degrading the wrong one (e.g. cutting GPU quality when the actual bottleneck is Reactive Engine evaluation of many chained mappings).

### 101. THREADING

Separate: UI, Audio, Analysis, Decode, Render, Export. No arbitrary mutable shared state. Use explicit state/message boundaries.

#### 101.1 Concurrency Contract Cross-Reference

> **[RATIFIED — Ref AR-6.1, AR-6.2]** The two concrete, binding concurrency mechanisms required by this section are: the lock-free, append-only `AudioAnalysisCache` (§18.1) for Audio↔Render cross-thread reads, and the GL command queue (§88.2) for any cross-thread GPU resource mutation. No other ad hoc cross-thread sharing mechanism (shared mutable maps, direct object calls across threads) is permitted anywhere in the codebase.

### 102. STATE MANAGEMENT

Unidirectional: `UI Action → ViewModel/Controller → Domain Command → ProjectState → RendererState → GPU`.

#### 102.1 Renderer State Is a Derived Cache, Never the Source of Truth

> **[RATIFIED — Ref AR-4.2]** `RendererState` in the diagram above is precisely defined: it is a **derived `RenderGraph`** (§8, §85.1) — a compiled, GPU-resource-bound representation incrementally updated via the Command diff/patch protocol (§85.1). `ProjectState` is always the sole durable source of truth; `RenderGraph` can always be fully regenerated from it (this is also why context loss, §8, is safely recoverable). `RendererState` is never rebuilt wholesale on every frame (that would be a severe, needless performance cost) and never diverges from what `ProjectState` would produce, by construction of the diff protocol.

---

## PART VII — UI, TEMPLATES, PROJECT MANAGEMENT

### 103. UI

Professional creative-tool appearance. Phone: Preview, Inspector, Layers, Timeline. Tablet: Preview, Layer stack, Inspector, Timeline simultaneously.

#### 103.1 Accessibility (Mandatory NFR)

> **[RATIFIED — Ref AR-19.4]** New, mandatory non-functional requirement, absent from v2.0: the editing UI (not the audio-reactive *content* it produces, which is exempt) must meet baseline Android accessibility expectations — semantic labeling for Compose UI elements (screen-reader support), minimum touch-target sizing, contrast-compliant chrome consistent with the "professional creative-tool appearance" goal. The specific conformance target/guideline level is a policy decision — see Appendix B, item U-8.

### 104. LAYER PANEL

*(Unchanged from v2.0.)* Each layer: eye, lock, thumbnail, name, type, reactive indicator, selection.

### 105. REACTIVE UI

Example: SCALE, Source: [Bass], Range: 0.85–1.15, Sensitivity, Smoothness, Attack, Release, Curve, Threshold, Invert. Advanced: multiple modulators.

> Per §27.1, the "multiple modulators" advanced view must expose ordering and each mapping's `combineOp`. Per §21's ratification, mappings whose source has sustained low reliability show a persistent visible indicator.

### 106. GLOBAL AUDIO PANEL

*(Unchanged from v2.0.)* Master: Sensitivity, Normalization, FFT size, Smoothing, Beat detection, Analysis quality.

### 107. BEGINNER MODE

Expose: Sensitivity, Smoothness, Bass, Mid, Treble, Beat.

> Per §21's ratification, Beat is presented identically to other sources here — with the same low-confidence indicator behavior applied uniformly, not a special-cased "supplementary" treatment.

### 108. POWER USER MODE

*(Unchanged from v2.0.)* Expose: FFT size, Custom bands, Attack, Release, Response curves, Custom modulation, Shader parameters, Blend modes, Render scaling.

### 109. SOCIAL PRESETS

*(Unchanged from v2.0.)* YouTube, TikTok, Reels, Shorts, Instagram, Spotify Canvas. Provide safe-area guides for vertical platforms.

### 110. TEMPLATES

Templates are ordinary project presets. Examples: Single Artwork + Spectrum, Artwork + Circular Spectrum, Dark Ambient, Witch House, Industrial, Minimal Vertical, Square YouTube. Templates must not create hard-coded special rendering logic.

#### 110.1 Templates Are Plain Serialized Projects (No Separate Subsystem)

> **[RATIFIED — Ref AR-18.2]** This confirms and makes binding what v2.0 already stated in principle: Templates require **no dedicated "Template" subsystem** distinct from ordinary Project file management (§81). A Template is simply a serialized project file (using every layer/effect referenced within it as first-party plugins per §30.1/§37.1) presented through a curated "start from" gallery in the UI. This removes an entire nominal subsystem from the Core Engine list (§5) — there is no `TemplateEngine`.

### 111. PROJECT SCREEN

*(Unchanged from v2.0.)* Cards: thumbnail, name, duration, resolution, last modified. Actions: open, duplicate, rename, delete, export, share, backup.

> Backup/share/export bundles never include analysis cache or derived preview data (§18.1, §81).

### 112. PROJECT DUPLICATION

*(Unchanged from v2.0.)* Duplicate receives new project ID. Assets may be shared safely. Original must remain untouched.

### 113. OFFLINE-FIRST

*(Unchanged from v2.0.)* Core functionality must work offline. No cloud requirement for audio analysis, preview rendering, export, project storage.

### 114. PRIVACY

Do not upload user music, images, projects, or videos without explicit future opt-in functionality.

> Directly reinforced by: `NETWORK` permission removed from all v1 plugin types (§61.1), and the opt-in-only, off-by-default telemetry hook (§98).

### 115. LICENSE SAFETY

*(Unchanged from v2.0.)* Do not copy competitor proprietary assets or code. Every bundled dependency and asset must have compatible licensing.

---

## PART VIII — CODE ORGANIZATION, DEPENDENCY RULES, PERFORMANCE STRATEGY

### 116. CODE ORGANIZATION

> **[RATIFIED — Ref AR-18.1]** This corrects v2.0's module list, which mixed engine-native and first-party-plugin layer/effect types under one undifferentiated `layers/`/`effects/` grouping — inconsistent with the core-vs-plugin split ratified at §12.1/§30.1/§37.1, and load-bearing here because Phase 1 (§129) establishes the enforced module graph from this list:
>
> Suggested modules: `app/`, `core/{model, project, assets, time, diagnostics}`, `audio/{decoder, playback, analysis, cache, beat}`, `reactive/{mapping, envelope, curves, modulation}`, `renderer/{core, backend, opengl, shaders, textures, framebuffers, compositor}`, `layers/{image, video, text, shape, gradient, solidcolor, group}` **(engine-native only, per §12.1 — never a home for spectrum/waveform/oscilloscope/particles/shader visualizers)**, `plugins/system/{spectrum, waveform, oscilloscope, particles, shader-effects}` **(first-party plugins, per §30.1/§37.1, built on `plugins/api` exactly like third-party visualizer/effect plugins — blur, glow, glitch, chromatic-aberration, distortion, kaleidoscope, noise, shake, and every other §37 effect live here too, not in a separate `effects/` core module)**, `timeline/`, `export/`, `plugins/{api, runtime, registry, validator, sandbox, laboratory, sdk}`, `ui/`, `testing/{audio, renderer, project, plugins, export, performance, golden}`.

#### 116.1 Enforced Module Dependency Graph

> **[RATIFIED — Ref AR-5.1]** New, mandatory requirement: the module list above is now a **strictly layered, enforced dependency graph**, checked by build-system visibility rules (Gradle module `api`/`implementation` boundaries) and/or a CI lint check that fails the build on a disallowed import:
>
> ```
> core/model  (no Android/GL deps)
>   → audio (decoder, playback, analysis, cache)
>   → reactive (mapping, envelope, curves, modulation, resolver)
>   → renderer/core, renderer/backend(gl), shaders, textures, framebuffers, compositor
>   → layers/{image, video, text, shape, gradient, solidcolor, group} (engine-native only, §12.1)
>   → plugins/api (public contract)  ◄── plugins/runtime, registry, validator, sandbox(wasm)
>   → plugins/system/{spectrum, waveform, oscilloscope, particles, shader-effects, ...} (first-party plugins on plugins/api, per §30.1/§37.1 — mechanistically identical to third-party plugins, never a separate core `effects/` module)
>   → timeline/, export/
>   → ui/ (Compose)  ── plugins/laboratory (dev UI)
> ```
>
> **No back-edges are permitted.** `plugins/runtime`-loaded code (declarative or WASM) never imports `renderer.opengl` or `audio.analysis` internals — only `plugins/api`. This enforced boundary is established **before Phase 1 code lands**, not retrofitted later — it is the concrete mechanism that prevents the "gradual erosion into a monolith" failure mode §157 warns against.

### 117. DEPENDENCY RULE

UI cannot directly manipulate GPU resources. Plugins cannot directly manipulate core internal state. Renderer owns GPU resources. Project owns persistent state. Reactive Engine owns modulation. Audio Engine owns analysis. Export Engine owns encoding. Plugin Runtime mediates extensions.

> Enforced concretely by §116.1's dependency graph and §88.2's GL command queue.

### 118. TESTING REQUIREMENT

Testing is a first-class product subsystem. Required: Unit, Integration, Renderer, Audio, Project, Migration, Plugin, Export, Performance, UI, Regression, Golden Render.

> **Migration** testing is concretized by §84.1's mandatory Migration Golden-Render suite; **Golden Render** is concretized by §77.1's CI/device-matrix tiering; **Plugin** testing is concretized by §68.1's Compatibility Regression Suite.

### 119. AUDIO TEST FIXTURES

*(Unchanged from v2.0.)* Test: silence, sine, bass sweep, white noise, impulse, drums, stereo, clipping, very quiet signal. Verify expected analysis ranges.

### 120. RENDER GOLDEN TESTS

Render deterministic scenes at 320×180. Compare against golden reference with defined tolerance. Detect shader errors, layer-order errors, blend errors, transform errors, reactive errors, plugin errors.

#### 120.1 Comparison Methodology

> **[RATIFIED — Ref AR-15.1]** The comparison methodology is precisely the one defined in §77.1: SSIM (or equivalent perceptual metric), pinned reference environment, per-category threshold — never exact/bit-pixel match. This section's "defined tolerance" is now bound to that definition rather than left unspecified.

### 121. PERFORMANCE REGRESSION

*(Unchanged from v2.0.)* Every major feature must benchmark before/after. If performance regresses materially: diagnose before continuing.

> Exercises the §100.1 per-subsystem CPU breakdown and the §87.2 Adaptive Quality Controller's behavior under regression.

### 122. DEVICE MATRIX

Test conceptually: high-end Snapdragon, mid-range Snapdragon, low-end Android, Pixel-class, Samsung Galaxy-class tablet. The exact current-year SoC/RAM-tier/tablet-model list to standardize on is open — see Appendix B, item U-17.

> This is the **Device Matrix Smoke Test** tier defined in §77.1 — distinct from, and looser than, the CI Golden Test tier.

### 123. NO PERFORMANCE HACK STACK

*(Unchanged from v2.0.)* If a subsystem repeatedly requires patches: STOP. Review architecture. Refactor root cause. Do not accumulate workaround after workaround.

### 124. ROOT-CAUSE PROTOCOL

*(Unchanged from v2.0.)* When a bug is reported: Reproduce → Inspect diagnostics → Identify subsystem → Create regression test → Determine root cause → Fix root cause → Run targeted tests → Run relevant full regression → Verify performance → Document. Do NOT immediately modify random code.

### 125. CLAUDE MUST SELF-DIAGNOSE

*(Unchanged from v2.0.)* The user is NOT the primary QA engineer. Claude must autonomously compile, test, render, benchmark, inspect logs, reproduce, diagnose, repair, regression-test. User primarily provides creative validation, visual quality feedback, workflow feedback.

### 126. CLAUDE DEVELOPMENT DOCUMENTATION

Maintain: `PROJECT_STATUS.md, ARCHITECTURE.md, TEST_PLAN.md, RENDERING_NOTES.md, PROJECT_FORMAT.md, PLUGIN_API.md, PLUGIN_SECURITY.md, PERFORMANCE.md, EXPORT.md, KNOWN_ISSUES.md`.

> `PLUGIN_API.md` must document the SemVer/shim policy (§68.1), the Tier 1/Tier 2 execution model (§60.1), and every WASM host ABI (§56.1 and its Layer/Generator analogues) with syscall-table rigor. `PLUGIN_SECURITY.md` must document the trust model (§60.2) and shader static-analysis rules (§16.2).

### 127. ADR SYSTEM

Architecture Decision Records: `ADR-001 Renderer, ADR-002 Audio Analysis, ADR-003 Reactive Engine, ADR-004 Layer Model, ADR-005 Project Format, ADR-006 Export, ADR-007 Plugin System, ADR-008 Plugin Security, ADR-009 Plugin UI, ADR-010 Plugin Runtime, ADR-011 GPU Backend, ADR-012 Testing Strategy`.

> ADR-011 (GPU Backend) is explicitly reserved for the future `VulkanBackend` decision per §6's ratification — it records a boundary-discipline decision now and remains open for the actual second-backend decision later. Confirmation that this deferral still stands (no v1/near-term Vulkan commitment) is tracked as Appendix B, item U-18.

---

## PART IX — PHASES

### 128. DEVELOPMENT PHASE 0

Architecture only. Do not build the complete UI. Produce: architecture, module graph, data model, render graph, audio graph, reactive model, plugin model, project schema, test architecture, export architecture, performance strategy, risk register.

> **This document (v3.0) constitutes the completion of Phase 0**, together with ARCHITECTURE_REVIEW.md as its supporting analysis. Phase 1 may not begin until every item in Appendix B is either resolved or explicitly deferred with an owner and a target phase.

### 129. PHASE 1

Audio: import, decode, playback, waveform, trim, analysis, cache.

> Per §20.O of the Review: also establishes `core/model`, the module dependency-boundary CI check (§116.1), DI setup, and the Coordinate/Color/Clock decisions (§13.1, §90.1, §14.1) as testable primitives **before any UI is built**.
>
> **Mandatory Phase 1 deliverables, stated explicitly so they cannot be read as optional:**
> - **Coordinate (§13.1), Color (§90.1), and Clock (§14.1) implemented as testable primitives** — pure functions with unit tests (logical-unit conversion and `pixelsPerLogicalUnit`; sRGB↔linear conversion; the `TimelineTime`/`AudioSourceTime` domains and `TrimMapping`). Two of the three are renderer-facing, and no renderer exists in Phase 1 — they are still required here, precisely so Phase 2 inherits them already proven rather than inventing them under deadline.
> - **The §85.1 Command data model as a first-class citizen**, subject to §85.1's scope guard: the model, not the timeline, renderer, reactive evaluation, or product UI.
> - The §116.1 module dependency-boundary check, landing **before** other Phase 1 code.
> - The canonical analysis contracts ratified in §17.3, §17.4, §18.2, and §18.3.
>
> **Blocked by:** nothing. U-1 (DI framework) and U-2 (minimum Android API level) are **RESOLVED** — Hilt; API 35 (Android 15) minimum, `compileSdk`/`targetSdk` 36 — see §6.1. U-21 (canonical analysis hop) is **RESOLVED** — 2048-sample window, 480-sample hop, 100 Hz native (§17.5). U-5 (exact SSIM thresholds) targets this phase's test infrastructure but does not block starting it — thresholds are refined empirically once real renders exist. **Phase 1 has no unresolved blockers, hard or scoped.**

### 130. PHASE 2

Basic compositor: images, layers, transforms, opacity, blend modes, preview.

> Scoped to the **engine-native layer set only** (§12.1: Image, Video, Text, Shape, Gradient, SolidColor, Group). Includes the GL resource-queue architecture (§88.2) and context-loss recovery path (§8) from the start.

### 131. PHASE 3

Reactive engine: FFT bands, RMS, peak, beat, mapping, smoothing, attack/release, curves.

> Includes the `ParameterResolver` (§22.1), Resolved Modulation Cache (§27.2), source deduplication (§22.2), and pure-function envelope/peak-gravity implementations (§9.1) — **validated against the layer-isolation golden test (§78/§80.1) as this phase's exit criterion**, not merely "features implemented."
>
> **Blocked by:** U-9 (GPU resource budget numbers — provisional defaults permitted from Phase 2 onward, full hardware survey targeted at this phase) and U-11 (per-subsystem CPU budget split — provisional split given at §100.1, finalized at Phase 10). Neither blocks starting this phase; both must use the provisional values in §88.1/§100.1 until resolved.

### 132. PHASE 4

Visualizers: spectrum, waveform, oscilloscope, particles.

> Built as the **first real exercise of the Plugin API** (§30.1) — this phase and Phase 5 below are, per the Review, effectively merged: built-in visualizers/effects are simultaneously "Phase 4/5 features" and "the first system plugins," forcing the Plugin API to be correct before any third-party plugin exists.

### 133. PHASE 5

Effect engine: blur, glow, glitch, chromatic aberration, distortion, kaleidoscope, noise, shake, color.

> Same ratified treatment as Phase 4 (§37.1) — first-party Effect Plugins on the public API.

### 134. PHASE 6

Timeline and keyframes.

> Includes command-coalescing (§85.1) and the ratified keyframe/reactive composition order (§27.1, §41.1).

### 135. PHASE 7

Plugin Platform. Implement: Plugin API, Manifest, Registry, Loader, Validator, UI Schema, Shader system, Sandbox, Test Runner, Benchmark, Packaging, Versioning, Migration, Quarantine.

> Generalizes the Tier 1 (Declarative) model already exercised in Phases 4–5, and **adds the Tier 2 (WASM) execution model** (§60.1) with its sandbox, validator stages (§63.1), and host ABI (§56.1) for Analyzer/Layer/Generator-with-logic plugins. Includes the two-phase quarantine model (§67.1), transactional dependency activation (§70.1), and SemVer/shim policy (§68.1). **Hard gate:** per §57 and Appendix B item U-19, the complete Analyzer host-function table and the entire Custom Layer/Generator WASM ABI must be authored and security-reviewed before this phase begins any Tier-2 plugin implementation — neither may be designed ad hoc during coding. Per Appendix B item U-20, the Plugin UI Schema's declarative grammar (§47/§48) should also be finalized before this phase's UI-schema-generation work begins.

### 136. PHASE 8

Export: 1080p, 4K, vertical, square, custom, H.264, HEVC where supported, AAC.

> Includes capability pre-flight probing (§96.1), Foreground Service (§92.1), GPU-surface encoder hand-off (§92.1), and the bounded retry policy (§94.1).
>
> **Blocked by:** U-6 (Foreground Service type classification — must be verified against current Android platform policy before the export Foreground Service, §92.1, is implemented).

### 137. PHASE 9

Project management: save, autosave, recovery, migration, backup, duplicate.

> Includes the mandatory Migration Golden-Render suite (§84.1) and the three-tier asset caching model (§82.1).

### 138. PHASE 10

Performance hardening.

> Includes the Adaptive Quality Degradation Ladder (§87.2), decode-for-purpose asset policy (§88.3), video proxy workflow (§34A), and per-subsystem CPU budgeting (§100.1).
>
> **Blocked by:** U-9 (GPU resource budget numbers — finalized here, provisional since Phase 2), U-10 (Adaptive Quality Degradation Ladder's exact trigger thresholds), and U-11 (per-subsystem CPU budget split — finalized here, provisional since Phase 3). This phase is where all three provisional values from earlier phases must be replaced with real, device-matrix-derived numbers.

### 139. PHASE 11

Production QA.

> Includes the full Production Readiness Gate (§148) and the Plugin API Compatibility Regression Suite (§68.1) running against the full archived plugin corpus.
>
> **Blocked by:** U-17 (exact device-matrix hardware list — needed to run the Device Matrix Smoke Tests, §77.1/§122, this phase depends on).

### 140. DO NOT IMPLEMENT EVERYTHING AT ONCE

The first working milestone must prove: Audio + One image layer + Second independent image layer + Audio analysis + Reactive mapping + GPU rendering + Export. Only after this architecture is proven should additional effects proliferate.

---

## PART X — GOLDEN PROJECTS, DEFINITIONS OF DONE, FINAL WORKFLOW

### 141. FIRST GOLDEN PROJECT

Create: Background, Artwork, Logo, Spectrum, Waveform, Dust, Text. Mappings: Background → Bass, Artwork → Mid, Logo → Beat, Spectrum → Full FFT, Dust → High, Text → Onset. Add: Glow, Blur, Glitch. This project becomes the main regression fixture, and is the fixture used by `test_layer_isolation_golden` and `test_effect_isolation_golden` (§80.1).

### 142. SECOND GOLDEN PROJECT

Plugin test: Chromatic Aberration Plugin. Use: Background, Artwork, Logo. Apply plugin only to Artwork. Verify: Background unchanged, Logo unchanged. This is the fixture used by `test_plugin_isolation_golden` (§80.1).

### 143. THIRD GOLDEN PROJECT

Analyzer plugin: Kick Detector. Mapping: Kick Detector → Logo Scale. Verify: new analyzer output appears automatically in every compatible reactive parameter. Exercises the Tier 2 WASM Analyzer path (§60.1, §56.1) and the §63.1 determinism check end-to-end.

### 144. FOURTH GOLDEN PROJECT

Custom Layer Plugin: 3D Sigil. Verify: layer appears in Add Layer; it has custom UI, custom parameters, audio reactivity, serialization, export.

> Per §12.1's ratification (Ref AR-18.3), this must be built with **zero special-casing in core** — if it were secretly hard-coded, this golden test would not be testing what it claims to.

### 145. FIFTH GOLDEN PROJECT

Plugin failure. Install intentionally faulty plugin. Expected: validation failure, plugin quarantined, host remains stable, project remains intact.

> Exercises the fast-path validation rejection (§63.1) and, for a plugin that passes fast-path but fails at runtime, the two-phase quarantine model (§67.1) end-to-end.

### 146. DEFINITION OF DONE — CORE

Core feature is DONE only when: implementation, UI, serialization, tests, regression, performance, error handling, documentation, migration consideration, export verification are complete.

### 147. DEFINITION OF DONE — PLUGIN

Plugin is DONE only when: manifest valid, API compatible, UI schema valid, shader compiles, parameters valid, reactive mappings work, serialization works, test fixtures pass, golden render passes, performance acceptable, memory acceptable, package valid, installation works, uninstallation safe, version metadata correct.

> Additionally, for Tier 2 plugins: WASM ABI conformance passes, fuel-limited execution test passes, determinism check passes (§63.1).

### 148. PRODUCTION READINESS GATE

All must pass: BUILD, UNIT TESTS, INTEGRATION TESTS, AUDIO TESTS, RENDER TESTS, PLUGIN TESTS, EXPORT TESTS, PROJECT RECOVERY, MEMORY TESTS, PERFORMANCE TESTS, DEVICE TESTS, REGRESSION TESTS.

> **[RATIFIED — Ref AR-15.2, AR-15.3, AR-10.2]** This gate additionally, explicitly requires: `test_layer_isolation_golden`, `test_effect_isolation_golden`, `test_plugin_isolation_golden` (§80.1) passing; the Migration Golden-Render suite (§84.1) passing for every historical schema version; and the Plugin API Compatibility Regression Suite (§68.1) showing no newly-failing archived plugin version.

### 149. FINAL USER WORKFLOW

*(Unchanged from v2.0.)* User opens application. Creates 9:16 project. Imports song. Trims song visually. Adds background image, foreground image, logo, spectrum, waveform, particles. Configures: Background: Bass → Scale; Foreground: Mid → Rotation; Logo: Beat → Scale; Particles: High → Emission; Spectrum: Full FFT → Height. Adds Glow, Chromatic Aberration, Glitch. Every component reacts independently. User exports. Receives high-quality MP4 with synchronized audio.

### 150. FUTURE EXTENSION EXAMPLE

*(Unchanged from v2.0.)* Application does not contain Chromatic Aberration. User opens Settings → Extensions → Plugin Laboratory → Install. Imports `chromatic_aberration.arp`. Application validates, compiles, runs tests, benchmarks, certifies. Now Effects → Chromatic Aberration exists. No APK rebuild. No host source modification. No project migration unless required.

> Per §63.1's ratified fast/slow split, the plugin is **immediately usable** after fast-path validation (`Validated`), with `Certified` following asynchronously — the workflow narrative above ("validates, compiles, runs tests, benchmarks, certifies") is understood to span both phases, with usability granted at the earlier one.

### 151. FUTURE CLAUDE WORKFLOW

*(Unchanged from v2.0.)* User asks Claude: "Create a CRT VHS effect for this application." Claude must: read PLUGIN_API.md; read current API version; generate plugin template; implement shader; define parameters (including `unit` and quality/content-knob classification per §13.1/§25/§49); define reactive targets; define UI schema; create tests; create golden fixture; compile; run tests; render; inspect render; benchmark; fix failures; package; provide plugin artifact. The user should not need to manually debug basic plugin implementation.

### 152. FUTURE CLAUDE ANALYZER WORKFLOW

*(Unchanged from v2.0, now with binding mechanism.)* User asks: "Create a kick detector I can use as an audio-reactive source." Claude must create an Analyzer Plugin (Tier 2 WASM per §60.1, using the ABI of §56.1). Output: `kickEnergy`. After installation: Reactive Source "Kick Energy" automatically appears. Any compatible layer can use it.

### 153. FUTURE CLAUDE LAYER WORKFLOW

*(Unchanged from v2.0.)* User asks: "Create a procedural fog layer." Claude creates a Generator Plugin (Tier 1 if purely shader-driven, Tier 2 if it needs CPU-side simulation state, per §60.1). After installation: Add Layer → Generators → Procedural Fog. Parameters: density, speed, direction, noise, opacity, color, depth. Every animatable parameter is automatically reactive (§50.1).

### 154. EXTENSION PRINCIPLE

*(Unchanged from v2.0.)* The host application must provide CAPABILITY: Audio, Time, Render context, Parameters, Reactive engine, Assets, Project persistence, Testing, Diagnostics. Plugins provide IMPLEMENTATION: new creative functionality.

### 155. ABSOLUTE ANTI-COUPLING RULE

Do not allow: Effect A to know about Layer B; Plugin A to directly modify Plugin B; UI to directly modify GPU; Audio analyzer to directly modify visual layer. Everything communicates through defined interfaces.

> Enforced concretely by: `ParameterResolver` (§22.1), the write-only plugin injection contract (§50.1), the module dependency graph (§116.1), and the GL command queue (§88.2).

### 156. EXTENSION FUTURE-PROOFING

*(Unchanged from v2.0.)* The architecture must eventually permit custom shaders, analyzers, visualizers, particle systems, generators, layer types, importers, exporters, modulation sources, modulation operators. The core should not require redesign for these.

### 157. WHAT NOT TO DO

DO NOT: create a giant monolithic Activity; put renderer logic inside Compose; put FFT calculations inside individual layers; duplicate audio analysis per effect; hard-code every effect into the host; require APK rebuild for new effects; load arbitrary untrusted executable code; use global mutable state; make preview and export separate render implementations; silently discard unsupported plugin state; use random patches instead of root-cause fixes; rely on the user to discover basic regressions.

#### 157.1 "No Global Mutable State" — Precise, Enforceable Restatement

> **[RATIFIED — Ref AR-17.2]** The bullet "use global mutable state" is refined because, taken absolutely, it is unsatisfiable on Android (an `EGLContext`, an `AudioTrack`, a `MediaCodec` surface are unavoidably singleton-shaped platform resources) and an unenforceable absolute rule trains engineers to ignore architectural rules generally. The binding rule is: **no ad hoc, implicit, untyped global mutable state** (e.g. static mutable maps used as an informal event bus or cache). A small, explicit, **documented, dependency-injected** set of owned platform singletons (one `GLContextHolder`, one `AudioEngine` instance, injected via DI rather than referenced as bare `object`/static fields) is permitted and necessary. The distinction the rule enforces is explicitness, ownership, and testability — not "zero singletons of any kind."

### 158. FINAL ARCHITECTURAL STATEMENT

*(Unchanged from v2.0.)* The correct mental model is: this is not a video editor with a visualizer. It is an AUDIO-REACTIVE COMPOSITING PLATFORM with AUDIO ENGINE + REACTIVE ENGINE + LAYER COMPOSITOR + GPU RENDERER + TIMELINE + MEDIA EXPORT + EXTENSION PLATFORM. The Android UI is simply the user-facing interface to this engine.

### 159. FINAL CLAUDE INSTRUCTION

*(Unchanged from v2.0.)* Do not optimize for producing the first prototype quickly. Optimize for: correct architecture, deterministic rendering, layer isolation, plugin isolation, testability, performance, memory safety, project durability, extension compatibility, maintainability, future extensibility. When uncertain between "quick implementation" and "architecturally correct implementation," choose the architecturally correct implementation. When a bug occurs: reproduce → diagnose → test → fix → regression-test. When a feature is missing: first determine whether it belongs in Core or Extension Platform. If it is creative functionality that does not fundamentally require changes to the engine: **IMPLEMENT IT AS A PLUGIN.** The ultimate goal is that the application can continue gaining capabilities indefinitely without the core becoming a monolithic collection of special cases.

> **[RATIFIED — Ref AR-18.1–AR-18.3]** This instruction is now enforced structurally, not just as guidance: §12.1/§30.1/§37.1/§110.1 have already applied this test to every built-in visualizer, effect, and template in v1, moving them to the Extension Platform. Any *new* feature request must be evaluated against the same test before implementation begins.

---

## APPENDIX A — TRACEABILITY TABLE

Every ARCHITECTURE_REVIEW.md finding marked **Spec change: Y**, and where it is incorporated in this document. Two rows (AR-1.4, AR-5.2) are additionally listed even though the Review marked them **N** (clarify-only / implementation-detail, not a required spec change) — their substance was incorporated anyway as non-binding clarifications, and they are marked accordingly below so this table does not misrepresent them as mandatory Y findings:

| AR Ref | Review Topic | Incorporated in v3.0 §§ |
|---|---|---|
| AR-1.1 | Media3 scope boundary | §7, §92 |
| AR-1.2 | Plugin execution tiers (WASM mandatory) | §45, §60.1, §55, §57, §74 |
| AR-1.3 | Precise determinism definition | §9.1 |
| AR-1.4 (N — clarify only) | Beat status/confidence | §21, §105, §107 |
| AR-1.5 | Two-phase quarantine | §67.1 |
| AR-2.1 | Coordinate & unit system | §13.1, §10, §49 |
| AR-2.2 | Linear-light color compositing | §90.1, §38, §40, §41.1 |
| AR-2.3 | Clock authority | §14.1 |
| AR-2.4 | Modulation combine semantics | §27.1, §10 |
| AR-2.5 | Unknown plugin control fallback | §48.1 |
| AR-2.6 | Analysis cache hop size/interpolation | §17.2 |
| AR-3.1 | Flat (non-recursive) modulation graph | §26 |
| AR-3.2 | Keyframe/reactive composition order | §27.1, §41 |
| AR-3.3 | Quarantine scope (global, transparent) | §67.1 |
| AR-3.4 | Plugin API SemVer policy | §68.1 |
| AR-3.5 | Export Foreground Service | §92.1, §95 |
| AR-4.1 | ParameterResolver contract | §22.1 |
| AR-4.2 | RenderGraph derived cache / command diff | §102.1, §85.1 |
| AR-4.3 | Plugin write-only injection contract | §50.1 |
| AR-4.4 | Missing-asset recovery | §82.1 |
| AR-4.5 | timelineRange as compositing gate only | §14.2 |
| AR-5.1 | Enforced module dependency graph | §116.1 |
| AR-5.2 (N — implementation detail) | Passive plugin registry query direction | §44 |
| AR-5.3 | GPU resource budget at edit time | §88.1 |
| AR-6.1 | Audio↔render concurrency contract | §18.1, §101.1 |
| AR-6.2 | GL command queue | §88.2 |
| AR-6.3 | GL context loss is routine/recoverable | §8 |
| AR-7.1 | Progressive priority-ordered analysis | §17.1 |
| AR-7.2 | Low-confidence source behavior | §21 |
| AR-7.3 | Source evaluation deduplication | §22.2 |
| AR-8.1 | Per-type interpolation rules | §41.1 |
| AR-8.2 | Resolved Modulation Cache | §27.2 |
| AR-8.3 | Undo coalescing rule | §85.1 |
| AR-9.1 | WASM validation stages | §63.1 |
| AR-9.2 | Transactional plugin dependency activation | §70.1 |
| AR-9.3 | Plugin structural migration hooks | §69.1, §46 |
| AR-9.4 | Remove NETWORK permission (v1) | §61.1 |
| AR-10.1 | Analysis cache external to project file | §10, §18.1, §81 |
| AR-10.2 | Migration golden-render tests | §84.1 |
| AR-11.1 | Quality-knob vs. content-knob classification | §86.1, §25, §49 |
| AR-11.2 | Dropped-frame determinism | §87.1, §27.2 |
| AR-12.1 | Adaptive Quality Degradation Ladder | §87.2 |
| AR-12.2 | Decode-for-purpose asset policy | §88.3 |
| AR-12.3 | Per-subsystem CPU budgets | §100.1 |
| AR-13.1 | Encoder capability pre-flight probing | §96.1 |
| AR-13.2 | Bounded export retry policy | §94.1 |
| AR-13.3 | GPU-surface encoder hand-off | §92.1 |
| AR-14.1 (Review §14) | Three-tier asset caching | §82.1, §16 |
| AR-14.2 | onTrimMemory response | §98.1 |
| AR-14.3 | Config-change GL survival | §8 |
| AR-15.1 | Golden test tiering & SSIM methodology | §77.1, §120.1 |
| AR-15.2 | Isolation tests as permanent CI | §80.1 |
| AR-15.3 | Plugin API compatibility regression suite | §68.1 |
| AR-16.1 | Plugin trust model | §60.2, §46 |
| AR-16.2 | Shader static analysis + GPU watchdog | §63.1, §89 |
| AR-16.3 | WASM host ABI | §56.1 |
| AR-17.1 | RendererBackend scope discipline | §6 |
| AR-17.2 | Global mutable state restated | §157.1 |
| AR-17.3 | Fast-path/slow-path plugin validation | §63.1, §66.1 |
| AR-18.1 | Built-ins as first-party system plugins | §0, §12.1, §30.1, §37.1 |
| AR-18.2 | Templates as ordinary presets | §110.1 |
| AR-18.3 | 3D Sigil demo confirmation | §12.1, §144 |
| AR-19.1 | Single-audio-track v1 scope | §1, §15 |
| AR-19.2 | Video proxy workflow | §34A |
| AR-19.3 | Color grading as post-v1 final-composite plugin | §37A |
| AR-19.4 | Accessibility NFR | §103.1 |
| AR-19.5 | Opt-in telemetry hook | §98 |

---

## APPENDIX B — UNRESOLVED ARCHITECTURAL DECISIONS REQUIRING HUMAN APPROVAL

Everything above is now **ratified, binding specification text** — it is not awaiting approval. The items below are the ones the Review flagged as needing a numeric value, a technology selection, a feasibility spike, or a business/product/legal judgment call that architecture alone cannot settle. **No implementation work should begin on a phase that depends on an unresolved item below** (cross-referenced to the phase in §128–§140 it blocks). Two items (U-1, U-2) have since been resolved — see the `Status` column.

| # | Decision | Nature | Owner | Status | Blocks phase |
|---|---|---|---|---|---|
| U-1 | Dependency injection framework selection (e.g. Hilt vs. Koin vs. manual) | Technology/team preference | Project Owner | **RESOLVED — Hilt** (§6.1) | ~~Phase 1~~ — resolved, no longer blocks |
| U-2 | Minimum supported Android API level | Business/market-reach decision (also gates which `MediaCodec`/Compose/Foreground-Service capabilities are available) | Project Owner | **RESOLVED — minSdk 35 (Android 15); compileSdk/targetSdk 36 (Android 16)** (§6.1) | ~~Phase 1~~ — resolved, no longer blocks |
| U-3 | WASM runtime library selection (e.g. Wasmtime vs. Wasmer, via JNI) and an Android feasibility spike confirming acceptable binary size/startup latency/performance | Technology selection + feasibility spike required | Project Owner | OPEN | Phase 7 |
| U-4 | Plugin API major-version shim support window (how many prior majors, how many years) | Support-policy/business decision | Project Owner | OPEN | Phase 7, ongoing |
| U-5 | Exact per-category SSIM (or equivalent) golden-test thresholds | Empirical tuning, needs real reference-device renders | Project Owner | OPEN | Phase 1 (infrastructure), refined through all phases |
| U-6 | Foreground Service type classification (`dataSync` vs. `specialUse` vs. other) for export, verified against current Android platform policy at implementation time | Platform-policy compliance decision, may change over time | Project Owner | OPEN | Phase 8 |
| U-7 | Timeline for activating the opt-in crash/plugin-failure telemetry hook (§98), and its exact data-minimization/consent UX | Privacy/legal decision | Project Owner | OPEN | Post-v1 (hook reserved now, per §98) |
| U-8 | Accessibility conformance target (e.g. which WCAG-equivalent level, or platform-specific Android accessibility guideline) for the editing UI | Product/legal decision | Project Owner | OPEN | Phase applies across UI work, formalize before Phase 2 UI begins |
| U-9 | Specific GPU resource budget numbers per device tier (max FBOs, max texture memory, max effect-chain depth) referenced in §88.1 | Needs a real hardware survey (§122) to set numbers responsibly | Project Owner | OPEN | Phase 3 (hardware survey), enforced from Phase 2 onward with provisional defaults |
| U-10 | Specific Adaptive Quality Degradation Ladder thresholds (§87.2) — at what measured frame-time/device-tier each ladder step triggers | Needs device-matrix profiling data | Project Owner | OPEN | Phase 10 |
| U-11 | Exact per-subsystem CPU time budget split within the 16.67ms frame (§100.1's indicative numbers are provisional) | Needs profiling data from a working renderer | Project Owner | OPEN | Phase 3 onward, finalized Phase 10 |
| U-12 | Whether to revisit single-audio-track v1 scope (§1, §15, §19.1) to add multi-track/voiceover/ducking, given §1's own "promotional clips" use case | Product-scope decision | Project Owner | OPEN (deliberately deferred) | Any phase touching Audio Engine, if reopened |
| U-13 | Whether/when to schedule actual implementation of Color Grading as a `FinalComposite`-scope Effect Plugin (§37A) — architecture is reserved now, feature build is not scheduled | Product roadmap decision | Project Owner | OPEN (deliberately deferred) | Post-v1 |
| U-14 | Whether/when a future networked plugin class (e.g. cloud-render exporter) should be designed, given `NETWORK` is removed entirely from v1 (§61.1) | Product roadmap + security-review decision (would require its own manifest/signature/review model) | Project Owner | OPEN (deliberately deferred) | Post-v1, if ever |
| U-15 | HDR/wide-gamut color pipeline — v2.0/v3.0 both say "architecture prepared for" (§90) but do not schedule it; confirm target phase, if any | Product roadmap decision | Project Owner | OPEN (deliberately deferred) | Post-v1, if ever |
| U-16 | `.arp` package integrity-signature scheme specifics (which cryptographic scheme, key management/generation for plugin authors) implied by §46/§60.2 | Security-engineering decision, needs its own design pass | Project Owner | OPEN | Phase 7 |
| U-17 | Exact device-matrix hardware list (specific SoCs/RAM tiers/tablet models) to standardize on for §122 device testing | Needs current-year market data at implementation time | Project Owner | OPEN (§6.1 names two anchor devices as build-target rationale only; the full matrix is still undecided) | Phase 10–11 |
| U-18 | RendererBackend Vulkan follow-up — confirm this remains an unscheduled future ADR (ADR-011) and not a v1/near-term commitment, per §6's scope-down | Roadmap confirmation | Project Owner | OPEN | N/A (explicitly deferred; confirm deferral stands) |
| U-19 | **Complete WASM Host ABI specification** — the full Analyzer host-function table beyond the three illustrative examples in §56.1, AND the entire Custom Layer/CPU-logic Generator ABI, which is currently unspecified at §57. Found during the pre-implementation consistency audit: this was previously deferred with "defined... at implementation time" language and no gate, which this item corrects. | Architecture-required companion specification; must be authored as a normative, versioned, capability-typed function table and pass the same security-review rigor §56.1 already requires of the Analyzer ABI | Project Owner | OPEN | **Phase 7 — hard gate: no Tier-2 plugin implementation (Analyzer, Custom Layer, or CPU-logic Generator) may begin until this item is resolved** |
| U-20 | Plugin UI Schema's declarative grammar (§47/§48) — exact JSON keys/shapes for groups, sections, parameter dependencies, and visibility rules are described conceptually but not formally specified. Found during the pre-implementation consistency audit. | Plugin API surface design task | Project Owner | OPEN | Phase 7, before UI-schema-generation work begins |
| U-21 | **Canonical analysis hop (§17.5).** §17.2 inherited a "50% overlap" default while mandating a 100 Hz storage timeline; with the canonical rate fixed at 48 kHz (§17.3) these were arithmetically incompatible. Surfaced by the P-1…P-6 ratification pass, which made the arithmetic explicit. | DSP/analysis decision; changes cached numerical content and every golden vector | Project Owner | **RESOLVED — hop = 480 samples at 48 kHz → exactly 100 Hz native; FFT window unchanged at 2048; overlap 76.5625% is derived, not an input; "50% overlap" superseded; no interpolation to reach the storage rate** (§17.5) | ~~Phase 1~~ — resolved, no longer blocks |

**Nothing in Appendix B blocks Phase 0 completion** (this document, together with ARCHITECTURE_REVIEW.md, constitutes Phase 0). Each item above must be resolved, or explicitly and knowingly deferred with a named owner, before the phase it blocks begins — per §128's binding statement that Phase 1 may not begin until every Appendix B item is resolved or explicitly deferred. **U-1, U-2 and U-21 are resolved (§6.1, §17.5). U-5 is explicitly non-blocking to Phase 1's start (§129). Every remaining open item targets a phase later than Phase 1 — Phase 1 has no open Appendix B blockers of any kind.**

**Decisions incorporated directly as normative text rather than as Appendix B items:** the P-1…P-6 ratification pass resolved channel policy and canonical sample rate (§17.3), retained spectrum representation (§17.4), `analysisConfigHash` membership (§18.2), cache format version / disk budget / eviction (§18.3), and the scope of §14.1's translation rule (P-5). These are resolved, so they belong in the normative body, not in a register of unresolved decisions. Only the one decision that genuinely remained open after that pass — U-21, the canonical analysis hop — was added here, and it has since been resolved into §17.5.
