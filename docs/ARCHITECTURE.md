# Timeline Architecture (Current Source Map)

## Scope and evidence

This is a source-level description of the current working tree. The Android application/JNI code has not been compiled in this environment: Java/JDK, Android SDK, and Android NDK are absent. Host C++ evidence is recorded separately in [`TESTING.md`](TESTING.md), [`PERF.md`](PERF.md), and `docs/evidence/`.

## Authoritative timeline path

```text
Persisted/UI DTOs: TimelineProject / TimelineTrack / TimelineClip (frame-count Long fields)
  ├─ TimelineViewModel
  │   ├─ translates edit descriptions to CoreV2EditCommand
  │   ├─ commits through TimelineCoreV2Adapter
  │   ├─ sends native undo/redo through NativeTimelineHistoryCommand
  │   └─ dispatches clip/group lock toggles as native SetGroupLock edits
  ├─ CineTimelineCanvas + AdvancedTimelineCanvas
  │   ├─ TimelineGestureController / AdvancedTimelineGestureController
  │   ├─ snap via TimelineCoreV2Adapter.snap (no Kotlin fallback)
  │   ├─ preview via TimelineCoreV2Adapter.applyEdits
  │   └─ compose/measure clips selected by TimelineVirtualization
  └─ NativeTimelineCoreV2 Kotlin/JNI transport
      └─ timeline_core_v2_jni.cpp (explicit RegisterNatives)
          └─ timeline_core_v2 C++23 core
              ├─ integer Flicks / rational frame conversion
              ├─ project validation and deterministic hash
              ├─ trim/move/insert/delete/split/slip/slide/roll/ripple/SetGroupLock
              ├─ snap and stable errors
              └─ bounded per-clip delta undo/redo journal
```

Both Compose canvases form the same command protocol. `TimelineGestureController.previewCommands` creates requests; the canvases ask the adapter for the native preview project and retain that request batch for commit. Specialized Ripple/Roll/Blade controllers call `TimelineViewModel.executeCommand`; its dispatch table maps those command descriptions to v2 commands. Their long-press context menus route clip/group lock changes through native `SetGroupLock` history and disable that action when v2 is unavailable. The native core validates and calculates the outcome; Kotlin owns UI state and DTO conversion only. This call graph is source-inspected, not Android build/runtime verified.

## Edit/time/history ownership

- **Core:** `app/src/main/cpp/timeline_core_v2/include/timeline_core_v2/timeline_core.h` and `src/timeline_core.cpp` define the C++23 model and operations. Core time is signed integer Flicks (`705,600,000` per second). Checked rational conversion and `computeTrim` are native.
- **JNI:** `timeline_core_v2_jni.cpp` registers a fixed JNI table and converts frame-count DTO fields to/from native Flicks. It serializes access to its process-level history session with a mutex. JNI ABI/link behavior is unverified without the NDK.
- **Kotlin adapter:** `TimelineCoreV2Adapter.kt` is the transport/conversion and error-mapping boundary. It stores clip templates only to preserve application metadata when native edit results return core fields; edit decisions and snap results are native. Snap grid intervals are sent as frame counts for C++ conversion. Persistent `linkedGroupId` metadata is converted to a collision-checked stable native ID, `TimelineClip.isGroupLocked` is sent as a native flag and restored from results, and `SET_GROUP_LOCK` carries an explicit boolean into the native journal.
- **History:** native C++ keeps bounded per-clip before/after deltas, including group-lock flag changes, validates expected/current/target project hashes, validates candidate restoration, and rejects divergence with `TL_E_HISTORY_CONFLICT` without mutating state. Kotlin `NativeTimelineHistoryCommand` is a stack label/coordinator, not a second edit journal. `SnapshotCommand` still stores Kotlin project snapshots for non-core metadata (color, transform, track settings); complete cross-domain history behavior remains unverified and is not claimed as one native metadata journal.
- **Legacy paths:** duplicate `CoreEditEngine`, `TimelineValidator`, `TimelineQuery`, and old `timeline.model` Clip/Track/Timeline/TimecodeUtil declarations are marked `DeprecationLevel.ERROR`; current app/test call-site search found none. Their compile behavior still needs Android/Kotlin compilation. `CoreTimelineEngine` performs track metadata operations and delegates clip insertion/moves to the v2 adapter.

## UI virtualization and time boundaries

`TimelineVirtualization.visibleClips` performs UI-only culling from scroll/viewport geometry. `CineTimelineCanvas` and `AdvancedTimelineCanvas` use the visible list to create clip children; track headers/backgrounds still traverse tracks. A source test constructs 500 clips across 20 tracks, but no Android test, frame-time, jank, scrub, memory, or device result is available. See [`PERF.md`](PERF.md).

`TimelineFrameMath` provides exact integer/rational mappings at Media3/export/sample boundaries; output frame counts use ceiling to cover the requested duration. These Kotlin boundary helpers and their call sites are source-present but uncompiled. Floating-point values remain in pixel geometry and display-only settings, not native edit time.

## Other editor subsystems

Transform, color, Media3 playback/export, MotionCam RAW, and native export modules remain separate from timeline edit semantics. The older mock `ExportRenderPipeline` with empty decode/encode and color/adjustment stubs was removed after a repository-wide reference search. The selectable `NATIVE_CPP` preview option backed by no-op native player functions was also removed. This does not expand the validated support claim for the existing Media3 export path; see [`FEATURE_MATRIX.md`](FEATURE_MATRIX.md) and the media/export documentation.

## Current verification boundary

- The latest direct C++23 Timeline Core runner passed with warnings-as-errors; the CMake Debug/Release/ASan+UBSan/coverage tests and host benchmark also passed. Exact counts, coverage, and logs are in [`TESTING.md`](TESTING.md) and [`PERF.md`](PERF.md).
- Native export/decoder host tests passed separately in the same recorded gate.
- No current Android/Kotlin compilation, JNI ABI/link validation, Compose test, APK assembly, emulator run, or physical-device measurement is claimed.
- The C++ schema-v1 codec contains only edit-core fields and does not replace application project persistence.
