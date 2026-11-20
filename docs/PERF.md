# Timeline Core v2 Performance

## Host-only native microbenchmark

Command: `cmake --preset host-benchmark && cmake --build --preset host-benchmark --target timeline_core_v2_benchmark && build/cmake/host-benchmark/app/src/main/cpp/timeline_core_v2/timeline_core_v2_benchmark`.

Latest recorded run: see [`GATE_20261116T122225Z.log`](evidence/GATE_20261116T122225Z.log). It used the Linux x86_64 sandbox host, GCC 12.2.0, CMake 3.31.10, Ninja 1.13.2, and a Release configuration. Each row reports 30 iterations over synthetic projects with 20 tracks; p50/p95 are per-operation wall time in microseconds. The filename's sandbox-generated timestamp is treated as an opaque evidence identifier, not an independently verified chronology claim.

| Operation | 500 total clips / 20 tracks p50 / p95 | 10,000 total clips / 20 tracks p50 / p95 |
|---|---:|---:|
| `validateProject` | 10.472 / 14.067 µs | 286.923 / 319.838 µs |
| `visibleClipIds` | 12.500 / 12.644 µs | 312.043 / 353.429 µs |
| `snap` | 15.087 / 16.118 µs | 386.320 / 429.738 µs |
| `applyEdit` move (copy + validate) | 63.793 / 86.363 µs | 2,204.810 / 2,438.240 µs |

These are reproducible host-native C++ microbenchmark observations, not Compose composition/layout, Android frame-time, jank, scrub, thermal, memory, device, or render-throughput measurements. The synthetic project does not represent media decode/render load. They are not evidence for the app's §11 device budgets.

## Android/device targets

No Android SDK/NDK, emulator, `adb`, Perfetto runner, Android Macrobenchmark module, or physical device is available in this execution environment. Therefore no 60 fps, jank, scrub latency, memory, thermal, preview/export throughput, or 16 KB ELF-alignment target is claimed.

| Target | Measurement | Status |
|---|---|---|
| 500+ clips / 20+ tracks, ≥60 fps p95, <1% jank | Compose Macrobenchmark + Perfetto on named mid-range device | UNVERIFIED; Compose source culling exists, but the Compose tests/build and device run were unavailable |
| Scrub ≤50 ms proxy / ≤120 ms full-res | Instrumented device tracing | UNVERIFIED |
| 1080p/4K preview and export throughput | Representative Android hardware/media | UNVERIFIED |
| Compose visible-child count at 500+ clips | `TimelineVirtualizationTest` source contains a 500-clip/20-track culling case; test execution is blocked with the Android toolchain | UNVERIFIED |
| Host native edit/snap/query cost | `timeline_core_v2_benchmark` above | MEASURED on host only |

Emulator/host measurements, if later added, must be labeled and must not be presented as representative physical-device results.
