# Color grading + mobile validation backlog

**Owner:** current Arena coding agent (no additional agent)  
**Branch:** `arena/a2d6ebb0-cinema-cut-pro` (stay on this branch)  
**Goal:** make the Color grading source behavior testable, then verify it in a rebuilt Android APK on a real/emulated device without asking for repeated product decisions.

Status terms: `SOURCE` = code/test source exists but was not compiled; `UNVERIFIED` = required runtime evidence is missing; `BLOCKED` = required tool/device is absent; `NOT IMPLEMENTED` = no supported implementation exists.

## Current snapshot

- **SOURCE / UNVERIFIED:** clip grade + node layer evaluation is connected to the Media3 preview parameter path; clip/node primary controls and node LUT selection/intensity are wired in the current Color screen.
- **SOURCE / UNVERIFIED:** regression test sources cover neutral preservation, clip-grade backfill, mirrored-value de-duplication, keyframes, node opacity/LUT, curves, and reset behavior.
- **SOURCE / UNVERIFIED:** an Android launcher/Color instrumentation smoke test and `tools/run_color_grading_mobile.sh` have been added. The script preflights JDK 17+, the SDK, the pinned NDK, and an authorized device; then it runs focused and full JVM unit tests, builds the debug APK, runs connected instrumentation, installs and launches the APK, and prints the manual visual checklist.
- **BLOCKED (confirmed in this checkout):** `java`, `javac`, `adb`, Android SDK/NDK, an authorized device/emulator, and APK output are absent. The one-shot mobile gate recorded `docs/evidence/COLOR_GRADING_MOBILE_20261120T062725Z.log`; its shell-generated timestamp is evidence metadata, not a project chronology claim.
- **NOT IMPLEMENTED / UNVERIFIED:** export parity for advanced grades and nontrivial node stacks. The validator rejects unsupported configurations; the clip-level primary/curve subset is accepted in source but no MP4 output has been inspected on a device.
- The consolidated, prioritized backlog across Color grading, Timeline Core v2, export parity, and device performance now lives in [`REMAINING_WORK.md`](REMAINING_WORK.md); use that file as the execution order and this file for the detailed color procedure.

## Prioritized remaining work

| ID | Priority | Work | Status / next action | Acceptance evidence |
|---|---:|---|---|---|
| CG-M0 | P0 | Android toolchain + mobile smoke gate | `BLOCKED`: provide/run in an environment with JDK 17+, Android SDK platform/build tools, NDK/CMake, `adb`, and one authorized device/emulator. Then run `tools/run_color_grading_mobile.sh` unchanged. | One log records focused/full JVM tests, `:app:assembleDebug`, `:app:connectedDebugAndroidTest`, APK SHA-256, install and launcher start; no build/test claim without zero exit. |
| CG-M1 | P0 | Compile and run Color mapper/evaluator tests | `UNVERIFIED`: run `:app:testDebugUnitTest --tests com.example.model.colorgrade.engine.ColorGradeAdjustmentMapperTest --tests com.example.timeline.engine.GradeEvaluationEngineTest`; then run the full unit suite. Fix compile/test failures before device preview claims. | Gradle exit 0 and test report saved under `app/build/reports/tests/`. |
| CG-M2 | P0 | Device preview and editing acceptance | After CG-M0, import a short SDR H.264 clip and exercise regular sliders and nodes in paused + playing preview. Check save/reopen, undo/redo, reset, LUT and opacity. The repeatable steps are below. | Device model/API level, APK hash, test-clip hash, observed results, screenshots/logcat, and no silent shader fallback. |
| CG-M3 | P1 | Preview persistence/undo consistency | Review all Color panel callbacks and save/load paths after compilation. Confirm each gesture creates one undo item and that project reopen restores clip and node grades without mirror duplication. | Focused ViewModel tests where practical plus on-device undo/redo and reopen evidence. |
| CG-M4 | P1 | Export supported subset | Verify primary/curves on a short SDR export, then confirm unsupported LUT/advanced/multi-node configurations fail before rendering rather than silently disappear. | Export validator/test result and decoded output frame comparison; current status remains `UNVERIFIED` until a real MP4 is inspected. |
| CG-M5 | P2 | Complete per-node controls | Current source explicitly tells the user skin-tone masking controls remain clip-level; inspect Color Match and other advanced controls for accidental clip writes while node mode is active. Decide from model/API evidence whether to implement node storage or hide unsupported controls. | No node-mode gesture mutates `clip.colorGrade`/clip params; tests for any newly implemented mapping. |
| CG-M6 | P2 | LUT lifecycle | `LutPanel` delete is still a placeholder. Implement repository deletion only after checking cache/file ownership and add tests; until then keep delete disabled or clearly unavailable. | Imported LUT survives project reopen; deleting one LUT does not invalidate another project or crash the renderer. |
| TC-M1 | P1 | Kotlin/JNI and Compose validation | `BLOCKED` on the same Android toolchain. Run existing adapter payload, native-unavailable, gesture-cancel and virtualization tests on Android. Preserve fail-closed behavior; do not add a Kotlin edit/snap fallback. | Gradle/JNI ABI results and the relevant test reports. |
| TC-M2 | P2 | Linked-group membership flow | `NOT IMPLEMENTED`: lock UI recognizes persisted groups but no user flow creates/reassigns `linkedGroupId`. Implement only after defining atomic selection/link/unlink semantics in the existing native command boundary. | Native undo/redo tests plus Android create/link/unlink/lock behavior. |
| TC-M3 | P2 | Cross-domain history and large-project device budgets | History parity is partial; 500-clip/20-track device jank/scrub, memory/thermal, and 16 KB ELF evidence are absent. | Macrobenchmark/Perfetto output and built-binary inspection on a named device. |

## One-pass mobile procedure

Run `tools/run_color_grading_mobile.sh`; it selects the first authorized `adb` device automatically, honors `ANDROID_SERIAL`, preflights JDK 17+, the SDK, the pinned NDK `27.2.12479018`, and device metadata, and needs no package/test selection input. After install/launch, use the same short SDR H.264 clip for every run:

1. Record device model/API level, APK SHA-256, clip SHA-256, and the app version shown by Android.
2. With the Color tab open, change Temperature, Tint, Contrast, Saturation, and a primary wheel. Confirm the paused frame redraws and playback frames change; record any warning/error and inspect filtered `AndroidRuntime`/Media3 logs.
3. Open Nodes; add/select a node, change a wheel and LUT, adjust opacity, disable it, and reorder it. Confirm each operation is visible, local to that node, and survives switching tabs.
4. Undo and redo each gesture; save, force-stop/reopen the project, and verify values are restored. Use Reset All and confirm clip and every node return to neutral.
5. Export the supported clip-level curve/primary subset and inspect the output frame. Try an unsupported advanced/multi-node case and confirm export is rejected clearly before producing a misleading file.
6. Save screenshots plus the test log under `docs/evidence/`. Do not label visual/export results `PASS` without those artifacts.

## Low-input execution rule

- Continue autonomously in this order: compile/test → fix → rebuild/install → device checklist → export check → broader Timeline/JNI/performance gates.
- Keep all work on the active branch; do not add agents or switch branches.
- Make conservative, test-backed source changes; no guessed color behavior and no successful mobile claim from static checks.
- The only external prerequisite currently blocking mobile execution is an Android-capable environment/device. Once present, the checked-in runner performs the repeatable setup and records the evidence; product decisions should not be re-requested unless a genuine semantic ambiguity is discovered.
