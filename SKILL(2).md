---
name: deterministic-test-harness
description: Build deterministic test infrastructure for a C++/Android media engine — GoogleTest unit suites, RapidCheck property tests, golden-frame/SSIM conformance, libFuzzer targets, sanitizer matrices, native benchmarks, Macrobenchmark, and a single gate script that produces evidence logs. Use when a project needs provable correctness claims, regression protection for edit semantics, reproducible frame output, or a CI gate that cannot be gamed by disabling tests.
license: MIT
---

# Deterministic test harness

Goal: every claim in the final report is backed by a log file another engineer can reproduce with one
command. No claim without a log.

## 1. Layout

```
tests/unit/            # GoogleTest, one binary per module
tests/property/        # RapidCheck generators + INV-* suites
tests/golden/          # op-sequence JSON + expected frame hashes
tests/fuzz/            # libFuzzer targets (host only)
tests/androidTest/     # instrumentation, gesture + export
bench/                 # google/benchmark binaries
bench/macrobenchmark/  # AndroidX Macrobenchmark module
tools/gate.sh          # the single entry point
docs/evidence/         # committed logs, timestamped
```

## 2. Determinism rules

- No wall-clock, RNG seed, thread count, or device in the output path of a golden test.
- Fixed seeds printed at start; failing seeds replayable with `--seed`.
- Golden frames compared by hash **or** SSIM with a documented tolerance; never "looks right".
- Reference renderer: a host (CPU) implementation of the same graph is the oracle; the device must match
  it, not the other way round.
- Flaky test = broken test. Quarantine, fix, or delete — never retry-loop it into green.

## 3. Property tests (RapidCheck)

Generators: random clip sets (0–200 clips, 1–20 tracks), random deltas including 0, ±1 frame, and
out-of-bounds values, random lock states, random media durations including 1-frame media.
Properties: the INV-01..INV-16 list from `timeline-core-invariants`. Minimum 10,000 cases in the gate;
nightly run at 1,000,000 with `--max-success`.

## 4. Fuzzing

Targets: `fuzz_otio_import`, `fuzz_project_deserialize`, `fuzz_command_stream` (apply arbitrary command
sequences and assert invariants), `fuzz_media_probe` (container parsing is the classic crash surface).
Corpus committed if small; crash reproducers always committed as regression tests.

## 5. Sanitizers

- `asan-ubsan` preset: all unit + property tests.
- `tsan` preset: threading tests (playback clock, cache eviction, JNI callback paths).
- `hwasan` device build: smoke test on real hardware; heap-tag mismatch reports captured.
- Leak check: ASan `detect_leaks=1` on the host suite; Android: `adb shell dumpsys meminfo` deltas in a
  soak test.

## 6. Benchmarks as tests

- Native: `google/benchmark`, results in JSON, compared against a stored baseline with a tolerance
  (e.g. 10% regression fails the nightly).
- Android: `Macrobenchmark` for cold start of the editor screen, scroll/drag on a 500-clip timeline,
  and export throughput. Record on a fixed device profile and state the device in the report.

## 7. `tools/gate.sh` contract

```bash
#!/usr/bin/env bash
set -euo pipefail
LOG="docs/evidence/GATE_$(date -u +%Y%m%dT%H%M%SZ).log"
run() { echo "== $* " | tee -a "$LOG"; "$@" 2>&1 | tee -a "$LOG"; }
run ./gradlew --no-daemon :core-edit:test
run cmake --preset host-tests && cmake --build --preset host-tests
run ctest --preset host-tests --output-on-failure
run ./build/host/tests/property/tl_property_tests --max-success=10000
run ./build/host/tests/fuzz/tl_fuzz_command_stream -runs=20000
run ./gradlew detekt ktlintCheck lint
run clang-tidy -p build/compile_commands.json src/**.cpp
echo "GATE PASS" | tee -a "$LOG"
```

- Non-zero exit on any failure; the log path is printed at the end and quoted in the final report.
- Coverage gate: `llvm-cov export` and fail if `core-model`, `core-edit`, `core-history` drop below 85%
  lines / 75% branches.
- Forbidden in the gate: skipping tests, lowering thresholds, `--filter` used to hide failures. The gate
  script itself is reviewed; changes to it need an ADR.

## 8. Reporting template (per test class)

| Suite | Cases | Result | Log |
|---|---|---|---|
| unit (GoogleTest) | 187 | pass | GATE_... |
| property (RapidCheck) | 10,000/case | pass | GATE_... |
| golden frames | 8 | 8/8 SSIM ≥ 0.995 | GATE_... |
| fuzz command stream | 20,000 runs | 0 crashes | GATE_... |
