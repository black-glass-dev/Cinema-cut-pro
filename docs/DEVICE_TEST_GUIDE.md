# Device testing guide — Cinema Cut Pro test APK

**APK:** `Cinema-Cut-Pro-debug-92-1.apk` (89.8 MB, debug-signed, arm64-v8a)  
**Download (browser):** https://github.com/black-glass-dev/Cinema-cut-pro/releases/tag/android-test-92-1  
**Built from commit:** `b9e4c6c9fd3278a893a5ca53218f0936a18a73ac` on branch `arena/a2d6ebb0-cinema-cut-pro`  
**CI evidence:** run 44700425318 (run #92) — `:app:compileDebugKotlin` ✓, `:app:externalNativeBuildDebug` ✓ (JNI/NDK arm64), full `:app:testDebugUnitTest` suite ✓ (includes the new Color mapper/evaluator/export-composition/LUT-lifecycle tests), native C++ export tests ✓, `:app:assembleDebug` ✓, third-party license assets verified ✓. SHA-256 checksum file is attached next to the APK in the release.  
**Install:** allow "install unknown apps" for your browser/file manager, open the APK, install (it is debug-signed, not a Play Store release).

## What this build contains (compiled and JVM-tested, not yet device-verified)

- Color grading: clip grades, grade keyframes, and color nodes flow through one shared composer (`GradeEvaluationEngine.evaluateClipGrade`) into the same GPU shader for preview and export.
- Export accepts the full shader-rendered grade set (primary wheels, curves, LUTs, skin-tone protection, HSL qualifier, selective color, grain, vignette, channel mixer, log/tone mapping) and clearly rejects animated grades, node blend modes, and Color Match before rendering.
- LUT library survives app restart (persisted files re-register) and delete now works (entry + cache + file removed).
- Timeline Core v2: native C++ edit/snap/history authority with fail-closed behavior when unavailable.

## Phone test checklist (your pass — record results/screenshots)

Use the same short SDR H.264 clip throughout. Note phone model + Android version before starting.

1. **Launch & import:** install, open, import the clip, confirm a visible preview frame and no crash (Settings → About phone for model; capture any error dialog).
2. **Clip grade preview:** Color tab → Wheels: drag Temperature, Tint, Contrast, Saturation and one primary wheel. Verify the paused frame redraws AND playback frames visibly change. Toggle Before/After (toolbar) to compare.
3. **Node grade:** open Nodes → add a node → change a wheel, pick a LUT, set intensity and opacity, disable and reorder the node. Each change must stay node-local and survive tab switches.
4. **History/persistence:** Undo/Redo every gesture; force-stop and reopen the app; verify clip and node values restored exactly (no doubled grade). Use Reset All → everything returns to neutral.
5. **LUT lifecycle:** import a `.cube` LUT, force-stop + reopen (LUT must still be listed — restart persistence), apply, then delete it (picker refreshes, selection clears).
6. **Export parity:** export the clip with a grade (primary + curves or LUT) — SDR H.264/MP4. Watch the output: first frames must match preview grading. Then try an animated grade or Color Match — export must be rejected with a clear message BEFORE writing a file.
7. **Timeline smoke:** move/trim/snap a clip, undo/redo, long-press lock, cancel mid-drag. Rejected edits must not change the timeline.

**Report back:** screenshots/screen-recording per step, any crash dialogs, and whether step 6's exported MP4 matches the preview. Items not exercised stay `UNVERIFIED`; this build's on-device behavior has no pass claim until you run the checklist.

## Reproduce the build

```bash
tools/run_color_grading_mobile.sh   # with JDK 17+, Android SDK, NDK 27.2.12479018, and a device
# or on GitHub: Actions → "Build and release Android test APK" → run with [publish-test-release]
```
