---
name: timeline-core-invariants
description: Design, implement, and verify the authoritative edit semantics of a non-linear video editor timeline core (trim, roll, slip, slide, ripple, move, delete, split) with integer flick timebase, validate-then-commit commands, and undo/redo. Use when writing or reviewing timeline model code, EditEngine/command classes, trim math, snapping, history, or when timeline edits produce wrong durations, wrong source frames, lost clips, or non-reversible undo state.
license: MIT
---

# Timeline core invariants

Purpose: make timeline edit semantics **single-sourced, provable, and reversible**. Every bug in this
domain has the same shape: the same edit is computed in two places, or a mutation happens before
validation. Kill both possibilities structurally.

## 1. Time representation

- One unit, integer only: `flicks` (1 s = 705,600,000). Never `float`/`double` seconds in model code.
- Types: `struct Flicks { int64_t v; }` (strong type, no implicit conversion to int64_t),
  `RationalTime { int64_t value; int32_t rate; }`, `TimeRange { Flicks start, duration; }`.
- Helpers must be constexpr and lossless: `flicksOfFrame(n, rate)`, `frameOf(t, rate)`,
  `rescale(t, fromRate, toRate)` with explicit rounding rule documented (round-half-to-even).
- Invariant: `rescale` round trip at the same rate is identity; test with 23.976, 29.97, 59.94, 120.

## 2. The single trim function

```
struct TrimRequest { ClipId id; Edge edge; Flicks newTimelineEdge; };
std::expected<TrimResult, EditError> computeTrim(const Project&, const TrimRequest&) noexcept;
```

Rules (this is the contract, encode it in tests first):

- `dIn  = newStart - oldStart`; `dOut = newEnd - oldEnd`.
- Trim in: `sourceIn += dIn`, `start = newStart`; out: `sourceOut += dOut`, `start` unchanged.
- Dragging an edge **toward the middle** shortens; **away** extends. Source edge moves with the same
  sign — never invert one side.
- `duration` is always **derived** (`sourceOut - sourceIn`), never stored independently.
- Validity: source range inside `media.availableRange`, `duration >= minDuration` (default one frame),
  track/clip/group not locked, no overlap (unless the track is in magnetic mode).
- Extend never goes past available media (documented reject, not silent clamp).

The drag ghost, the commit path, the export renderer and the UI preview all call **this** function.
If any of them computes trim math locally, that is a defect, even if the numbers happen to agree.

## 3. Command model

- `EditCommand` is data (POD-ish, serializable), applied by `apply(cmd, project) -> expected<EditResult>`.
- **Validate-then-commit**: build the new track state, validate fully, then swap. On error, the project
  hash must be byte-identical to before the call.
- Every command provides `inverse()`; `apply(inverse)` restores the prior project hash exactly.
- Error codes are enumerated and stable (`TL_E_TRIM_OUT_OF_BOUNDS`, `TL_E_LOCKED_CLIP`, `TL_E_OVERLAP`,
  `TL_E_MIN_DURATION`, `TL_E_TRACK_NOT_FOUND`, ...), documented in `docs/ERROR_CODES.md`.
- Results report `changedClipIds`, `affectedRange` and `projectHash` so the UI can diff cheaply.

## 4. Invariant checklist (each needs a named test)

| ID | Invariant |
|----|-----------|
| INV-01 | `duration == sourceOut - sourceIn` after every op |
| INV-02 | `end == start + duration`; project duration == max(track end) |
| INV-03 | no overlapping clips in a track unless magnetic mode |
| INV-04 | rejected edit ⇒ identical project hash |
| INV-05 | locked track/clip/group never mutated ⇒ deterministic error |
| INV-06 | undo(apply(cmd)) ⇒ identical hash (property test over random sequences) |
| INV-07 | redo(undo(x)) == x, including after history compaction |
| INV-08 | trim never exits `availableRange` |
| INV-09 | min-duration violation rejects, never clamps silently |
| INV-10 | snap is deterministic; native and any mirror agree over random inputs |
| INV-11 | thread-safety contract documented and enforced (TSan clean) |
| INV-12 | serialize∘deserialize == identity, schema-versioned |
| INV-13 | frame cache key covers source+params+timebase (no stale frames) |
| INV-14 | export frame at t == preview frame at t (SSIM/hash, tolerance documented) |

## 5. Property tests to write (RapidCheck / proptest style)

1. Random clip + random delta → trim → invariant check, then undo → hash equal.
2. Random command sequence (100 ops) → apply all → undo all → hash equal to initial.
3. Random sequence → apply all → redo all → hash equal to the pre-undo state.
4. Trimming to any frame boundary, then re-trimming back, restores the original source range.
5. Slip/slide/roll preserve track total duration.
6. Delete + undo never changes clip IDs, z-order, or linking.

## 6. Anti-patterns to reject in review

- `deleteClip(); addClip();` "move" — delete-first destroys data on rejection.
- Ignoring `expected::error` and using `.value()` / `.project` blindly.
- Duration stored next to source in/out (two sources of truth).
- Trim math copied into gesture code, Compose preview, or export path.
- Lock checks on tracks but not clips/groups.
- "It works in the demo" benchmarks with no generated cases.

## 7. Definition of done for this domain

- All INV-01..INV-14 have a test, and the test names contain the INV id.
- `bench/trim_bench` numbers recorded; core edit ops are O(1)–O(log n) per command.
- `docs/EDIT_SEMANTICS.md` contains the same rules as the code, with worked numeric examples
  (24 fps, 29.97 fps, one with a trim that hits a media bound).
