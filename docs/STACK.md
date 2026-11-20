# Verified and Declared Stack

This file distinguishes repository declarations, host probes, upstream source identity, and claims that remain unverified. No new third-party runtime dependency is introduced by the initial v2 core scaffold.

## Repository declarations

| Component | Repository declaration | Evidence source | Verification state |
|---|---|---|---|
| Gradle | 9.3.1 wrapper | `gradle/wrapper/gradle-wrapper.properties` | Wrapper cannot start here: no Java. |
| Android Gradle Plugin | 9.1.1 | `gradle/libs.versions.toml` | Not resolved by Gradle in this environment. |
| Kotlin | 2.2.10 | `gradle/libs.versions.toml` | Not compiled in this environment. |
| Compose BOM | 2024.09.00 | `gradle/libs.versions.toml` | Repository declaration only. |
| Media3 | 1.11.1 | `gradle/libs.versions.toml` | Repository declaration only; no playback/export claim follows from this pin. |
| Android API | compile SDK 36, minor API 1; min SDK 24 | `app/build.gradle.kts` | Android SDK absent. |
| Android CMake | 3.22.1 | `app/build.gradle.kts` | Android external build not run. |
| Android C++ dialect | App-wide `-std=c++17` flag; Timeline Core v2 and `native_timeline_core` request C++23 through target-specific CMake properties/features | `app/build.gradle.kts`, `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/timeline_core_v2/CMakeLists.txt` | Android NDK/libc++ compile and effective final compiler flags are unverified. |
| Android ABI filter | arm64-v8a | `app/build.gradle.kts` | Current project setting; no NDK build performed. |
| NDK for FFmpeg script | 27.2.12479018 | `tools/build_ffmpeg_android.sh` | Not installed; different from the requested NDK r30 proposal. |
| FFmpeg | tag `n8.1.3`, commit `1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7` | `tools/build_ffmpeg_android.sh`; GitHub API tag/commit verification in `docs/RESEARCH.md` | Source identity verified. Android build/output capabilities unverified. |

## Host toolchain used for native-core checks

- Host C++ compiler: GCC 12.2.0.
- `g++ -std=c++23` probe including `<expected>`: passed in the current session; `g++ -std=c++2c` was rejected as an unknown flag.
- CMake 3.31.10 and Ninja 1.13.2 were installed from the verified PyPI distributions into ignored `build/host-tools/` for the latest host gate; they are not project dependencies and are not on the default `PATH`.
- The standalone `timeline_core_v2` host target requires C++23. Direct warnings-as-errors tests and the CMake Debug, Release, ASan/UBSan, coverage, and benchmark gates passed for the current working-tree source. Latest gate coverage measured 84.00% executable lines and 65.59% branches taken. See [`TESTING.md`](TESTING.md) and [`PERF.md`](PERF.md).
- Android's app-wide flags contain `-std=c++17`, while the v2/JNI targets request C++23; NDK/libc++ support and the effective Android compiler command remain unverified.
- The current environment has no full JDK. A prior PyPI `jdk4py` 17.0.9.2 runtime had no `javac`; the requested assemble command is blocked as recorded in `docs/evidence/ANDROID_ASSEMBLE_BLOCKED.log`. A Temurin asset download was not usable from this environment.
- Historical `GATE_20261111*.log` files cover earlier source revisions; gate filenames retain sandbox-generated timestamps. Preserve filenames as raw evidence identifiers, not as independently verified chronology. Coverage applies only to v2 C++ source; Android/Kotlin coverage is unavailable.

## Target policy

- Use C++23 as the portable baseline. Enable any C++26 facility only after a compiler/library feature probe and generated feature header demonstrate it on the actual NDK toolchain.
- Do not change the app's NDK/CMake/Gradle/Kotlin pins based solely on the requested future stack; first acquire and verify the actual toolchain/artifacts.
- New dependency additions require a verified upstream tag/SHA or artifact coordinate and a license entry in `docs/THIRD_PARTY.md`.
- FFmpeg license configuration, codec inventory, bit-depth, HDR, and export support remain unclaimed until the actual built libraries and container output are verified.
