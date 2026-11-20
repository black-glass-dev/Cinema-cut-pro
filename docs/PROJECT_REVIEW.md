# Cinema Cut Pro — source review

**Review date:** 2026-11-16

**Scope:** static inspection of the Android project extracted from the repository's uploaded ZIP. This is a code review, not a device QA report.

## Executive summary

This is an **Android-first video-editor prototype**, not a web app. It has a substantial Compose/Kotlin editor, separate landscape timeline workspace, media import/preview source, color and transform tools, and export/native modules. Timeline edit/time/snap/history source has been consolidated under the C++23 Timeline Core v2 with Kotlin/JNI transport; native host tests pass, while Android integration remains unverified. Other image-processing and export modules have separate limitations and are not evidence of a complete multi-layer renderer. The source alone does not establish reliable playback or export on a real phone.

A crucial product distinction: the current Gemini integration is for **textual color-grading advice**. The repository does not implement prompt-to-video generation. If “advanced video creation” means generating footage with AI, that requires a separate model/provider and service design.

## Architecture map

### App and state flow

- `app/src/main/java/com/example/MainActivity.kt` hosts the Compose app; `ChromaProApp.kt` wires the main editor, view models, preview controller, and export UI.
- `timeline/core/` contains `TimelineProject`, tracks/clips/settings, edit commands, validation, undo/redo, autosave, project versions, and file storage.
- `timeline/ui/TimelineViewModel.kt` exposes project/editor state as `StateFlow` and applies timeline commands. The advanced workspace is a separate `AdvancedTimelineActivity`; `ActiveProjectManager` transfers a project snapshot and playhead state between activities.
- `timeline/engine/preview/` owns playback selection/synchronization, frame lookup, still-frame preview, thumbnails, and transform/color preview adapters.
- `timeline/media/` contains URI import/analysis, metadata, codec checks, relinking, and proxy-work scaffolding.
- `timeline/export/` contains validation, render planning, export backends, MediaCodec decode/encode/mux helpers, progress, cancellation, and a foreground service.
- `model/adjustments/` and `model/colorgrade/` contain parameter stacks, keyframes, LUT and color math, CPU reference paths, and shader source.
- `ui/` contains the Compose editor, timeline workspaces, color/transform controls, scopes, and export settings.
- `app/src/main/cpp/` contains C++23 Timeline Core v2 (explicitly registered JNI) plus separate transform, color/export, FFmpeg, and MotionCam C++ modules. The old selectable no-op C++ player path was removed. Other unrelated JNI/image-processing APIs still require their own implementation and device verification.

### Technology and packaging

- Android Gradle project: root `settings.gradle.kts`, one application module (`:app`).
- Kotlin 2.2.10, Jetpack Compose, Android Gradle Plugin 9.1.1, Gradle wrapper 9.3.1; the app compiles Java source/target 11.
- `minSdk 24`, `targetSdk 36`, compile SDK API 36 minor level 1.
- CMake 3.22.1; the Timeline Core v2 Android target explicitly requests C++23, while separate native targets retain their own configured language levels; currently filtered to `arm64-v8a`. Android/NDK compilation is unverified here.
- The Kotlin namespace is `com.example`; the application ID is the generated-looking `com.aistudio.chromapro.xzvqpw`. Decide on a stable package ID before a public release. JNI function names also include `com_example`, so package migration must update native symbols and tests together.
- The visible Android app label is `Cinema Cut Pro`; several internal UI/AI strings still say “ChromaPro”.

## Capability assessment from the checked-in source

| Area | What is present | What is not established / needs work |
|---|---|---|
| Project and timeline | Frame-based Kotlin persistence/UI DTOs; C++23 integer-Flicks edit/time/snap core; native/JNI/Kotlin adapter source; native command routing in both timeline canvases/ViewModel; per-clip delta undo/redo; visible-clip culling. | Group IDs map from persistent `linkedGroupId` metadata; `isGroupLocked` is persisted, native lock changes are undoable, and both long-press menus expose clip/group lock actions. Host semantics are tested; Kotlin/JNI/Compose paths remain uncompiled, while media-bound mapping and device performance are unverified. See `FEATURE_MATRIX.md` and `MIGRATION.md`. |
| Media import and preview | Android media/URI abstractions, analyzers, Media3 ExoPlayer primary routing, MIME-aware source passing, structured playback diagnostics, legacy LibVLC 3.5.1 Java API selection, still-frame preview, and proxy-related classes. | Media3 depends on device extractors/codecs; there is no universal format guarantee. SAF permissions, rotation, VFR, previously failing media, and all playback controls still need target-device validation with sample files and Logcat. |
| Transform and motion | Transform data/keyframes, interpolation and motion-path code, native math, and editor controls. | Some JNI transform calls are hard-coded placeholders (`nativeComputeBoundingBox`, `nativeHitTestHandles`, and related functions in `native_transform_jni.cpp`). A UI control or declared engine does not guarantee the result is applied to exported pixels. |
| Color and LUT | Adjustment/grade stacks, CPU frame processors, LUT parsing/interpolation code, color UI, and GLSL source. The ordinary video preview route is `TimelinePreviewController` → `GradeEvaluationEngine` → Media3 `VideoEffectGraph`/`VideoAdjustmentEffect` → `AdjustmentShaderProgram`; source changes now feed clip grades (including unmirrored legacy values/keyframes) and ordered enabled color nodes into that shared preview-parameter block, while color gestures record full before/after project snapshots. | This is source-level work only: the new Kotlin/JUnit tests and Media3 shader path were not built or run here, and device behavior remains `UNVERIFIED`. `OpenGLAdjustmentRenderer` still returns its input texture unchanged and `CpuColorPipeline` is a no-op, but neither is the ordinary Media3 editor-preview route. `ColorSpaceTransformEngine` retains simplified transforms. The export validator accepts the supported clip-level primary/curve subset in source but rejects unsupported advanced grades and nontrivial multi-node stacks; actual export output and CPU/GPU/preview parity are `UNVERIFIED`/not demonstrated. HDR, log, and 10-bit labels remain unverified. Repeatable phone checks are ordered in [`REMAINING_WORK.md`](REMAINING_WORK.md) and detailed in [`COLOR_GRADING_BACKLOG.md`](COLOR_GRADING_BACKLOG.md); see also [`device-playback-validation.md`](device-playback-validation.md). |
| Export | Queue/progress/service lifecycle plus source for a bounded Media3 Transformer adapter for contiguous constant-frame-rate clips on one visible video track, source-frame trims, output resize, H.264/MP4, and compatible embedded AAC. Its validator rejects unsupported edits/formats; a separate incomplete `VideoExportPipeline.kt` remains. | It is **not production-ready or device-verified**. Android build/JVM tests and a valid MP4 export were not run in this environment. The adapter is cut-only: no crossfades, multiple-layer compositing, full transform/grade/LUT/watermark graph, separate audio mixing, alpha, or HDR. Other backend scaffolding is not a production fallback. See [Media3 preview/export scope](MEDIA3_PREVIEW_AND_EXPORT.md). |
| Audio | Audio-track models, stream helpers, a PCM mixer, and mux/encode scaffolding. | There is no validated end-to-end multitrack audio mix/export report. Verify timing, channel mapping, clipping/limiting, mute/solo, and sync against test media. |
| AI | `MainViewModel` calls `GeminiRepository` for a color-advice chat; Retrofit targets the Google Generative Language API. | This is text advice, not video generation. `BuildConfig.GEMINI_API_KEY` is embedded in the client binary when configured; that is not safe for a public app. Move production calls behind a backend. |
| Tests | Native C++ Timeline Core v2 and export/decoder host test sources; Kotlin/Robolectric/Compose unit test sources. | Current direct native core and export/decoder host tests pass. Kotlin/Android tests and builds were not run because Java/Android tools are absent. There is no end-to-end import, render/export, or device-compatibility result for this checkout. The imported archive's `build.log` is not treated as evidence. |

### Source evidence for the export caveat

- `timeline/export/backend/Media3TransformerBackend.kt` builds a Media3 `Transformer`, configures H.264/AAC encoders, applies source clipping and output presentation sizing, reports progress, and cleans up on success, failure, or cancellation. Its validator accepts only a bounded sequential/cut-only composition and deliberately rejects unsupported edits; crossfades are not implemented.
- `timeline/export/pipeline/VideoExportPipeline.kt` remains a separate, incomplete path: it selects only the top enabled video layer and does not demonstrate the full compositing/color graph.
- `timeline/export/backend/NativeSoftwareExportBackend.kt` still has a simulated completion path and is not selected as a production fallback.
- `app/src/main/cpp/` now contains real host-testable frame/compositing, color/LUT, transform, PCM, and checked JNI buffer routines. The custom C++ frame pipeline is not yet connected to the Media3 MP4 path; host tests do not validate JNI or Android integration.

These are implementation and validation gaps, not just documentation gaps. Do not market export as a fully working multi-track renderer until the Android app has built and representative files have been inspected and tested on devices.

## Repository cleanup performed

- Extracted the Android project into the repository root while excluding Gradle cache and CMake/NDK build outputs (`.gradle/`, `app/.cxx/`, and generated build directories).
- Moved the duplicate `MediaLibrarySheet.kt` copy out of the orphaned `app/applet` tree after confirming it was byte-for-byte identical to the active source file.
- Consolidated imported audit notes under `docs/archive/imported/`; the original uploaded ZIP remains at the repository root as `codespace-project-reviver.zip`.
- Removed archive-only build noise (`build.log`, the unused Node `package.json`, an unused network-check Gradle script, and an empty AI Studio placeholder assets tree).
- Added Android/Gradle/secrets ignores, made `gradlew` executable, restored the Gradle 9.3.1 wrapper JAR from its upstream Git blob after detecting that the uploaded copy was corrupt, and removed the need for a checked-in/local `debug.keystore` for debug builds.

## Validation limits

The current sandbox has no `java`, `javac`, Android SDK, Android NDK, or `adb`, so Gradle and Android tests cannot run here. The latest host gate (`GATE_20261120T063018Z.log`) passes all native stages with 170,624 assertions and exits 2 solely because Java/JDK is absent; the mobile runner preflight separately confirms the missing Android SDK/NDK/device prerequisites. GitHub run metadata queries reported `completed/success` for 39122394286 (head `4aedd12f29650ace3d44a5f106ab33c52425b183`) and 40202985334 (head `e068a5f21570f14a0256d92b2960e8ef8b9d7e24`); their job logs were not inspected, and those head SHAs do not identify the source revision tested by this local checkpoint. Exclude those records from current verification claims. This checkout has no Android build/JVM result or target-device playback/export evidence. No sample video or Logcat was supplied, and no Android device is attached; device behavior remains unverified. Gate filenames carry sandbox-generated timestamps; treat them as artifact identifiers rather than independently verified chronology.