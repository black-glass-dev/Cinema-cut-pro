# Timeline Core v2 Testing

## Current-session host evidence

The current session date is 2026-11-16. The latest gate filename (`GATE_20261116T122225Z.log`) also contains a sandbox timestamp; retain it as a raw evidence identifier, not as an independently verified chronology claim.

- `./tools/run_timeline_core_tests.sh` — **PASS**, warnings-as-errors C++23 direct build; **170,624 assertions**, including 10,000 deterministic snap-oracle cases, 10,000 randomized multi-track history operations, and 10,000 single-clip undo/redo cycles. INV-05 covers group-wide move/ripple/insert rejection plus atomic group-lock toggles, native undo/redo, and ungrouped single-clip behavior.
- `./tools/probe_cxx_conformance.sh` — **PASS** with the available `c++ -std=c++23` compiler; `std::expected` probe is 1; `-std=c++2c`, `mdspan`, `format`, `generator`, `flat_map`, `inplace_vector`, and SIMD probes are 0.
- `python3 tools/run_native_export_tests.py` — **PASS** for the native export engine, MotionCam preview renderer, decoder, gyro, and accelerometer tests. This does not compile Android JNI, Kotlin, or Media3.
- Full `./tools/gate.sh` rerun with isolated PyPI CMake 3.31.10 and Ninja 1.13.2 under ignored `build/host-tools/` — **host gates PASS**: native export tests, direct core tests, C++ probe, CMake Debug/Release/ASan+UBSan/coverage CTest (1/1 each), line/branch coverage, and host benchmark. Coverage was **84.00% lines** and **65.59% branches taken**. Exact output: [`GATE_20261116T122225Z.log`](evidence/GATE_20261116T122225Z.log). The script exits 2 only because Java/JDK is absent and it therefore cannot run Gradle tests or `:app:assembleDebug`.
- The preceding gate attempt [`GATE_20261116T120642Z.log`](evidence/GATE_20261116T120642Z.log) passed host checks at the earlier 170,602-assertion revision; the latest log above is authoritative for current native code.
- `./gradlew :app:assembleDebug` was attempted directly and exited 1 before Gradle because `JAVA_HOME` and `java` are absent. The earlier gate attempt [`GATE_20261112T123122Z.log`](evidence/GATE_20261112T123122Z.log) recorded the same missing Java and initial host-tool blocker.

The CMake/Ninja distributions were installed only for the host gate and are not project/runtime dependencies. Android/Kotlin/JNI ABI and device validation remain separate gates.

## Earlier host-gate artifacts

Pre-existing `docs/evidence/GATE_20261111*.log` and `GATE_20261112T123452Z.log` record earlier source revisions. Their assertions and coverage are not evidence for the current direct-runner revision. The latest full host run is the `GATE_20261116T122225Z.log` artifact above; its filename is preserved as a raw evidence identifier, not an independent chronology source. Historical logs remain useful only for their exact source revisions and are not Android/UI evidence.

## Kotlin / Android — unverified and blocked

- Kotlin frame-math, adapter, gesture, group-payload, virtualization, and export test sources have not been compiled or executed in this session.
- `java`, `javac`, `kotlinc`, Android SDK/NDK, emulator, and `adb` are unavailable. Therefore Kotlin compilation, Compose tests, JNI ABI/link checks, instrumentation, APK assembly, 16 KB ELF-alignment check, and device performance validation are `BLOCKED` or `UNVERIFIED`.
- `TimelineClip.isGroupLocked` is a default-false serialized field; `linkedGroupId` remains in persistent metadata. The adapter maps both to native clip fields and verifies returned group IDs. Long-press context menus now offer native-history-backed lock/unlock actions for an already-persisted linked group or a single ungrouped clip; the item is disabled when native is unavailable. The app currently has no UI action to create/reassign linked-group IDs. Kotlin adapter-payload, UI-target, and legacy-serialization tests are source-only and have not run.
- The NDK CMake target requests C++23 in source, but JNI registration/signatures have not been verified by an Android build.

## Invariant coverage boundary

The current host suite names tests for INV-01..12 and checks native edit/time/snap/history behavior. The latest 10,000-operation history regression exercises random moves across four independent tracks, validates undo/redo hashes in intermediate windows, then traverses the retained bounded history. The snap test checks 10,000 deterministic native inputs against an independent test oracle; it is not Kotlin/native mirror parity because no Kotlin snap mirror is used.

- INV-13 (render-graph cache key) — `NOT IMPLEMENTED`.
- INV-14 (preview/export frame parity) — `NOT IMPLEMENTED`.
- INV-15 (native-unavailable fail-closed contract) — Kotlin test source exists; `UNVERIFIED` until Gradle tests run.
- INV-16 (hardware-buffer lifetime) — `NOT IMPLEMENTED` / `UNVERIFIED`; no v2 hardware-buffer renderer exists.

The seeded serialization mutation loop is not libFuzzer. The core tests are a dependency-free harness, not GoogleTest or RapidCheck. Do not turn missing Android/device gates into passes.
