# Timeline Core v2 Edit Semantics

This document is the native-core contract. The main Kotlin/Compose edit path now builds native preview commands and submits the same command batch to JNI commit/history, but Android/Kotlin compilation and runtime validation have not been performed; treat integration as source-present but UNVERIFIED.

## Time and clip ranges

- Core time is integer flicks (`705,600,000` per second) with normalized rational frame rates.
- `Clip.source_range.duration` is the sole duration source; timeline end is `timeline_start + source_range.duration`.
- `available_range` is the authoritative media bound. The Kotlin/JNI adapter must populate it from verified media metadata.
- Every public edit operates on a project copy, validates the whole result, and returns either an immutable result or stable error code. A rejected edit never mutates its input.
- Frame rates in a `Project` are required to be positive and reduced; frame-based adapters must convert through the rational rate rather than use floating-point seconds.
- Unknown source duration is not inferred from the current trim or an arbitrary sentinel. The adapter must supply verified available-source bounds before enabling trims/slips that depend on them.
- The version-1 binary project codec is little-endian, begins with `TLCORE2\0`, rejects unsupported versions, malformed flags, truncation, and trailing bytes. It serializes only the edit-core fields; it is not a replacement for app project persistence.

## Trim

The sole source-edge calculation is `computeTrim(clip, request)` in `timeline_core_v2/src/timeline_core.cpp`.

- Start: `dIn = newStart - oldStart`; `sourceStart' = sourceStart + dIn`; `sourceEnd` is unchanged; `timelineStart' = newStart` in normal mode.
- End: `dOut = newEnd - oldEnd`; `sourceEnd' = sourceEnd + dOut`; `sourceStart` and `timelineStart` are unchanged.
- Duration is derived from source range. Duration under the requested minimum and source-out-of-available-range both reject.
- Ripple trim uses the same `computeTrim` result, anchors a ripple start trim at its old left edge, then shifts following clips by the changed timeline end. The UI ghost and commit must call the same `applyEdit`/native preview operation with the same command, not local Kotlin math.

## Move, insert, delete, split, slip, roll, slide

- Move: validate source/destination track and clip/group locks, apply new start on a project copy, then reject any invalid overlap.
- Insert: validate new clip and destination; non-ripple rejects collision; ripple shifts followers before full validation.
- Delete: validate every selected clip before removing any; ripple applies the union of removed time intervals and rejects locked affected clips/tracks.
- Split: split strictly inside the clip, with matching source/timeline cut and caller-supplied unique id.
- Slip: timeline position/duration stay fixed; source range shifts as a unit and clamps to the available media range.
- Roll: two adjacent clips share a moved cut; total end is preserved and both source bounds/minimum durations are checked.
- Slide: requires unique adjacent left/right clips by timeline boundary (not storage order); the middle clip moves while neighboring source/timeline boundaries compensate and track end remains fixed.
- Multi-clip delete ripple compresses by the union of removed intervals, so simultaneous removals on multiple tracks are not double-counted.
- Ripple scope is currently supported only for trim, insert, and delete. Passing a non-`None` ripple scope to move, split, slip, roll, slide, or group-lock edits rejects with `TL_E_INVALID_ARGUMENT` rather than silently ignoring the flag.

## Lock metadata

- `SetGroupLock` is native edit kind 9. It requires an explicit boolean (JNI command-record field 5 is encoded as 0/1 for this kind only) and changes every clip sharing the anchor's nonzero `group_id`; a zero group ID targets only the anchor clip.
- Lock/unlock state changes are validated on a private project copy, included in the deterministic project hash, and recorded in the same C++ undo/redo journal as timeline edits. A rejected request leaves both project and history unchanged.
- The command intentionally remains available when the clip, group, or track is locked so users can unlock content; all timing edits continue to enforce track, clip, and group locks.
- Both Compose long-press context menus expose “Lock Linked Group” / “Unlock Linked Group” for persistent group IDs and “Lock Clip” / “Unlock Clip” otherwise. The action is disabled if the native core is unavailable; no Kotlin mutation fallback is used.
- This UI and JNI command mapping are source-present only. C++ group-lock semantics/history are host-tested; Android/JNI/Kotlin execution remains unverified.

## History and serialization

- `TimelineHistory` stores bounded per-clip before/after deltas, including track and clip-vector index. Undo/redo checks the expected current project hash, applies deltas to a candidate, validates that candidate and its target hash, then commits it. A divergence rejects with `TL_E_HISTORY_CONFLICT` (stable value 22) without changing project or history. The class is thread-confined; the JNI history session is mutex-protected.
- The JNI/Kotlin coordinator exposes commit, undo, redo, and history-clear operations. `TimelineViewModel` source routes move/trim/linked-trim/delete/split/insert/ripple/slip/slide/roll and group-lock commands through native commits and adds a snapshot-free `NativeTimelineHistoryCommand` to the Kotlin UI stack. C++ owns the actual timeline edit undo/redo deltas; Kotlin tracks command labels/order for the UI.
- `TimelineHistoryManager` still stores Kotlin `SnapshotCommand`s for metadata outside the native edit protocol (color/transform adjustments and track settings). The native project hash comparison clears native history when fields represented by the protocol change, but complete application-wide interleaved history parity is not established. Android compilation/tests remain UNVERIFIED.
- Native tests cover history round-trips for delete, split, insert, and move, clip ordering, external-project conflict atomicity, bounded history, 10,000 randomized multi-track operations, and 10,000 single-clip undo/redo cycles.
- The core codec uses explicit schema version 1 and fixed little-endian fields; it does not serialize media metadata, transforms, color state, sequences, or other application-level project fields. Do not use it to overwrite the app's persisted project format before a migration is designed.

## Snap

The v2 snap API builds targets in native code from one `SnapConfig`: timeline start, playhead, clip edges, markers, and optional grid. Threshold and grid are `Flicks`; Kotlin converts only the pixel threshold to an integer frame count, while the grid interval is sent in frames and converted to Flicks in C++. Both timeline gesture controllers call the v2 adapter; edit gestures fail closed and snapping returns rejected/null if `NativeTimelineCoreV2` is unavailable. Kotlin unit-test source checks this contract, but Gradle execution and Android runtime behavior remain UNVERIFIED. `CoreEditEngine`, `TimelineValidator`, and `TimelineQuery` are marked compile-guarded; metadata-only snapshot history remains Kotlin-owned.

## Open integration items

- Confirm the ripple-start policy against UI expectations under Android gesture tests.
- Verify `available_range` conversion from media metadata in the Android adapter. Unknown duration is not guessed beyond the referenced `sourceOut`; trims/slips that require verified bounds reject.
- Group identity is persisted in `TimelineClip.metadata["linkedGroupId"]`; `TimelineClip.isGroupLocked` defaults to false for backward compatibility. The adapter hashes nonblank group IDs through a collision-checked stable-ID table, transmits group-lock flags, and verifies native result IDs. C++ rejects timeline edits if any member of a nonzero group is group-locked. A long-press UI action now submits `SetGroupLock` through native commit/history; it controls groups only when a persistent `linkedGroupId` is already present and otherwise locks the single clip. No app action currently creates/reassigns linked-group membership. Kotlin adapter, lock-target, and legacy-serialization test sources exist but remain uncompiled/unrun until Gradle is available.
- Compile/register JNI on the Android toolchain, test native-unavailable behavior, and verify the native/Kotlin command protocol.
- Complete cross-domain metadata/history policy and render-graph/preview-export integration; these are not implemented by the edit-core codec.
