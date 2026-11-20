# Timeline Core v2 — Implementation Plan

## Status and execution constraints

- Active branch: `arena/a2d6ebb0-cinema-cut-pro` (do not switch branches).
- Current branch base: `dd626c4b938af4730b3e3dc877b44de875506f94`.
- The base commit contains the uploaded project ZIP and a one-line README. This checkpoint establishes the extracted Android project and Timeline Core v2 migration in Git history; the base commit itself is not an import/migration commit.
- Current environment for the latest gate: GCC 12.2 and Python are available; CMake 3.31.10/Ninja 1.13.2 were provisioned temporarily under `/tmp/arena-cinema-host-tools` and are not repository artifacts. `java`, `javac`, `kotlinc`, Android SDK/NDK, `adb`, emulator, and device tools remain unavailable. Android/NDK builds and physical-device proof are blocked/unverified. See [`REMAINING_WORK.md`](REMAINING_WORK.md) for the ordered mobile-first gates.
- `:app:assembleDebug` is explicitly requested and was attempted; it exited before Gradle because Java is missing. No release APK is requested.

## Target outcome

Create one authoritative C++ edit/time/snap/history implementation with an integer timebase, validate-then-commit semantics, deterministic errors, regression/property tests, and one Kotlin/JNI transport boundary. Preserve existing project/media compatibility where feasible. Remove migrated duplicate edit semantics or keep only compile-guarded facades with a migration map. Kotlin is UI/orchestration and DTO transport, not an alternate edit engine. No success claim without a command and captured evidence.

## Current evidence status

- **P0 — reconnaissance/docs/gates: PARTIAL.** Architecture, plan, research, stack, probes, CMake presets, host gate, and extracted source baseline are recorded in this checkpoint. Host verification is available, but Android build/test prerequisites remain unavailable.
- **P1 — host core: HOST TESTED, app integration unverified.** C++23 integer Flicks, checked arithmetic, project validation/hash, stable errors, schema-v1 codec, edit operations, and snap are implemented. Latest direct warnings-as-errors runner passes 170,624 assertions. The current CMake Debug/Release/ASan/UBSan/coverage tests also pass for the current working-tree source.
- **P2 — atomic edit semantics: HOST TESTED / APP UNVERIFIED.** `computeTrim` is the one native trim calculation. Native edits validate candidates before returning; tests cover F1/F2 regressions, locks (including a group lock on another member), rejection atomicity, ripple, roll, slide, and structural edits. JNI/ViewModel/Compose routes are source-present but not Android-compiled.
- **P3 — history: HOST TESTED / APP PARTIAL.** C++ history stores bounded per-clip deltas, verifies project hashes on undo/redo, and rejects external divergence with stable `TL_E_HISTORY_CONFLICT` (22). 10,000 randomized multi-track operations and 10,000 undo/redo cycles pass. Kotlin still has metadata-only `SnapshotCommand` history; complete cross-domain history parity is not claimed.
- **P4 — JNI/snap/duplicate migration: SOURCE PRESENT, ABI UNVERIFIED.** Explicit `RegisterNatives`, Kotlin transport/error mapping, ViewModel dispatch, no-fallback source behavior, `DeprecationLevel.ERROR` guards, and persistent group-ID/lock transport are present. The adapter payload test is source-only; no Android ABI/link/Gradle validation is available; no Kotlin snap mirror is used. C++ group-lock rejection and explicit group-lock updates/undo/redo are host-tested across move, ripple, insert, and lock-state transitions.
- **P5 — Compose integration/virtualization: SOURCE PRESENT, TEST UNVERIFIED.** Both canvases use native preview/commit paths and visible-clip culling. The 500-clip/20-track Kotlin test is source only; device performance and a unified gesture-state-machine requirement are still open.
- **P6 — preview/export compatibility: PARTIAL.** Rational frame conversion is used by inspected export boundaries, Media3 output frame count now uses ceiling, and placeholder/mock renderer files were removed. Export now evaluates each clip through the shared `GradeEvaluationEngine.evaluateClipGrade` composer and renders the composed clip grade + enabled color nodes with the same `VideoAdjustmentEffect` shader as preview; validation accepts the shader-rendered set and fail-closes on animated grades, blend modes, and Color Match, with LUT data pre-loaded before rendering. Kotlin tests, Media3 shader execution, and device playback remain `UNVERIFIED`; animated grade export and full multi-layer graph parity are `NOT IMPLEMENTED`.
- **P7 — performance/safety: HOST TESTED / DEVICE UNVERIFIED.** Current host Debug/Release/ASan/UBSan/coverage gates and host benchmark pass. The benchmark is not Compose/device performance evidence. No Android Macrobenchmark/Perfetto result, device measurement, or 16 KB ELF check exists.
- **P8 — cleanup/evidence: HOST COMPLETE; ANDROID BLOCKED.** Migration docs/status matrices were reconciled; the strongest available host gate and performance results are logged. `:app:assembleDebug` was attempted but stopped before Gradle because Java/JDK is absent. Worklog and conventional phase commit are included.

## Phases and gates

### P0 — Baseline, architecture map, research, and gate scaffold
- [x] Inventory current timeline model, edit/ViewModel/Compose/JNI/C++ paths, tests, Gradle declarations, and available tools.
- [x] Write `docs/ARCHITECTURE.md`, `docs/RESEARCH.md`, `docs/STACK.md`, `docs/PLAN.md`, and `AGENTS.md`.
- [x] Probe actual host compiler and C++ feature support; preserve exact toolchain gaps.
- [x] Add host CMake/Ninja presets and a gate that reports missing Java/Android tools honestly.
- [x] Establish the extracted project and migration state in a conventional phase commit on the active branch.
- **Exit evidence:** source inventory, exact probe output, host gate output, and an explicit Android toolchain blocker.

### P1 — Time model and project invariants
- [x] Introduce integer Flicks and rational frame-rate conversion with checked overflow behavior.
- [x] Define canonical edit-core project/track/clip/source ranges and derived duration/end.
- [x] Add deterministic hash/serialization and named invariant tests.
- **Exit evidence:** current direct C++23 runner passes; Android/JNI conversion remains unverified.

### P2 — Single edit engine and atomic commands
- [x] Implement one `computeTrim` used by native preview evaluation and commit evaluation.
- [x] Implement move/insert/delete/split/slip/slide/roll/ripple with validate-then-commit and lock checks in C++.
- [x] Add native `SetGroupLock` edits that update a persistent linked group atomically (or one ungrouped clip) and are reversible through the same C++ history journal.
- [x] Add F1 trim-orientation and F2 rejected-move/lock regressions; rejected native operations preserve project/history state.
- [ ] Verify the Kotlin/JNI payload and command mappings under Android tests when the toolchain is available.
- **Exit evidence:** named host tests and direct command result; app execution remains `UNVERIFIED`.

### P3 — Transactional history
- [x] Replace whole-project snapshots in the native timeline journal with bounded per-clip reversible deltas.
- [x] Validate expected/target project hashes and reject history conflicts atomically.
- [x] Add structural-edit, ordering, conflict, bounded-depth, and randomized undo/redo tests.
- [ ] Complete cross-domain history behavior for metadata-only Kotlin snapshots or document it as outside the native edit journal.
- **Exit evidence:** 10,000 randomized multi-track history operations plus 10,000 single-clip undo/redo cycles pass; Kotlin/Android behavior remains unverified.

### P4 — Snap, JNI contract, and duplicate-engine migration
- [x] Make C++ v2 the only native edit/snap authority; snap grid frames are converted to Flicks in JNI/C++.
- [x] Add explicit JNI registration and error/result mapping source.
- [x] Add compile-time deprecation guards and a migration map for obsolete Kotlin edit/query/model surfaces.
- [x] Keep native-unavailable paths fail-closed in adapter/gesture source; do not use Kotlin edit/snap fallback.
- [x] Map persistent linked-group IDs and group-lock flags between the active DTO and native clip records; add native/Kotlin payload tests and an undoable `SET_GROUP_LOCK` command mapping.
- [ ] Compile and validate JNI signatures/ABI and Kotlin guards with the Android toolchain.
- [ ] Decide whether to add a Kotlin/C++ frame-time parity test at the JNI boundary; do not add an unverified mirror.
- **Exit evidence:** current C++ host test and source-level signature review; Android registration/link check remains `BLOCKED`.

### P5 — Kotlin integration and virtualized Compose timeline
- [x] Route ViewModel move/insert/trim/delete/split/slip/slide/roll/ripple command descriptions through the native adapter.
- [x] Route both canvases' preview/snap requests through v2 and cull clip children by visible range plus overscan.
- [x] Add source-level unavailable-native and 500-clip/20-track regression tests.
- [x] Add long-press clip/group lock controls to both canvases; route toggles to native history and disable the action when v2 is unavailable.
- [ ] Add a user flow for creating/reassigning persistent linked-group IDs; current lock UI recognizes pre-existing IDs only.
- [ ] Compile/run Kotlin and Compose tests and verify cancel behavior.
- [ ] Consolidate duplicate UI gesture state machines if inspection/build demonstrates this is still needed.
- **Exit evidence:** Android Gradle test and compile when available; source-only status must remain explicit until then.

### P6 — Preview/playback/export compatibility audit
- [x] Inspect time conversion and export frame-count paths; use ceiling conversion where counting output frames.
- [x] Remove unreferenced placeholder `ExportRenderPipeline`/empty decode-encode and mock adjustment renderers.
- [x] Remove the selectable `NATIVE_CPP` preview path whose no-op JNI stubs advertised unsupported playback.
- [ ] Verify Media3 and MotionCam export integration on Android with representative media; do not infer a device result from host decoder tests.
- [ ] Implement or explicitly leave full preview/export frame parity NOT IMPLEMENTED.
- **Exit evidence:** named host tests only; Android/Media3 output validation is unavailable here.

### P7 — Performance and safety verification
- [x] Add host edit/snap/query benchmark source and retain historic raw observations.
- [x] Run current-source sanitizer/coverage gates using the verified isolated CMake/Ninja tools.
- [ ] Add/run Android Macrobenchmark/Perfetto for 500+ clips / 20+ tracks and scrubbing.
- [ ] Measure device budgets on a named representative device; measure 16 KB ELF alignment on produced binaries.
- **Exit evidence:** host and device metrics must be separated; every missing device measurement is `UNVERIFIED`.

### P8 — Cleanup, documentation, and final gate
- [x] Update `docs/MIGRATION.md`, `EDIT_SEMANTICS.md`, `INVARIANTS.md`, `TESTING.md`, `FEATURE_MATRIX.md`, `ARCHITECTURE.md`, and this plan as source changes land.
- [x] Run the strongest available final host gate and preserve exact output (`GATE_20261120T063018Z.log`; native export/core/probe, Debug/Release/ASan+UBSan/coverage, and host benchmark pass; overall exit 2 is solely the Java/JDK blocker. Sandbox timestamp is an artifact ID, not verified chronology).
- [x] Attempt the explicitly requested `:app:assembleDebug`; it is recorded as `BLOCKED` because Java/JDK is absent. The mobile runner preflight also confirms Android SDK/NDK/adb/device are unavailable.
- [x] Add a five-line `docs/WORKLOG.md` checkpoint including root cause → rule for each failed gate attempt.
- [x] Commit the phase using a conventional commit message on the current branch.
- [x] Final report in Bengali; use `NOT IMPLEMENTED`, `UNVERIFIED`, and `BLOCKED` precisely.

## Cross-phase acceptance criteria

- F1: one trim computation path for native ghost preview and commit, with regression tests.
- F2: all core edits validate before mutation; rejection preserves project/history hashes; clip/track locks are checked.
- F3: exactly one authoritative C++ edit-semantics implementation; duplicate app paths are removed or compile-guarded and documented.
- F4: one snap configuration/calculation; no divergent fallback when native is unavailable. A Kotlin parity claim requires 10,000-case evidence; no Kotlin snap mirror currently exists.
- F5: visible-range culling exists, but 500-clip/20-track device frame/jank/scrub measurements are still required and remain `UNVERIFIED` without a device.
- INV-01..16: only implemented invariants receive test claims; INV-13/14/16 remain `NOT IMPLEMENTED`, INV-15 Kotlin tests remain unrun.

## Decision rule

Prioritize a testable core and transactional history before renderer/UI polish. Verify APIs and tool versions before claims. Do not silently convert a rejected edit into a clamped edit, introduce float seconds into model math, or rely on static reflection/contracts/production `std::execution`. A phase is not `DONE` because code or docs exist; use `PARTIAL`, `UNVERIFIED`, `NOT IMPLEMENTED`, or `BLOCKED` when evidence is incomplete.
