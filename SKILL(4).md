---
name: ndk-cpp26-toolchain
description: Set up and debug an Android NDK C++26 toolchain (NDK r30 / Clang 22 / libc++), with feature-test gating, CMake presets, sanitizers, LTO, 16 KB page-size compliance, and build performance. Use when configuring CMakeLists for an Android native module, when a C++23/C++26 feature fails to compile, when native libs crash on 16 KB page devices, or when native build times or APK size need fixing.
license: MIT
---

# NDK C++26 toolchain

## 1. Ground truth (verify, never assume)

- NDK r30 ships LLVM/Clang 22 + libc++, supports `-std=c++2c` in **experimental** mode.
- Baseline for shipping code must be **C++23** (`-std=c++23`); C++26 features are opt-in behind
  feature-test macros and a generated config header.
- Known gaps at Clang/libc++ 22 that people wrongly assume exist: static reflection (P2996),
  contracts (P2900), `std::execution` (P2300) in the STL. Assume absent; gate them.
- C++26 has no published ISO edition yet (2026 DIS ballot failed). Treat `-std=c++2c` as "draft".
- Confirm the real values with a probe instead of trusting this file:

```
// cmake/ProbeCxxConformance.cpp
#include <version>
#include <cstdio>
int main() {
  std::printf("__cplusplus=%ld\n", __cplusplus);
#ifdef __cpp_lib_expected
  std::printf("expected=%ld\n", (long)__cpp_lib_expected);
#endif
#ifdef __cpp_lib_mdspan
  std::printf("mdspan=%ld\n", (long)__cpp_lib_mdspan);
#endif
#ifdef __cpp_lib_execution
  std::printf("execution=%ld\n", (long)__cpp_lib_execution);
#endif
#ifdef __cpp_lib_simd
  std::printf("simd=%ld\n", (long)__cpp_lib_simd);
#endif
}
```

Compile it per ABI in try_compile and emit `cxx_features.hpp` with `TL_HAVE_EXPECTED` etc.

## 2. CMake skeleton

```cmake
cmake_minimum_required(VERSION 3.31)
project(timeline_core LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)   # expose only JNI entry points
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
add_compile_options(-Wall -Wextra -Wpedantic -Werror
                    -fno-exceptions?  # NO: keep exceptions on, they stay inside the core
                    )
target_compile_options(core PRIVATE -O3 -flto=thin -fvisibility=hidden
                      -fstack-protector-strong -D_FORTIFY_SOURCE=3)
target_link_options(core PRIVATE -flto=thin -Wl,--gc-sections -Wl,-z,max-page-size=16384)
```

- `CMakePresets.json` presets: `dev-debug` (Ninja, `-O1 -g`, ASan+UBSan), `dev-release` (`-O3`),
  `android-arm64` (ABI arm64-v8a, API 26+), `android-x86_64` (emulator), `host-tests` (GTest on Linux).
- `android { ndkVersion = "30.x.y" ; externalNativeBuild { cmake { arguments += "-DCMAKE_CXX_FLAGS=-std=c++2c" } } }`
- STL: `c++_shared` (smaller, must ship `libc++_shared.so`); document choice in an ADR.

## 3. 16 KB page size

- NDK r28+ produces 16 KB-aligned ELF segments by default with AGP 8.5.1+ and uncompressed libs.
- Verify every shipped `.so`:
  `llvm-objdump -p lib.so | grep LOAD` → each LOAD must show `align 2**14` (or higher).
- Also test on a 16 KB emulator image (`adb shell getconf PAGE_SIZE` → 16384).

## 4. Sanitizers

- Host: ASan+UBSan for unit tests; TSan for threading tests; libFuzzer for parsers.
- Device: HWASan build variant for `arm64-v8a` (needs a HWASan-capable device/emulator).
- Prefer `-fsanitize=address,undefined -fno-omit-frame-pointer` in the debug preset only; never ship.

## 5. Performance

- ccache/sccache + Ninja + precompiled headers for heavy third-party headers (FFmpeg, Vulkan-Hpp).
- `-fno-rtti` where reflection-free design allows; keep exceptions.
- Measure: `ninja -d stats`, `-ftime-trace`, and `llvm-size`/`apkanalyzer` for binary size.
- Keep the JNI surface tiny and header-only where possible; heavy templates stay in `.cpp`.

## 6. Debugging

- `ndk-stack -sym <symbols>` for tombstone symbolication; keep unstripped `.so` in CI artifacts.
- LLDB: `lldb-server` via Android Studio; `adb shell am start` + `run-as` for permission issues.
- Log native logs through a single `TL_LOG` macro bridged to `__android_log_print`, with levels and
  Perfetto tracing (`TRACE_EVENT` via Perfetto SDK) — no stray `printf`.

## 7. Failure modes seen in practice

| Symptom | Cause | Fix |
|---|---|---|
| `invalid value 'gnu++23'` | old Clang (NDK r25) | bump NDK to r30 |
| crash only on new devices | 4 KB alignment | rebuild with r28+, check LOAD align |
| linker "undefined symbol" for libc++ | `c++_shared` not packaged | add `libc++_shared.so` or switch to `c++_static` |
| silent ABI mismatch | mixing NDK versions in dependencies | single toolchain file, `-DANDROID_NDK=` pinned |
| huge APK | debug symbols / all ABIs | strip release, ship arm64 only, `abiFilters` |
