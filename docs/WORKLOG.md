# Timeline Core v2 Worklog

> Chronology note: evidence filenames retain sandbox-generated timestamps. Preserve them as raw provenance, but do not infer chronology from filenames alone. Historical entries whose date was out of order are left date-neutral rather than assigned an unsupported date.

## 2026-10-10 — P0 repository baseline and toolchain reconnaissance
Done: inventoried the original archive/extracted Android project, mapped timeline edit/JNI/Compose authorities, wrote the phased plan and architecture/research/stack docs, and established an explicit extracted-source baseline.
Failed / root cause → rule: the first ephemeral CMake request used unverified `cmake==3.31.8` and failed; verify package versions first. `git diff --check` also flags trailing whitespace in imported source; preserve the upstream snapshot and scope checks to authored files, not mass-normalize vendor code. Java/JDK and Android tools are absent, so do not report Android/device builds.
Evidence: GCC 12.2 C++23 `<expected>` probe passed and `-std=c++2c` was rejected; `python3 tools/run_native_export_tests.py` passed; FFmpeg tag `n8.1.3` resolved to the script's pinned commit.
Remaining: P1 integer time/edit/history core, regression suite, host build gate, and Android/JNI migration.
Next: establish the P0 baseline on the current branch, then complete P1 host-core tests before beginning Android/JNI migration.

## 2026-11-11 — P1 initial native compile failure
Done: added the initial C++23 core model, edit/snap/history APIs, and host regression suite.
Failed / root cause → rule: the first warnings-as-errors build found strong-type assignments from raw integers, a missing `<algorithm>` include, absent `SnapResult` equality, and unused helpers; use explicit value wrappers, compile after each cohesive patch, and keep warnings-as-errors enabled.
Evidence: `./tools/run_timeline_core_tests.sh` exited 1 with those compiler diagnostics; no test executable ran in that attempt.
Remaining: fix diagnostics, run host tests/CMake/ASan, then wire the core into JNI and UI; Android toolchain/device gates remain unavailable.
Next: rerun the same host compile and address any new failing regression before integration.

## 2026-11-11 — P1 compile-fix patch was overwritten
Done: attempted the first compile fixes in the core.
Failed / root cause → rule: parallel `edit_file` calls targeted the same C++ source and raced, so some replacements were overwritten; never edit one file concurrently—batch parallel work only across disjoint files.
Evidence: the second `./tools/run_timeline_core_tests.sh` reproduced the prior compiler diagnostics and exposed that the earlier edits had not all persisted.
Remaining: apply one consolidated patch to the C++ source/header, then re-read the edited ranges before compiling.
Next: serialize same-file edits and rerun the warnings-as-errors host compile.

## 2026-11-11 — P1 CMake preset first configure failure
Done: the direct C++23 test runner now compiles and passes; added root CMake presets for host/Android builds.
Failed / root cause → rule: CMake could not find Ninja because the temporary PyPI tool directory was not on `PATH`; invoke temporary tools with both `PATH=/tmp/arena-timeline-tools/bin:$PATH` and `PYTHONPATH=/tmp/arena-timeline-tools`.
Evidence: `./tools/run_timeline_core_tests.sh` passed (20,200 assertions); `cmake --preset host-debug` exited 1 before compiler detection due to missing build program in `PATH`.
Remaining: rerun CMake with the correct tool environment; add the native build/test output to the gate log.
Next: configure/build/CTest, then run ASan/UBSan and expand invariant tests.

## 2026-11-11 — P1 CTest registered zero tests
Done: CMake host configure/build completed after adding the temporary Ninja directory to `PATH`.
Failed / root cause → rule: `enable_testing()` was only in a subdirectory, so top-level `ctest` said `No tests were found!!!` yet exited 0; enable CTest at the root and require a nonzero test count in the gate.
Evidence: `cmake --preset host-debug` and `cmake --build --preset host-debug` succeeded; `ctest --preset host-debug` reported zero tests.
Remaining: fix root CTest registration and verify the named test actually runs before trusting CTest status.
Next: add `include(CTest)` at root and rerun configure/build/CTest.

## 2026-11-11 — P1 root CTest registration fixed
Done: moved test discovery to top-level via `include(CTest)` and removed subdirectory-only `enable_testing()`.
Failed / root cause → rule: subdirectory-only test enablement can silently produce an empty top-level CTest run; define CTest at the root and inspect the discovered test count/output.
Evidence: host configure/build succeeded; CTest ran `timeline_core_v2_tests` (1/1 passed); direct runner passed 20,200 assertions and 10,000 deterministic snap inputs.
Remaining: run the full repository gate and sanitizer build; tests cover initial semantics only, not all INV-01..16 acceptance evidence.
Next: fix stale worklog date and execute `tools/gate.sh` with documented host tools.

## 2026-11-11 — P1 release preset invoked before configuration
Done: added host release, coverage, and benchmark presets for stronger core validation.
Failed / root cause → rule: `cmake --build --preset host-release` exited 1 because that binary directory had never been configured; configure each preset before building it.
Evidence: the first release build printed `Error: .../build/cmake/host-release` was not a directory; after `cmake --preset host-release`, build and CTest passed.
Remaining: keep CI/gate commands ordered configure → build → test for every binary directory.
Next: run release checks after the final source patch and retain the host-only scope label.

## 2026-11-11 — P1 gcov coverage command corrected
Done: added a GCC/gcov line and branch-taken coverage threshold to the host presets and gate.
Failed / root cause → rule: the first gcov helper passed the source object directory, so gcov searched for `timeline_core.gcno` instead of `timeline_core.cpp.gcno`; `-n` also suppressed the summary. Run gcov on the exact `.gcda` from its object directory and parse the source summary.
Evidence: initial `python3 tools/check_timeline_core_coverage.py` exited 1; corrected gate measured 81.85% source lines and 63.57% branches taken (thresholds 80%/60%).
Remaining: coverage reflects only the standalone C++ core and does not cover JNI, Kotlin, Compose, or device paths.
Next: preserve the gcov command and exact report in the gate log; do not describe seeded mutation as libFuzzer.

## 2026-11-11 — P1 CMake preset JSON validation
Done: added `host-benchmark` and `host-coverage` presets.
Failed / root cause → rule: an invisible U+200C inserted during a preset edit made `CMakePresets.json` invalid; parse JSON immediately after editing structured config and remove stray Unicode punctuation.
Evidence: `python3 -m json`/`json.loads` first reported a JSON decode error; the corrected file parsed and all host presets configured in the final gate.
Remaining: Android arm64 preset is still unconfigured because the NDK is absent.
Next: keep config parsing in the final gate/review before claiming an Android toolchain result.

## 2026-11-11 — P1 host core validation checkpoint
Done: completed integer Flicks, checked edits, bounded snapshot history, deterministic snap, schema-v1 codec, named INV-01..12 host tests, seeded mutation tests, and host microbenchmarks.
Failed / root cause → rule: earlier compile/type errors, a raced same-file edit, zero-test CTest registration, and tool-path/coverage invocation failures are recorded above; serialize edits, configure CTest at root, and capture actual discovered tests/coverage.
Evidence: `tools/gate.sh` log `docs/evidence/GATE_20261111T134445Z.log`; 92,968 assertions; debug/ASan/UBSan/coverage CTest pass; 81.85% lines and 63.57% branch-taken; host benchmark recorded in `docs/PERF.md`.
Remaining: gate exits 2 because Java/JDK is absent; app JNI/ViewModel/Compose migration, INV-13..16, and device performance remain NOT IMPLEMENTED/UNVERIFIED.
Next: refresh the final host gate after late semantic changes, then commit the standalone P1 core/gate checkpoint; continue Android integration only with verified toolchain evidence.

## P1 final host validation refresh (historical date unresolved)
Done: expanded the unsupported-ripple regression to cover Move, Split, Slip, Roll, and Slide; made the gate distinguish test exit code 2 from explicit environmental blockers; refreshed performance notes and captured Release evidence.
Failed / root cause → rule: the complete gate exits 2 only because Java/JDK is absent, so report the Android gate as BLOCKED while preserving independent host pass results.
Evidence: direct tests passed 92,976 assertions; `GATE_20261111T135732Z.log` passed Debug/Release/ASan/UBSan/coverage CTest (81.85% lines; 63.57% branches taken); the host benchmark is recorded in `docs/PERF.md`; the separate Release run is retained in `HOST_RELEASE_20261111T135240Z.log`.
Remaining: Kotlin/JNI adapter, duplicate-engine migration, Compose virtualization, Android build/device measurements, and INV-13..16 remain NOT IMPLEMENTED/UNVERIFIED.
Next: wire the authoritative API through a verified JNI/Kotlin boundary and retire duplicate edit semantics; keep Android compilation explicitly BLOCKED until a JDK/SDK/NDK is available.

## Android assemble attempt and toolchain recovery (historical date unresolved)
Done: ran the explicitly requested `./gradlew :app:assembleDebug` and tried to provision a temporary Java runtime from the allowed package host.
Failed / root cause → rule: the wrapper exited 1 because the default environment has no Java/JDK. A GitHub Temurin asset redirected to a non-allowlisted host and failed; the PyPI `jdk4py` 17.0.9.2 wheel provides a Java runtime but no `javac`, so do not call it a build JDK or retry blocked hosts.
Evidence: `docs/evidence/ANDROID_ASSEMBLE_BLOCKED.log` records the wrapper error; the temporary runtime reported Java 17.0.9 but its `bin/` has no `javac`.
Remaining: app JNI/ViewModel/Compose migration and duplicate-engine retirement are NOT IMPLEMENTED; Android compile/device claims remain unavailable. The missing toolchain does not justify stopping source-side work.
Next: continue the source migration/audit, then rerun Android gates if a complete JDK/SDK/NDK toolchain becomes available through permitted sources.

## P8 host gate and documentation reconciliation checkpoint (date not asserted)
Done: reconciled timeline status, toolchain, and workflow-run evidence; refreshed current host gate and performance records; preserved exact logs.
Failed / root cause → rule: the first gate rerun exited 2 because CMake/CTest/Ninja were absent from `PATH`; provision verified isolated host tools before retrying. The full staged `git diff --cached --check` also flagged trailing whitespace in imported source/raw logs; scope checks to authored files and do not mass-normalize imported/vendor content. In this documentation batch, parallel edits targeted the same WORKLOG and PLAN files and raced; serialize same-file edits and read back before recording success. The Android assemble attempt exited before Gradle because Java/JDK is absent; keep independent host gates running and label Android `BLOCKED`.
Evidence: `GATE_20261112T123452Z.log` — native export/core/probe, CMake Debug/Release/ASan+UBSan/coverage, coverage thresholds, and host benchmark passed; latest direct suite reported 170,596 assertions. `./gradlew :app:assembleDebug` failed before Gradle for missing Java.
Remaining: Kotlin/JNI ABI/Gradle, `:app:assembleDebug`, INV-15 runtime, Android device performance, render-graph INV-13/14, and hardware-buffer INV-16 remain blocked, unverified, or not implemented as documented.
Next: rerun Android/JNI/device gates only when a complete permitted toolchain and device are available; do not translate host results into Android claims.

## P2/P4 group-lock transport and group-wide rejection checkpoint (date not asserted)
Done: persisted `TimelineClip.isGroupLocked`, centralized linked-group identity in metadata, mapped group IDs/flags through the Kotlin/JNI payload, and made native edits reject a group member when any member is locked.
Failed / root cause → rule: the first refreshed gate was blocked because ignored CMake/Ninja tools did not survive the workspace refresh; reinstall the verified pinned tools before retrying. Kotlin/Gradle remains unavailable without JDK, so treat its new adapter test as source-only.
Evidence: `GATE_20261116T120642Z.log`; 170,602 assertions; Debug/Release/ASan+UBSan/coverage CTest passed 1/1; coverage 83.89% lines and 65.30% branches taken; native export tests passed.
Remaining: Android/JNI/Kotlin compilation, persistent-project compatibility test execution, group-lock UI toggle, and device measurements remain `UNVERIFIED`/`NOT IMPLEMENTED`.
Next: validate Kotlin serialization and adapter/JNI ABI when a full JDK/SDK/NDK is available, then implement a user-facing group-lock control without bypassing native edit validation.

## 2026-11-16 — P2/P4/P5 undoable group-lock control
Done: corrected the current-session date references; added native `SetGroupLock` atomic group/clip changes with C++ undo/redo; wired both long-press menus through the native history path and added Kotlin payload, UI-target, and legacy-serialization test sources.
Failed / root cause → rule: current history is grafted at `dd626c4` and does not contain the earlier reported `14cbdd3`; extracted project files are untracked, so verify current-branch status/history before assuming a checkpoint exists. Concurrent edits to `TESTING.md` raced and lost one change; serialize same-file edits and read back. Staged whitespace checks also surfaced imported whitespace in untouched canvas/view-model lines; scope formatting checks to authored hunks and do not mass-normalize the extracted baseline.
Evidence: direct C++23 suite passed 170,624 assertions; `GATE_20261116T122225Z.log` passed export/core/probe, Debug/Release/ASan+UBSan/coverage CTest, coverage (84.00% lines; 65.59% branches), and host benchmark; gate exit 2 only because Java/JDK is absent. Kotlin/JNI tests remain unrun.
Remaining: no app action currently creates/reassigns `linkedGroupId`, so linked-group locking is limited to pre-existing persisted groups; Android/Kotlin/JNI ABI/serialization/Compose validation and `:app:assembleDebug` are blocked, while device performance and preview/export parity remain unverified/not implemented.
Next: add and test a user flow for persistent group membership; rerun Android/JNI/Gradle gates with a complete JDK/SDK/NDK, then measure device performance on hardware.

## 2026-10-10 — Color grading preview and edit-state checkpoint
Done: traced the Color screen through the Media3 adjustment graph; wired regular/node primary controls and node LUT selection/intensity, composed enabled color nodes in order, recovered legacy clip grades missing their adjustment mirror (including frame-evaluated grade keyframes), kept node edits out of the clip-grade mirror, grouped Skin Tone slider history, made Reset All clear clip and node grades, and made color gestures snapshot the full project state.
Failed / root cause → rule: `GradeEvaluationEngine` previously ignored separate clip grades and node state; direct slider edits also needed a single history/save boundary. Feed persisted grade state into the same frame evaluator, avoid double-applying mirrored values, and record the complete edit snapshot.
Evidence: added `ColorGradeAdjustmentMapperTest` and `GradeEvaluationEngineTest` source cases, including legacy/mirrored/keyframed clip grades and node LUT mapping; delimiter/source-link checks passed, but JUnit was not run because this checkout has no Java/JDK, so Kotlin/Android behavior remains `UNVERIFIED`.
Remaining: Media3 GLSL/device playback and MotionCam advanced grades remain `UNVERIFIED`; unsupported advanced grades/nontrivial node stacks are rejected by export validation, while only the clip-level primary/curve subset is accepted in source and actual MP4 output plus preview/export parity remain `UNVERIFIED`/`NOT IMPLEMENTED`.
Next: run the focused Gradle unit tests and Android build when a complete JDK/SDK/NDK is available, then validate on a device with a known sample clip.

## Color grading mobile backlog and one-pass device gate (date not asserted)
Done: added `docs/REMAINING_WORK.md` (prioritized mobile-first gates from toolchain preflight through JVM tests, APK/instrumentation, handset color matrix, export comparison, Timeline/JNI, and device performance) and linked it from `docs/README.md`, `docs/PLAN.md`, and the color backlog; hardened `tools/run_color_grading_mobile.sh` with JDK 17+/javac checks, SDK/CMake 3.22.1 checks, the exact FFmpeg NDK pin `27.2.12479018`, path setup from `JAVA_HOME`/SDK roots, device metadata capture, and explicit launcher/logcat verification; replaced the placeholder instrumentation test with a real launcher + Color-workspace smoke test; refreshed status docs with the latest gate and preflight results.
Failed / root cause → rule: the first mobile-gate attempt stopped before Gradle because `java`/`javac`/`adb` and the Android SDK are absent, so treat that as `BLOCKED` rather than a test failure; the CMake pip launcher script needs `PYTHONPATH`, so invoke the wheel's `cmake/data/bin` executables directly; keep evidence filenames as artifact IDs, not chronology.
Evidence: refreshed host gate `docs/evidence/GATE_20261120T063018Z.log` — native export/core/probe pass, direct suite 170,624 assertions, Debug/Release/ASan+UBSan/coverage CTest pass, coverage 84.00% lines / 65.59% branches taken, host benchmark runs; blocked mobile preflight `docs/evidence/COLOR_GRADING_MOBILE_20261120T062725Z.log`; `git diff --check` passes on authored files. All Android/JVM tasks remain unexecuted — no build, test, or device claim is made.
Remaining: every Android/JVM/device gate (CG-1..CG-6, TC-1..TC-3, PERF-1, RENDER-1 in `REMAINING_WORK.md`) is `BLOCKED`, `UNVERIFIED`, `SOURCE ONLY`, or `NOT IMPLEMENTED` until a JDK 17+/SDK/NDK environment with an authorized handset runs the checked-in mobile gate.
Next: run `tools/run_color_grading_mobile.sh` unchanged in an Android-capable environment, fix any compile/test failures it exposes, then execute the physical-device color matrix and export comparison.

## Preview/export render-graph parity groundwork (date not asserted)
Done: extracted `GradeEvaluationEngine.evaluateClipGrade` as the single per-clip composer shared by preview and export; the Media3 export backend now renders the composed clip grade + enabled color nodes through the same `VideoAdjustmentEffect` shader as preview instead of merging only curves; validation accepts the full shader-rendered set (primary, curves, LUT, skin tone, qualifier, selective HSL, grain, vignette, channel mixing, log/tone mapping) with range checks, and fail-closes on animated grades, grade/node blend modes, and Color Match; export `prepare()` pre-loads referenced LUT files and fails closed when one is missing; `LutRepository` gained restart persistence (`initialize` re-registers persisted `.cube` files) and real deletion; `LutPanel` wires delete and clears the selection; removed the unused `defaultParams` in `resetAdjustmentParam`.
Failed / root cause → rule: `ColorGradeLayer` has no `blendMode` field (only `ColorGradeStack` does), so the node blend check reads `layer.grade.blendMode`; `ColorMatchParams` uses `enabled`/`targetMatchAmount`, not `referenceStrength`; deleting the conservative reject list required enumerating the GLSL uniforms actually consumed by the fragment shader before accepting a parameter.
Evidence: Kotlin delimiter/source-link checks and `git diff --check` passed on all edited files. The JVM/Robolectric tests added for the composer, validation, and LUT lifecycle have NOT compiled or run in this checkout (no JDK); the GitHub Actions workflow runs them on push. No APK or device export evidence exists yet — pixel parity remains `UNVERIFIED`.
Remaining: run the JVM suite and build the APK in CI; then on-device export of a full shader-rendered grade and decoded-frame comparison against preview; animated grade keyframes and full multi-layer graph parity stay `NOT IMPLEMENTED` in export.
Next: push with the `[publish-test-release]` marker so CI compiles, runs JVM tests, and publishes the test APK for the user's device pass.

## CI compile/test loop and test APK #92 (date not asserted)
Done: pushed the consolidated branch state and drove the GitHub Actions pipeline to green through five fix iterations: `mix()` arity in `ColorGradeAdjustmentMapper`, missing `TimelineClip` import in `GradeEvaluationEngine`, Media3 smart-cast local in `TimelinePreviewController`, explicit `VideoCapabilities.bitrateRange` probe in `McrawVideoTranscoder` (the SDK stubs do not expose `EncoderCapabilities.getBitrateRange` to Kotlin), `commitEdits` in the adapter test, stale selective-HSL/qualifier expectations updated to the shared-shader behavior, `LutRepository.resetForTests` for Robolectric classloader reuse, and the mcraw memory-bound test data moved strictly above the enforced 24 MP limit; also added per-error Kotlin annotations to the workflow because the log/artifact blob hosts are unreachable from the sandbox.
Failed / root cause → rule: this checkout has no JDK, so previously authored code had never compiled — CI compile errors are data, not regressions; fix every annotated error in one pass and re-run; artifact/log downloads from `*.blob.core.windows.net`/`release-assets.githubusercontent.com` fail with EOF here, so use check-run annotations for diagnostics and publish APKs via GitHub Releases for handoff.
Evidence: run 44700425318 (#92) — compileDebugKotlin, externalNativeBuildDebug (FFmpeg n8.1.3 + NDK 27.2.12479018), full testDebugUnitTest suite, native export tests, assembleDebug, license verification, APK artifact, and pre-release `android-test-92-1` (APK 89,827,481 bytes + SHA-256) all succeeded at commit `b9e4c6c`. Release URL: https://github.com/black-glass-dev/Cinema-cut-pro/releases/tag/android-test-92-1. The APK could not be copied into this workspace (blocked download host); the user downloads it from the release page.
Remaining: the user's handset pass per `docs/DEVICE_TEST_GUIDE.md` (preview/grades/nodes/history/LUT lifecycle/export parity/timeline smoke); on-device results determine whether export parity and persistence claims become verified.
Next: user runs the device checklist and reports results; fix anything the device pass exposes.

## 2026-10-10 — Timeline playhead interaction diagnosis and fix
Done: aligned ruler/playhead/virtualization to the timeline viewport after the 80 dp track header; made the visible playhead line a scrub hit target over clips; clamped scrubbing to the sequence end; coalesced Media3 paused seeks to frame-responsive cadence.
Failed / root cause → rule: the overlay's x-origin ignored the track-header column, while track-area drags were classified as clip edits and every playhead emission forced a decoder seek; align overlay and content coordinate spaces, prioritize cursor hit-testing, and avoid forced seeks for each pointer tick.
Evidence: run 44719380177 passed `compileDebugKotlin`, `externalNativeBuildDebug`, full `testDebugUnitTest`, native export tests, FFmpeg output validation, license verification, and `assembleDebug`; release `android-test-94-1` is a 89,827,481-byte APK. SHA-256: `9e28ad97bb78b8292b2b9701b210a88aa6eb41714ab0f72241e39fb820190bff`. Regression tests for both gesture controllers, sequence bounds, and Media3 seek policy passed in the JVM suite; standalone `python3 tools/run_native_export_tests.py` passed here too.
Remaining: handset drag/scrub verification remains UNVERIFIED; clip edit bounds still rely on Core v2 validation and handset proof.
Next: user can test the fresh APK while source-side MOV/error fallback work continues; apply any reported findings without waiting on device results.

## MOV unsupported-source fallback
Done: added a guarded Media3 `UNSUPPORTED_MEDIA` → bundled FFmpeg fallback when auto-fallback is enabled; bypassed proxy/effect failures and active color-grading sessions, and prevent one-time fallback from looping back to Media3.
Failed / root cause → rule: fallback previously handled native-to-Media3 only, leaving a structured Media3 container/codec rejection on the default route without an alternate decoder; only pivot on unsupported-source errors, not generic playback or shader errors.
Evidence: run 44721084836 passed `compileDebugKotlin`, `externalNativeBuildDebug`, full `testDebugUnitTest`, native export tests, FFmpeg output validation, license verification, and `assembleDebug`; release `android-test-95-1` is a 89,827,481-byte APK (SHA-256 `bb061dc34ef5b95d544af5783df335c9860bd9d52c8d3d899be12000f099cbf2`). Standalone `python3 tools/run_native_export_tests.py` passed too.
Remaining: handset MOV codec/runtime remains UNVERIFIED; FFmpeg color-grade capability is limited, so this route is suppressed while the Color workspace is active.
Next: user can test release `android-test-95-1`; continue tracing/repairing remaining preview-grade and Timeline issues source-side.

## 2026-10-10 — Preview fallback error-key follow-up
Done: keyed playback-error deduplication by asset, engine, message, and structured category so identical Media3 failures on different clips do not suppress fallback.
Failed / root cause → rule: the old per-engine/message key could treat the same failure on a second asset as already handled; include source identity and error category in retry keys.
Evidence: run 44723887294 on `812ba7a` passed Kotlin/native compilation, full JVM tests, native export tests, FFmpeg validation, license checks, APK assembly, and release publication. Fresh release `android-test-97-1`: `Cinema-Cut-Pro-debug-97-1.apk`, 89,827,481 bytes, SHA-256 `769b79aaaeb80b143a1a68bf3534b03a5ecd13dd51f7fe0d843671c2d368825b`.
Remaining: all physical-device playback, grade, and Timeline behavior remain UNVERIFIED.
Next: continue source-side diagnosis/fixes for the remaining preview-grade, nodes/LUT/history/export usability issues; the user may run the handset checklist independently.

## 2026-10-10 — Portrait Timeline redesign from retained screenshot (CI pending)
Done: preserved the supplied screenshot in `docs/design-reference/`; moved editor tabs beneath the work area; restyled the toolbar, track ruler/labels, filmstrip clip, selected trim grips, and playhead to match the black/red reference while retaining Core v2 gestures and scrub line.
Failed / root cause → rule: the existing view used a blue/sprocket clip card, yellow selection and tabs above the timeline; adopt the reference's thumbnail-first red-selected clip, dark track surfaces, compact controls and bottom tabs without changing edit authority.
Evidence: source-level review and `git diff --check` only; Android/JVM compile, screenshot rendering and handset comparison remain pending. Screenshot preserved as a 2 MB repo asset.
Remaining: visual rendering, touch-target feel, and scrubbing/trim behavior on a physical phone remain UNVERIFIED; CI compilation is required before handoff.
Next: run the existing Android/JVM + native CI workflow, resolve any compile/test findings, then publish an installable APK and compare it on the user's handset against the preserved reference.

## 2026-10-10 — Timeline CI compile fix
Done: first CI run for `94a4d5f` exposed a missing Compose `fillMaxWidth` import in the rewritten clip view; added the import.
Failed / root cause → rule: compiler error was `FilmStripClipView.kt:78:18 Unresolved reference fillMaxWidth`; inspect each rewritten view's required layout imports before retrying CI.
Evidence: failed workflow 44727832563 on `94a4d5f`; fix is source-only and not yet compiled.
Remaining: rerun build/JVM/native/FFmpeg/release workflow; earlier workflow stopped at Kotlin/JVM compile and did not create APK.
Next: commit/push this focused import fix and watch resulting CI before reporting any pass or APK availability.

## 2026-10-10 — Timeline redesign CI verification
Done: Kotlin/native compile, JVM tests, native C++ host tests, FFmpeg syntax/output validation all passed on `fa4702a` (workflow 44728680261).
Failed / root cause → rule: APK build and publication were skipped because the fix commit did not contain the workflow's `[publish-test-release]` marker; workflow dispatch returned HTTP 403, so release must be requested by a marked commit on this branch.
Evidence: https://github.com/black-glass-dev/Cinema-cut-pro/actions/runs/44728680261 — compile/JVM and native/FFmpeg steps PASS; APK steps SKIPPED.
Remaining: no APK for this redesign yet; UI runtime and physical-phone comparison are UNVERIFIED and reserved for user handset testing.
Next: use a tiny, accurate worklog-only marked commit to trigger the workflow's approved APK release, then verify release metadata and SHA-256.

## 2026-10-10 — Timeline redesign APK handoff
Done: approved test release #101 published after all CI gates passed on `d15e321` (workflow 44729598621).
Failed / root cause → rule: none in this run; the earlier `94a4d5f` compile failure was fixed at `fa4702a` with the missing Compose import.
Evidence: workflow https://github.com/black-glass-dev/Cinema-cut-pro/actions/runs/44729598621; release https://github.com/black-glass-dev/Cinema-cut-pro/releases/tag/android-test-101-1; APK `Cinema-Cut-Pro-debug-101-1.apk`, 89,827,481 bytes, SHA-256 `094c1682b95c29d17fb79e947672df9bd66c15bd73b2bf5f94dddfcb7603156f`.
Remaining: device screenshot comparison, import-to-clip rendering, gestures, playhead/scrub and visual match on a physical phone are UNVERIFIED; user will perform handset checks.
Next: install the APK on the handset and compare Timeline against `docs/design-reference/CinemaCutPro_Editor_Toolbar.png`; report any mismatches or gesture regressions for follow-up.
## 2026-10-10 — Home landing page, editor layout, timeline seek and black-preview repair
Done: added a cold-launch Home route modeled on the supplied image with exact `#1A1A1E`, `#F2F2F4`/`#E4E4E8`, and `#FF3B30` palette, Create Project flow, JSON project import, stored recent-project list/grid/open actions; moved editor open behind Home; corrected tap-on-clip seek mapping, enlarged the preview/timeline resize target, added a first-frame poster and one-shot proxy/original/LibVLC fallback; kept the red playhead and native Core v2 edit authority.
Failed / root cause → rule: timeline `onTap` selected a hit clip but only sought when no clip was hit; the viewport had no visible frame while a source stalled without a Media3 error; and the app root rendered the editor unconditionally. Always seek a tap by local timeline X plus horizontal scroll, show a decoded poster until the first player frame, and make Home the cold-launch route. The branch only tracks the uploaded ZIP, so stage the extracted project files as well as this patch for a reproducible build.
Evidence: `./tools/run_timeline_core_tests.sh` PASS (170,624 assertions); `python3 tools/run_native_export_tests.py` PASS; local `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon` BLOCKED before Gradle because `java`/`JAVA_HOME` are absent. Screenshots retained in `docs/design-reference/` and `docs/design-reference/issue-captures/`.
Remaining: Kotlin/JVM compile and APK build await the pushed CI workflow; Home rendering, import/open, preview poster/decoder fallback, timeline tap/scrub placement, and resize behavior remain UNVERIFIED on handset, which the user will test.
Next: push the extracted source plus authored fixes on this branch, run CI, repair compile/test failures, publish and checksum the APK; do not claim runtime/visual correctness from source inspection.

## 2026-10-10 — Android CI and test APK handoff
Done: changes landed on the active branch and were compiled/tested in the Android CI toolchain; an installable debug test APK was published.
Verification: GitHub Actions run `44734870532` on `9b852a7687cdc4a92ee48515ab09a4cdf2b57f58` passed Kotlin/native compilation, JVM tests, native host tests, repeated FFmpeg Gradle output checks, APK assembly, packaged-license validation, and checksum preparation; host gates also passed locally (170,624 timeline assertions and native export tests).
APK: `https://github.com/black-glass-dev/Cinema-cut-pro/releases/download/android-test-103-1/Cinema-Cut-Pro-debug-103-1.apk` (89,876,633 bytes), SHA-256 `6b22c7c7836e490d26106a93c914fce2301bd5afc690164665c862726eaced31`; release #103 is a debug-signed device-test build, not a Play Store release.
Remaining: physical handset UI, imported-video playback, timeline gesture/resize, and appearance checks are UNVERIFIED and remain for the user; a local APK download was attempted but blocked by EOF from GitHub's release-asset host, so use the release link above.

## 2026-10-10 — New Project controls, project-ratio viewport and Media3 replay failure
Done: rebuilt the New Project screen around the supplied light-gray/two-column reference, added expandable custom aspect/resolution/FPS editors with validation, tied preset resolutions to the chosen canvas ratio, bounded the preview canvas to the actual resize pane, measured divider drags against the editor container, and collapsed verbose playback errors into a details dialog. Removed the always-installed empty Media3 effects list for ordinary playback; the supplied log points at replaying through that effects path with replayable cache disabled.
Evidence: Timeline Core v2 host suite PASS (170,624 assertions); native export suite PASS; `bash -n tools/build_ffmpeg_android.sh` PASS. Android/Kotlin compile remains BLOCKED locally because no `java` executable exists; CI must compile before APK publication.
Remaining: new project-screen pixel match, custom input keyboard interaction, selected-ratio canvas rendering, drag-divider behavior, and playback/repeated seeking with the reported local MP4 are UNVERIFIED on device. Media3 GPU grade activation still uses Media3 effects only when a grade is enabled; that path needs a separate handset test.
Next: run the Android CI gate on this branch, fix compile/test errors, publish an updated APK only after CI passes, and ask the user to verify the reference appearance and local video playback on their handset.

## 2026-10-10 — New Project CI fixes and APK #106
Compile iteration one found the scoped Compose `weight` import, ratio-preset pair predicate, and missing `TextButton` import; fixed in `1152867`. Android CI run `44746339832` PASS: Kotlin/native build, JVM tests, native host tests, FFmpeg checks, APK assembly, license verification, checksum and release. APK `Cinema-Cut-Pro-debug-106-1.apk` (89,925,785 bytes), SHA-256 `5f0f7be3d971935acaefede0d0efc1c6886f50d7e2d0e7ee0d4bf2815902c576`: https://github.com/black-glass-dev/Cinema-cut-pro/releases/tag/android-test-106-1. Device playback, canvas ratio/drag, custom-input behavior, and screenshot pixel match remain UNVERIFIED for the user’s handset pass; local Gradle is still unavailable without Java.

## 2026-10-10 — Timeline, multi-import/export, and brand asset checkpoint
Done: serialized async media imports to preserve both selected MP4s and clip/history snapshots; bounded trim drags to legal source/timeline frames; removed duplicate ruler/playhead header offsets; added gap seeking coverage, Input beside Export, and the supplied reel/Android launcher icons.
Evidence: `./tools/run_timeline_core_tests.sh` PASS (170,624 assertions); `python3 tools/run_native_export_tests.py` PASS; brand ZIP integrity PASS; added JVM validation cases for two sequential MP4s and split/trimmed clips.
Status: exact multi-import, split/trim export tests and Android compile are UNVERIFIED because this environment has no Java runtime; physical-device gestures/export are also UNVERIFIED. Color grading remains deferred.
Next: run Android CI on this focused commit, fix any compile/test failures, then ask for device verification of multi-import, trim edges, playhead placement, and real MP4 exports.

## 2026-10-10 — Timeline/import/export CI verification
Done: fixed the first CI compile miss (an explicit `TimelineFrameMath` import) in `a77017c`; the follow-up Android compile and JVM suite passed.
Evidence: run `44757248643` caught the omitted import before tests; run `44758484403` PASS for Kotlin/native compilation, JVM tests (including new two-MP4 and split/trim validation cases), native export tests, and FFmpeg checks; timeline host suite (170,624 assertions) and asset/XML integrity also PASS.
Remaining: the push workflow intentionally skipped APK publication; physical-device gestures and real media import/export remain UNVERIFIED. Color grading stays deferred.
Next: workflow-dispatch was denied (HTTP 403); request the approved debug APK through the documented marked-push path, then request handset checks for multi-video/export and timeline gestures.
