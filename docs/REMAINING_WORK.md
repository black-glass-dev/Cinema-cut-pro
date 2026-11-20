# Remaining work — mobile-first execution order

**Owner:** the current coding agent; no additional agent is needed.  
**Branch:** `arena/a2d6ebb0-cinema-cut-pro` only.  
**Primary acceptance goal:** run the editor in a rebuilt APK on an Android handset, verify Timeline and Color interactions against real media, and retain reproducible evidence. Host source checks are useful preparation, not a substitute for phone validation.

## Status vocabulary

- `PASS`: the named acceptance check ran and passed with saved evidence.
- `PARTIAL`: some explicitly stated scope is verified, but the full requirement is not.
- `UNVERIFIED`: source/tests exist, but the required compile, runtime, or device evidence is absent.
- `NOT IMPLEMENTED`: no supported implementation exists for the stated behavior.
- `BLOCKED`: a required toolchain or device is absent.
- `SOURCE ONLY`: code or tests exist but have not compiled; never report this as a pass.

## Current evidence snapshot

| Gate | Result | Evidence / limits |
|---|---|---|
| Timeline Core v2 host gate | **Host scope PASS; overall gate BLOCKED** | `docs/evidence/GATE_20261120T063018Z.log`: native export regression tests pass; C++23 direct suite passes 170,624 assertions, including 10,000 snap-oracle inputs, 10,000 randomized multi-track history operations and 10,000 undo/redo cycles; CMake Debug, Release, ASan/UBSan and coverage pass; coverage is 84.00% lines / 65.59% branches taken; host benchmark runs. Gate exit 2 is solely the final Java/JDK blocker. Host microbenchmarks are not phone performance evidence. |
| Color focused/full JVM tests and Android assemble | **BLOCKED** | No `java`, `javac`, Android SDK/NDK, `adb`, or APK output in this checkout. No Kotlin/JUnit or Android compilation claim is made. |
| Color device instrumentation | **SOURCE ONLY / BLOCKED** | A Compose instrumentation smoke test opens the launcher and Color workspace, but it has not compiled or run. `tools/run_color_grading_mobile.sh` records the missing prerequisites in `docs/evidence/COLOR_GRADING_MOBILE_20261120T062725Z.log`; the later script revision adds stricter JDK/SDK/NDK preflight. |
| Color preview wiring | **SOURCE ONLY / UNVERIFIED** | Source routes clip grades and enabled node layers through `GradeEvaluationEngine` into the Media3 preview parameters. Mapper/evaluator regression tests exist, but no shader compile, Android playback, or pixel comparison has run. |
| Color persistence/history | **SOURCE ONLY / UNVERIFIED** | Color edit gestures snapshot the complete project and trigger save hooks; no Android persistence/reopen or undo/redo test has run. |
| Color export / render-graph parity | **SOURCE / UNVERIFIED** | Export now evaluates every clip through the shared `GradeEvaluationEngine.evaluateClipGrade` composer (keyframed adjustment block + persisted clip grade + enabled color nodes) and renders it with the exact `VideoAdjustmentEffect` shader used by preview. Validation accepts the full shader-rendered set (primary, curves, LUT, skin tone, qualifier, selective HSL, grain, vignette, channel mixing, log/tone-mapping) and fail-closes on animated grades, node blend modes, and Color Match. Export `prepare()` pre-loads referenced LUT files; a missing LUT fails the export instead of silently dropping it. LUT import now re-registers persisted files after app restart (`LutRepository.initialize`) and deletion removes entry, cache, and file. JVM/Robolectric tests cover the composer, validation, and LUT lifecycle, but they have not compiled or run; on-device MP4 output and pixel parity remain `UNVERIFIED`. Animated grade keyframes and full multi-layer render graphs stay `NOT IMPLEMENTED` in export. |
| Timeline Android/JNI/Compose | **SOURCE ONLY / UNVERIFIED** | Adapter, JNI, ViewModel, canvas, gestures, and unit-test sources exist. ABI, Kotlin compile, Android tests and device gestures have not been validated. |
| Phone performance / 16 KB ELF | **UNVERIFIED** | No physical-device Macrobenchmark/Perfetto/jank/memory/thermal measurements or Android binary to inspect. Host numbers must not be substituted. |

Evidence filenames preserve sandbox-generated timestamps as artifact IDs; they do not establish project chronology. Current branch `HEAD` contains only the uploaded ZIP and README; project sources/docs are untracked in this checkout. Do not run `git add .` or treat a clean tracked-only diff as full-source review.

## Ordered backlog and acceptance gates

| Order | ID | Work / status | Dependencies and action | Pass criteria / evidence |
|---:|---|---|---|---|
| 0 | MOB-0 | **BLOCKED** — Android toolchain + authorized handset | Provide a machine/session with JDK 17+, Android SDK/platforms/build-tools, SDK CMake 3.22.1, pinned NDK `27.2.12479018`, `adb`, and one authorized device. Prefer a physical handset; an emulator can prove launch/instrumentation, but not real codec behavior or phone performance. The mobile runner discovers the first authorized device or honors `ANDROID_SERIAL`. | The runner's preflight finds the matching JDK, SDK/NDK and device; metadata records model/API/ABI. Missing prerequisites stop before Gradle and are labeled `BLOCKED`. |
| 1 | CG-1 | **UNVERIFIED** — Color mapper/evaluator and full JVM suite | Run the unchanged `tools/run_color_grading_mobile.sh`; it executes focused `ColorGradeAdjustmentMapperTest` + `GradeEvaluationEngineTest`, then the full `:app:testDebugUnitTest` suite. Fix any compile/test failure before moving to runtime claims. | Gradle exit 0 and test reports under `app/build/reports/tests/`; save the complete runner log. |
| 2 | CG-2 | **SOURCE ONLY / UNVERIFIED** — rebuilt APK + Color workspace smoke | The same runner assembles debug, runs `:app:connectedDebugAndroidTest`, installs and launches the rebuilt APK. The instrumentation test opens the real launcher and Color workspace. | All Gradle tasks pass; APK SHA-256 is recorded; instrumentation passes on the named device; install and launcher report success; no startup crash in `AndroidRuntime` logs. |
| 3 | CG-3 | **UNVERIFIED** — actual preview, edit, persistence and history | With a physical handset and a repeatable short SDR H.264 clip, use the manual test matrix below. Test regular clip mode and node mode separately; verify paused-frame redraw and moving playback frames, not only slider/UI state. | Record handset model/API/ABI, APK and sample hashes, screenshots/video, observed values, undo/redo, force-stop/reopen, reset, and relevant Logcat. No `PASS` without artifacts. |
| 4 | CG-4 | **SOURCE / UNVERIFIED** — export scope and preview/export parity | Export a clip with the full shader-rendered grade set (primary + curves + LUT + nodes); inspect decoded output frames against the preview. Verify animated grades, Color Match, and node blend modes still fail validation before rendering, and that a missing LUT file fails `prepare()` instead of silently dropping the LUT. | Real MP4, export log, input/output hashes, decoded frame comparison, and explicit unsupported-case result. Frame-animated grade export and full multi-layer graph parity remain `NOT IMPLEMENTED`. |
| 5 | CG-5 | **UNVERIFIED** — Color state/legacy edge cases | After the focused suite compiles, test project save/load, keyframes, regular-slider reset, grade reset, clip/node isolation, duplicate mirror suppression, paste semantics and Reset All. Open source questions are recorded in [`COLOR_GRADING_BACKLOG.md`](COLOR_GRADING_BACKLOG.md); make only test-backed fixes. | Tests establish one state source per control and no edit disappearing/reappearing after reset, history, save, or reopen. Device evidence confirms renderer behavior. |
| 6 | CG-6 | **SOURCE / UNVERIFIED** — LUT deletion lifecycle | `LutRepository.initialize` re-registers persisted `.cube` files after restart and `deleteLut` removes entry, cache, and file; `LutPanel` wires delete and clears the selection. Robolectric tests cover the lifecycle but have not run; on-device import/restart/delete behavior needs the manual pass. | Import, restart, select and delete lifecycle tests; removing one LUT must not invalidate another project or silently change an unrelated grade. |
| 7 | TC-1 | **BLOCKED / SOURCE ONLY** — Android Timeline Core JNI + Compose tests | Run the Android build and existing adapter payload, native-unavailable, serialization, gesture-cancel, virtualization (500 clips / 20 tracks) and app tests. Validate JNI signatures/linking. Keep native-unavailable edits/snap fail-closed; do not introduce a Kotlin math fallback or float-second edit math. | Gradle/JNI ABI and test reports, plus on-device timeline trim/move/snap/undo/redo/cancel and lock interaction evidence. Rejected native edits must leave state/history unchanged. |
| 8 | TC-2 | **NOT IMPLEMENTED** — persistent linked-group membership UI | Current lock controls only recognize persisted group IDs; no user flow creates/reassigns `linkedGroupId`. Define atomic link/unlink/reassign semantics at the native command boundary, then implement native undo/redo tests and UI. | Link/reassign/unlink on a project, save/reopen, lock behavior across every group member, and native undo/redo on device. |
| 9 | TC-3 | **PARTIAL / UNVERIFIED** — cross-domain history | Native timeline history is tested; Kotlin metadata/color/history remains snapshot-based and cross-domain parity is incomplete. Verify ordering between native timeline edits and color/metadata snapshots, external changes, undo/redo and save/reopen. | Android regression tests demonstrate deterministic ordering and no native history divergence/conflict; document any intentionally separate history boundary. |
| 10 | PERF-1 | **UNVERIFIED** — phone performance, safety and package checks | On named representative physical hardware, profile 500+ clips / 20+ tracks, scrubbing and playback with Macrobenchmark/Perfetto; record frame/jank, latency, memory and thermal behavior. Inspect produced arm64 ELF page alignment (16 KB) and native sanitizer/build configuration. | Raw Perfetto/Macrobenchmark report, device/build metadata, ELF inspection output and repeatable thresholds. Host microbenchmarks do not satisfy this gate. |
| 11 | RENDER-1 | **NOT IMPLEMENTED / UNVERIFIED** — complete preview/export render graph | Only bounded Media3 export support exists; full multi-layer compositing, transforms, audio, advanced grades/LUTs and renderer cache-key parity are not an established shared graph. Design and implement only after current Android/export tests expose concrete requirements. | Common graph contracts plus device preview and decoded exported-frame parity tests across supported effects. Unsupported features remain rejected until that evidence exists. |

## Physical-device color test matrix

Use one short, known-good SDR H.264 clip for the full run. The runner automates focused/full JVM tests, debug APK build, instrumentation, install and launch; it does **not** automate visual or MP4 acceptance.

1. **Baseline:** record phone model, Android API, ABI, app version, APK SHA-256 and clip SHA-256. Import the clip and capture a neutral preview frame. Confirm there are no decoder/shader errors in Logcat.
2. **Clip grade:** change Temperature, Tint, Contrast, Saturation and a primary wheel. Check the paused frame redraws and at least two moving playback frames visibly change. Capture before/after screenshots; do not infer pixel processing from a changed slider.
3. **Node grade:** add/select a node, change a wheel and LUT/intensity, change opacity, disable and reorder the node. Confirm each operation changes preview and remains local to that node after switching tabs and reopening the project.
4. **History/state:** undo and redo each edit; force-stop/reopen; verify clip and node values restore without mirror double-application. Use Reset All and verify all relevant preview controls return to neutral.
5. **Export:** export supported clip-level curves/primary only and inspect decoded output frames. Attempt unsupported advanced/LUT/multi-node export and verify an explicit validation failure before a misleading output is written.
6. **Timeline interaction:** move/trim/snap a clip, cancel a gesture, undo/redo, exercise clip/group lock and ensure rejected native edits do not mutate state. Record touch behavior, jank and errors separately from host tests.
7. Save raw logs, screenshots and exports under `docs/evidence/`; name the device and media hashes. If any step cannot be performed, report it as `UNVERIFIED` or `BLOCKED`, not passed.

## Repeatable commands

The single mobile entry point is:

```bash
tools/run_color_grading_mobile.sh
```

It uses JDK 17+, the Android SDK, the exact FFmpeg NDK pin, and an authorized `adb` device; then it runs focused tests, the full JVM suite, `:app:assembleDebug`, connected instrumentation, install and launcher start. It honors `ANDROID_SERIAL` or selects the first authorized device. For manual diagnosis, the underlying gates are:

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests com.example.model.colorgrade.engine.ColorGradeAdjustmentMapperTest \
  --tests com.example.timeline.engine.GradeEvaluationEngineTest
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

The current host-only suite can be repeated with `tools/gate.sh` after provisioning CMake/Ninja. A host `GATE BLOCKED/FAILED (exit 2)` caused solely by missing Java still means Android/JVM tasks did not start; report host pass and Android blocker separately.

## Low-input execution rule

Continue in this order without adding another agent: toolchain/device preflight → focused and full JVM tests → fix → APK/instrumentation → physical-device preview/timeline checks → export comparison → broader Timeline/JNI/performance gates. Keep all work on the active branch. Do not ask for product input when behavior is already specified; pause only for a genuinely unavailable external prerequisite or a real semantic ambiguity. Never claim mobile, shader, persistence, history, or export behavior from source inspection alone.
