# Cinema Cut Pro

Cinema Cut Pro is a native Android video-editor prototype built with Kotlin, Jetpack Compose, and a small C++/JNI layer. The repository contains substantial editor, timeline, color, media, and export code, but these subsystems are at different completion levels.

> **Current status:** prototype / active development. The source routes `AUTO` preview to Media3 ExoPlayer for standard Android-compatible media and retains LibVLC as an explicit fallback. A bounded Media3 Transformer adapter contains validation and execution code for sequential, cut-only, single-visible-video-track H.264/MP4 export; it is not a full NLE renderer. The current workspace has no JDK, Android SDK, or NDK, so no Android build, JVM test, valid MP4 export, or device playback result is claimed here. Standard-format support depends on platform extractors/decoders; the experimental arm64 MotionCam Pro `.mcraw` import, Bayer preview, and bounded 8-bit SDR video-export source also require Android and device validation. Multi-layer compositing, full color-grade export, multitrack audio, crossfades, and HDR are not implemented by the standard export path. See [MotionCam RAW scope and limitations](docs/MOTIONCAM_RAW.md), [preview/export scope and validation](docs/MEDIA3_PREVIEW_AND_EXPORT.md), [the source review](docs/PROJECT_REVIEW.md), and [development roadmap](docs/ROADMAP.md).

## What is in the codebase

- Portrait editor and a separate landscape advanced-timeline workspace.
- Project, track, clip, edit-command, undo/redo, autosave, and versioning models.
- Media import/analysis, Media3 ExoPlayer primary preview with SAF/MIME-aware source handling, explicit legacy LibVLC fallback, proxy-generation scaffolding, and native MotionCam Pro `.mcraw` frame preview with a bounded 8-bit SDR H.264/MP4 video-export bridge.
- Transform/keyframe and color-adjustment/color-grade models and UI.
- A bounded Media3 Transformer H.264/MP4 cut-only export adapter for compatible contiguous CFR clips on one visible video track; it trims, validates unsupported edits, reports progress, and supports cancellation. Crossfades and broader NLE compositions are not implemented. It remains unverified on target devices.
- A Gemini text endpoint intended for color-grading advice. This is **not** a text-to-video or video-generation model.

## Project layout

```text
app/src/main/java/com/example/
  timeline/core/       project data, editing commands, history, persistence
  timeline/engine/     preview, timeline math, native adapters, performance
  timeline/media/      import, media metadata, proxies, codec checks
  timeline/export/     export jobs, backends, resolver, codecs, service
  model/               adjustment and color-grade models/processors
  ui/                  Compose editor, timeline, color, transform, export UI
  ai/                  Gemini API/repository
app/src/main/cpp/      C++23/JNI Timeline Core v2 plus C++17 transform, color, and export modules
app/src/test/          local JVM/Robolectric tests
app/src/androidTest/   Android instrumentation tests
docs/                  current review, roadmap, and imported historical notes
codespace-project-reviver.zip  original uploaded ZIP snapshot (not used by Gradle)
```

The Android application module is `:app`. There is no Node/web application; the unused AI-Studio `package.json` and generated build log from the uploaded archive were removed.

## Build locally

### Requirements

- Android Studio with a compatible Android Gradle Plugin 9.1.1 toolchain.
- JDK 17 or the JDK bundled with Android Studio.
- Android SDK Platform 36 (the project requests API 36, minor level 1), NDK, and CMake 3.22.1.
- An arm64-v8a emulator/device for the native build. This project currently filters native builds to `arm64-v8a` only.

Open the **repository root** in Android Studio, or run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The dependency-free host-side C++ suites can be run separately:

```bash
./tools/run_timeline_core_tests.sh
python3 tools/run_native_export_tests.py
```

They do not compile the Android JNI bridge or verify Android/Media3 integration. On Windows, use `gradlew.bat` instead. The debug build uses Android Gradle Plugin's generated debug signing key; no private debug keystore needs to be added to the repository. A release signing config is enabled only when all four environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are set and the keystore file exists. Never commit a signing key or its passwords.

### Optional Gemini color-advice API

The editor does not need an API key. To try the Gemini color-advice path, copy `.env.example` to an ignored local `.env` file and replace the placeholder:

```properties
GEMINI_API_KEY=your_key_here
```

**Security:** the current Android client reads this value through `BuildConfig`, so a key supplied at build time can be recovered from a distributed APK. A local `.env` file prevents accidental Git commits; it does **not** make a mobile API key secret. Before public release, route Gemini requests through a controlled backend and apply provider-side restrictions and quotas.

## Documentation

- [Source review and architecture map](docs/PROJECT_REVIEW.md)
- [Media3 preview/export scope and validation notes](docs/MEDIA3_PREVIEW_AND_EXPORT.md)
- [MotionCam RAW scope and limitations](docs/MOTIONCAM_RAW.md)
- [Prioritized development roadmap](docs/ROADMAP.md)
- [Documentation index](docs/README.md)
- [Original source archive snapshot](codespace-project-reviver.zip)

The older audit notes have been preserved under `docs/archive/imported/`. They were copied from the uploaded project and may contain outdated or unverified claims; the current source review is the authoritative status summary.

## Timeline Core v2 migration status

Timeline Core v2 source includes the C++23 integer-Flicks engine, explicit JNI registration, a Kotlin transport adapter, and ViewModel/Compose routes for native edit preview, commit, snap, undo/redo, and group/clip lock toggles. The bounded native journal stores reversible per-clip deltas, including lock-state edits, and rejects external-history conflicts. The direct host suite passes 170,624 assertions; the current CMake Debug/Release/ASan+UBSan/coverage tests and host benchmark pass, with 84.00% line and 65.59% branch coverage for the C++ core. **Android integration remains source-present and unverified**: this workspace lacks a JDK, Android SDK, and NDK, and Kotlin/Compose tests and `:app:assembleDebug` could not start because Java is absent. Viewport culling is present in both canvases, but device performance remains unverified. The placeholder Kotlin decode/encode export pipeline and a user-selectable `NATIVE_CPP` playback route backed by no-op stubs were removed; these removals do not expand the verified export/playback claims. See the [implementation plan](docs/PLAN.md), [feature matrix](docs/FEATURE_MATRIX.md), [migration map](docs/MIGRATION.md), and [host/device performance evidence](docs/PERF.md).
