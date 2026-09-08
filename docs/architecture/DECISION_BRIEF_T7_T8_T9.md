# Decision Brief — T-7, T-8, T-9

**Status: T-7 OPEN · T-8 OPEN · T-9 awaiting confirmation. Nothing here is ratified.**

Prepared after Step 8. No implementation, cache format, project format or golden vector was
modified in preparing it. Sources are quoted verbatim from `MASTER_SPECIFICATION_v3.0.md`.

---

## Shared constraints that bear on all three

| Ref | Verbatim | Bearing |
|---|---|---|
| §17 | "Required global features: RMS, Peak, Energy, **Normalized Energy**, **Loudness Approximation**." | Lists Energy and Normalized Energy as **separate** required features. |
| §17.4 | "**Stored separately** (computed during analysis at full float32 precision, then stored): RMS, Peak, Energy, Normalized Energy, Loudness Approximation, …" | All five **must be stored**, not derived at read time. Rules out "derive on read" for any of them without amending §17.4. |
| §17.4 | "magnitudes are normalized against the **canonical signal's full-scale reference**" | The only normalization reference the spec names anywhere. It is **absolute**, not track-relative. Applies to spectra, but it is the sole precedent. |
| §18.2 item 8 | "**Normalization algorithm and configuration** (§19, §106 'Normalization')." | Whatever is chosen is **in the hash** — changing it invalidates the cache. The invalidation mechanism already exists. |
| §18.2 governing test | "if changing an input changes a number stored in the cache, it is in the hash; if it only changes how an already-stored number is consumed, it is not." | The test that decides where each parameter belongs. |
| §18.1 | cache is "**immutable-once-written and append-only**", read lock-free behind a "highest-complete-index" marker; "If analysis for time T is not yet available, the renderer uses the nearest available cached sample." | A value that can change after being written is **incompatible** with this contract. Decisive for T-7. |
| §17.1 | stages run "chunked and progressive"; Trim usable in ~1 s from stage 1+2. | Bears on whether a whole-track prepass is affordable. |
| §17.2 | every feature "is resampled/aligned to the 100 Hz storage timeline before caching". | A feature whose natural window is longer than one frame must be aligned to 100 Hz. |
| §19 | "Visual reaction must be independent of **device playback volume**. Internal analysis operates on normalized signal characteristics." | The whole of §19. Note what it does **not** say — see below. |
| §106 | "Master: Sensitivity, **Normalization**, FFT size, Smoothing, Beat detection, Analysis quality." | Normalization is a user-facing master control, i.e. a setting with at least an on/off state. |

**One observation about §19 that materially narrows the problem.** §19's binding sentence is about
**device playback volume**. Analysis reads the decoded asset and never observes the OS or user
volume, so that requirement is **already satisfied unconditionally**, by every candidate below and
indeed by no normalization at all. §19's second sentence ("internal analysis operates on normalized
signal characteristics") is what motivates the feature, but it names no reference and imposes no
constraint that distinguishes the candidates. **§19 does not decide T-7.**

**Cost of one stored scalar channel.** 100 Hz × float32 = 400 B/s = **24 KB per track-minute**;
≈ 1.4 % of §17.4's 0.7 MB/track-minute scalar budget and ≈ 0.2 % of the 12.4 MB total. Disk is a
weak argument in every direction here, and is not used as a deciding factor below.

---

## T-7 — Normalized Energy

### What §17 currently requires

Verbatim and in full: **"Normalized Energy"** appears twice — once in §17's required global feature
list, once in §17.4's "stored separately" list. There is no definition, no reference level, and no
range anywhere in the document. §19 is the only section that could constrain it, and per the
observation above, it does not.

### Minimum viable definitions

A definition satisfying the existing requirement needs exactly two things: a numerator (the frame's
energy) and a **reference**. Every candidate differs only in the reference.

Throughout: `N` = 2048 (§17.5 window), `x` = the §17.3 canonical mono signal, and
`E[n] = Σ x²` over frame `n` (the current T-9 convention).

| | **A — Full-scale absolute** | **B — Track peak-energy** | **C — Track loudness-referenced** |
|---|---|---|---|
| **Exact definition** | `Ê[n] = E[n] / N` (equivalently `rms[n]²`), full scale = 1.0 | `Ê[n] = E[n] / max_m E[m]` | `Ê[n] = E[n] / E_ref`, `E_ref` from the track's integrated loudness (T-8) |
| **Range** | `[0, 1]` for non-clipping content | `[0, 1]`, max exactly 1 | unbounded above; `1.0` at reference |
| **Whole-track prepass?** | **No** — per-frame, causal | **Yes** | **Yes**, and depends on T-8 |
| **Recoverable from cached frames?** | **Yes** — exactly `rms[n]²`; storing it adds no information | **No** from a frame alone. Yes **only if** `max_m E[m]` is also cached | **No** from a frame alone. Yes only if `E_ref` is cached |
| **Invariant to source gain?** | **No** — a master 6 dB quieter yields 4× lower values | **Yes** — scaling `x` by `k` scales all `E` by `k²`; the ratio is unchanged | **Yes** |
| **§18.1 immutable-once-written?** | **Yes**, trivially | Only if the reference is fixed **before** any frame is written | Same as B |

### Consequences for the on-disk cache and deterministic analysis

**Candidate A** is the only causal option. It writes each frame from that frame's samples alone, so
§18.1's append-only, immutable-once-written contract and §17.1's progressive stage order are
satisfied without any special handling. Its weakness is not disk: it is that
`Ê[n] = rms[n]²` **exactly**, so it is not a distinct feature. §17 lists Energy and Normalized
Energy separately, and under A they differ only by the constant `N` — a unit change, not a second
measurement. That is real evidence against A being the intended reading.

**Candidates B and C** make it a distinct, gain-invariant feature — which is what the word
"normalized" ordinarily promises and what makes a reactive mapping behave the same on a quiet
master and a loud one. The cost is a reference that is unknown until the whole track has been
examined. Two sub-consequences:

1. **§18.1 forbids revising written frames.** A running maximum that grows as analysis proceeds
   would make every already-written `Ê` wrong, and rewriting them contradicts
   "immutable-once-written". So the reference **must** be finalised before the first stage-2 frame
   is published — i.e. a genuine prepass, not an incremental refinement.
2. **The prepass is nearly free in practice.** Stage 2 already reads every sample of the canonical
   signal to produce the Peak envelope, and `max_m E[m]` falls out of that same pass. The cost is
   that stage 2 becomes *pass-then-publish* rather than streaming — which §17.1 permits, since its
   ~1 s budget is for stage 1+2 **completion**, not for partial results.

**A constraint worth surfacing before deciding.** §17.4 requires Normalized Energy to be **stored**.
The cleanest engineering answer to B — store raw `E` plus the reference in the cache header and
divide at read time — would satisfy §18.2's governing test (the reference is a stored number; the
division is consumption) and would keep §18.1 trivially. **But it contradicts §17.4's "stored
separately" list**, so it cannot be adopted without amending §17.4. Flagged, not assumed.

**Consistency with §17.4's spectral reference.** §17.4 normalizes spectral magnitudes against "the
canonical signal's full-scale reference" — absolute. Choosing a track-relative reference for
Normalized Energy means the cache holds two features normalized against two different references.
That is defensible if intended, and a latent source of wrong cross-feature comparison if not.
Whichever way T-7 goes, the answer should be stated explicitly against §17.4.

### Recommendation (not ratified)

**Candidate B**, with `max_m E[m]` **also stored in the cache header**.

- It is the only reading under which §17's separate listing of Energy and Normalized Energy
  describes two distinct measurements.
- Gain invariance is what "normalized" promises, and it is what makes §19's second sentence
  ("internal analysis operates on normalized signal characteristics") mean something.
- The prepass is nearly free — it rides on the sample sweep stage 2 must perform anyway.
- Storing the reference makes the value auditable and lets a future reader re-derive raw energy,
  which a bare ratio does not.
- It requires an explicit note in §17.4 that Normalized Energy uses a **track-relative** reference
  while spectral magnitudes use the **absolute** full-scale one.

Candidate C is strictly worse to decide now: it inherits every open question in T-8.

---

## T-8 — Loudness Approximation

### What is specified, and what is not

**Specified:** §17 requires the feature. §17.4 requires it to be **stored**, computed "at full
float32 precision" during analysis. §18.2 item 8 puts "normalization algorithm and configuration"
in the hash. §106 exposes "Normalization" as a master control.

**Not specified — anywhere in the document:** the algorithm, the weighting, the gating, the
measurement window, the reference level, the units, the value range, and the behaviour at digital
silence. **The strings "LUFS", "loudness", "weighting", "dBFS", "BS.1770" and "R128" do not occur in
the specification at all** outside §17's and §17.4's two mentions of the feature name.

> **Implementation finding that must be resolved with this decision.** `NormalizationConfig` in
> `core:model` carries `targetLufs: Double = -23.0`. **That field is not from the specification** —
> it was introduced in Step 2 and its default silently encodes an EBU R128 broadcast target. It is
> currently a member of `analysisConfigHash` (`AnalysisConfig.canonicalForm()`), so it already
> participates in cache identity. Nothing computes with it yet, so no cached value is wrong today.
> It should either be ratified as part of T-8 or removed. Recorded here rather than quietly fixed.

### Minimum decisions required for a deterministic, cacheable value

Each of the following changes the stored number, so by §18.2's governing test each is a hash member
(item 8, or item 11 for implementation-level choices).

| # | Decision | Options | Why it changes the cached value |
|---|---|---|---|
| 1 | **Weighting** | none · A-weighting · **K-weighting** (BS.1770 shelf + RLB high-pass) | Different filters give different values for identical audio. At the fixed §17.3 canonical 48 kHz the BS.1770 coefficients are exactly published, so the filter is exactly specifiable — a determinism advantage the fixed canonical rate buys. |
| 2 | **Gating** | none · BS.1770 absolute (−70 LUFS) + relative (−10 LU) | Gating is defined over the **whole track** and is therefore non-causal. It also makes the per-frame value depend on frames not yet analysed, which collides with §18.1 exactly as T-7 Candidate B does. |
| 3 | **Measurement window** | per analysis frame (42.67 ms) · momentary (400 ms) · short-term (3 s) · integrated (whole track) | Anything longer than one analysis frame has a native rate below 100 Hz and must be aligned to the storage timeline under §17.2 — extra machinery, and a second framing to keep consistent with §17.5. |
| 4 | **Reference / units** | linear mean-square · dBFS (`20·log₁₀`) · LUFS (dB + BS.1770's −0.691 offset) | A pure scale/offset choice, but it is baked into every cached number and into every golden vector. |
| 5 | **Silence floor** | −∞ · −70 · −100 dB | `log(0)` is not representable. Without a floor, §119's silence fixture produces `-Infinity` in the cache and every downstream mapping inherits it. |
| 6 | **Channel basis** | §17.3 canonical mono (fixed) | Worth stating explicitly: BS.1770 defines per-channel weights for multichannel, and loudness measured on a mono downmix is **not** the same number as loudness measured on the stereo original. Since §17.3 fixes analysis to mono, the value is an approximation of the source's loudness by construction — which is consistent with §17 calling it an *approximation*, but should be said out loud. |
| 7 | **Relationship to T-7 and to §106 "Normalization"** | independent · loudness supplies T-7's reference · the §106 toggle switches between them | Decides whether T-7 and T-8 can be ratified separately. |

### Consequences for the cache and golden vectors

- **Cache identity.** Every decision above lands in `analysisConfigHash` item 8 or 11. Changing any
  of them later is a full re-analysis of every asset — correct behaviour, but expensive, so this is
  worth getting right once.
- **Golden vectors.** §17.4 makes precision testing across §119's fixture set **mandatory**, with
  measured tolerances recorded in `PERFORMANCE.md` and enforced by the §77.1 CI tier. Loudness
  values for every §119 fixture become part of that set. Note the CI tier can only cut golden
  vectors from **uncompressed** fixtures (plan §12), which the §119 catalogue already satisfies.
- **Non-causality is the expensive choice.** Options 2 (gating) and the "integrated" case of 3 both
  require a whole-track prepass and, like T-7 Candidate B, must finalise before any frame is
  published to keep §18.1's immutability.

### Recommendation (not ratified)

**A per-frame, K-weighted, ungated momentary-style approximation in dBFS, on the §17.3 canonical
mono signal:**

```
L[n] = max( SILENCE_FLOOR ,  −0.691 + 10·log₁₀( (1/N) · Σ y[n]² ) )
```

where `y` is the canonical mono signal after the BS.1770 K-weighting pre-filter (shelf + RLB
high-pass) at the fixed 48 kHz canonical rate, `N` = 2048, and `SILENCE_FLOOR = −70.0`.

Rationale:

- **Deterministic and causal.** Each frame depends only on its own 2048 samples plus a fixed filter
  state, so §18.1's immutability and §17.1's progressive order hold with no prepass.
- **Natively on the 100 Hz grid.** It uses §17.5's framing directly, so §17.2's alignment rule needs
  no work and §17.5's prohibition on manufacturing frames is untouched.
- **Exactly specifiable.** Because §17.3 fixes the analysis rate at 48 kHz, the K-weighting
  coefficients are the published constants — no rate-dependent filter design, no vendor variation.
- **Honestly named.** It is BS.1770's momentary formula without the 400 ms window and without
  gating. That is an *approximation*, which is precisely what §17 asks for — and it is a far more
  defensible reading than a plain `20·log₁₀(rms)`, which would be exactly derivable from the stored
  RMS and therefore not a distinct feature at all (the same objection as T-7 Candidate A).
- **Decouples T-7 from T-8.** With this definition, T-7 Candidate B needs nothing from T-8, so the
  two can be ratified independently.
- **Resolves the `targetLufs` finding**: under this definition there is no loudness *target*, only a
  measurement, so the invented field should be removed from `NormalizationConfig` (and hence from
  the hash) rather than ratified.

---

## T-9 — Energy convention

### Exact normative wording

Only two occurrences constrain it, and neither fixes a convention:

- §17: "Required global features: RMS, Peak, **Energy**, Normalized Energy, Loudness Approximation."
- §17.4: "Stored separately (computed during analysis at full float32 precision, then stored): RMS,
  Peak, **Energy**, …"

§18.2 does not mention it. Nothing else in the specification refers to it. **There is no wording
supporting or forbidding either `Σx²` or mean square.**

### Current implementation

`E[n] = Σ x²` over the 2048-sample analysis window — the physics definition — documented at the site
and recorded as a stated assumption.

### Can it be ratified independently of T-7 and T-8?

**Yes**, on three grounds:

1. **Convention-independent under T-7.** Every T-7 candidate is a ratio of energies. Scaling both
   numerator and reference by the same constant `N` cancels, so T-7's outcome is unaffected by which
   convention T-9 picks — provided both use the same one, which the implementation guarantees by
   computing the reference from the same array.
2. **Independent of T-8.** The recommended T-8 definition takes its own mean square of the
   *K-weighted* signal and never reads `E[n]`.
3. **Fully reversible.** `N` = 2048 is fixed by §17.5, so `E_mean = E / 2048` and
   `E = E_mean · 2048` — a read-time transform requiring no re-analysis. Contrast T-7 and T-8, where
   the wrong choice is **not** recoverable, because the track reference and the filtered signal are
   both unrecoverable from a stored frame.

**One observation, for completeness.** `E[n] = rms[n]² · N` exactly, so Energy is already derivable
from the stored RMS under either convention; §17.4 nonetheless mandates storing it. That is
redundancy, not a contradiction, and it costs ≈ 24 KB per track-minute. Noted only so the redundancy
is on the record before the format is frozen.

**Recommendation:** ratify `E = Σx²` as implemented. It is the lowest-risk item of the three and its
resolution unblocks nothing else, so it can be confirmed at any time.

---

## Summary

| | Decision needed | Recoverable if wrong? | Blocks Step 9? |
|---|---|---|---|
| **T-7** | Reference for Normalized Energy | **No** — track reference is unrecoverable from a stored frame | **Yes** |
| **T-8** | Full definition of Loudness Approximation | **No** — the filtered signal is unrecoverable from a stored frame | **Yes** |
| **T-9** | Confirm `E = Σx²` | **Yes** — constant factor, read-time transform | No |

T-7 and T-8 both remain **OPEN**. Nothing in this brief has been applied.
