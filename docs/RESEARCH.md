# Timeline Core v2 Research and Probes

## Environment probes (executed)

| Probe | Result | Interpretation |
|---|---|---|
| `g++ --version` | `g++ (Debian 12.2.0-14+deb12u1) 12.2.0` | Available host compiler; not Android NDK Clang. |
| compile `<expected>` using `g++ -std=c++23` | `PASS` | Host supports the required C++23 baseline and `std::expected` in the probe. |
| compile same probe using `g++ -std=c++2c` | `g++: error: unrecognized command-line option '-std=c++2c'` | C++26 mode is not available in this host toolchain; do not claim it compiled. |
| `./gradlew :app:assembleDebug` | `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.` | Android/Gradle compile is blocked before Gradle starts. |
| `command -v cmake ninja adb java sdkmanager` and SDK/NDK environment/path checks | none found initially | No Android toolchain or device tooling is installed. A host CMake/Ninja pair was later installed to `/tmp` only for native-core host checks. |
| `python3 tools/run_native_export_tests.py` | `Native export engine tests passed.` plus MotionCam decoder/renderer test passes | Baseline native host suite passed; does not compile Android JNI or Kotlin. |

The temporary host build tools were installed from the Python package index after an initial unverified `cmake==3.31.8` request failed. The index listed `cmake==3.31.10`; that version and Ninja 1.13.2 were installed outside the repository. Root cause → rule: an assumed patch version is not evidence; check the package index before selecting an ephemeral tool version. The CMake launcher requires its target directory on `PYTHONPATH`.

## Repository-pinned stack (source-derived, not all build-verified)

See `docs/STACK.md` for the exact repository declarations and what remains unverified. Current source declares Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, Compose BOM 2024.09.00, Media3 1.11.1, Android compile SDK 36 minor level 1, CMake 3.22.1, C++17 for Android native targets, and NDK 27.2.12479018 in the FFmpeg build script. These are repository pins, not proof that this sandbox can resolve or compile them.

## Upstream source check

- FFmpeg remote: `https://github.com/FFmpeg/FFmpeg.git`
- Repository tag in `tools/build_ffmpeg_android.sh`: `n8.1.3`
- Pinned source commit: `1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7`
- Verification commands:
  - `gh api repos/FFmpeg/FFmpeg/commits/1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7 --jq '{sha: .sha, commit_date: .commit.committer.date, message: .commit.message}'`
  - `gh api repos/FFmpeg/FFmpeg/git/ref/tags/n8.1.3 --jq '{ref: .ref, sha: .object.sha, type: .object.type}'`
  - `gh api repos/FFmpeg/FFmpeg/git/tags/23151b11c75aa44d9ab8db796a53c76acf00f6c0 --jq '{tag: .tag, target_sha: .object.sha, target_type: .object.type}'`
- Observed: tag `n8.1.3` resolves to the pinned commit above. This verifies source identity only. No Android FFmpeg build or codec/export behavior was validated.

## Unverified/toolchain constraints

- NDK 27.2.12479018 is declared by the current FFmpeg build script; no NDK is installed. The prompt's NDK r30 / Clang 22 assumptions have not been verified in this environment and are not substituted silently.
- No Java/JDK, Android SDK, emulator, `adb`, Perfetto device trace, or physical Android device is available. Android builds, instrumentation, ABI ELF checks, and device performance targets remain `UNVERIFIED` unless the environment changes.
- Maven artifacts were not resolved by Gradle; catalog versions are source declarations only.
- C++26-specific features beyond the host's tested C++23 `<expected>` probe must be feature-probed before use. Keep portable C++23 as the build baseline.
