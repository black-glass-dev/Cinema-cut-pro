#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"
mkdir -p docs/evidence
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="docs/evidence/GATE_${STAMP}.log"
status=0

record_status() {
  local command_status="$1"
  if [ "${command_status}" -ne 0 ]; then
    status=1
  fi
}

run() {
  local label="$1"
  shift
  printf '\n== %s:' "${label}" | tee -a "${LOG}"
  printf ' %q' "$@" | tee -a "${LOG}"
  printf '\n' | tee -a "${LOG}"
  "$@" 2>&1 | tee -a "${LOG}"
  local command_status=${PIPESTATUS[0]}
  if [ "${command_status}" -ne 0 ]; then
    printf 'COMMAND EXIT: %s\n' "${command_status}" | tee -a "${LOG}"
  fi
  return "${command_status}"
}

blocked() {
  printf '\nBLOCKED: %s\n' "$1" | tee -a "${LOG}"
  if [ "${status}" -eq 0 ]; then
    status=2
  fi
}

printf 'Timeline Core v2 gate: %s\n' "${STAMP}" | tee "${LOG}"
printf 'Branch: %s\n' "$(git branch --show-current)" | tee -a "${LOG}"
printf 'Compiler: ' | tee -a "${LOG}"
if command -v "${CXX:-c++}" >/dev/null 2>&1; then
  "${CXX:-c++}" --version | head -n 1 | tee -a "${LOG}"
else
  printf 'missing (%s)\n' "${CXX:-c++}" | tee -a "${LOG}"
  blocked "C++ compiler unavailable; native tests cannot run."
fi

if run 'Native export regression suite' python3 tools/run_native_export_tests.py; then
  :
else
  record_status "$?"
fi

if [ -x tools/run_timeline_core_tests.sh ]; then
  if run 'Timeline Core v2 direct C++23 tests' ./tools/run_timeline_core_tests.sh; then
    :
  else
    record_status "$?"
  fi
else
  blocked 'Timeline Core v2 test runner is missing or not executable.'
fi

if [ -x tools/probe_cxx_conformance.sh ]; then
  if run 'C++23 conformance probe' ./tools/probe_cxx_conformance.sh; then
    :
  else
    record_status "$?"
  fi
else
  blocked 'C++ conformance probe is missing or not executable.'
fi

CMAKE_BIN="${CMAKE_BIN:-cmake}"
CTEST_BIN="${CTEST_BIN:-ctest}"
NINJA_BIN="${NINJA_BIN:-ninja}"
if command -v "${CMAKE_BIN}" >/dev/null 2>&1 &&
   command -v "${CTEST_BIN}" >/dev/null 2>&1 &&
   command -v "${NINJA_BIN}" >/dev/null 2>&1; then
  if run 'CMake host-debug configure' "${CMAKE_BIN}" --preset host-debug; then
    if run 'CMake host-debug build' "${CMAKE_BIN}" --build --preset host-debug; then
      if run 'CMake host-debug CTest' "${CTEST_BIN}" --preset host-debug --no-tests=error; then
        :
      else
        record_status "$?"
      fi
    else
      record_status "$?"
    fi
  else
    record_status "$?"
  fi

  if run 'CMake host-release configure' "${CMAKE_BIN}" --preset host-release; then
    if run 'CMake host-release build' "${CMAKE_BIN}" --build --preset host-release; then
      if run 'CMake host-release CTest' "${CTEST_BIN}" --preset host-release --no-tests=error; then
        :
      else
        record_status "$?"
      fi
    else
      record_status "$?"
    fi
  else
    record_status "$?"
  fi

  if run 'CMake host-asan configure' "${CMAKE_BIN}" --preset host-asan; then
    if run 'CMake host-asan build' "${CMAKE_BIN}" --build --preset host-asan; then
      if run 'CMake host-asan CTest' "${CTEST_BIN}" --preset host-asan --no-tests=error; then
        :
      else
        record_status "$?"
      fi
    else
      record_status "$?"
    fi
  else
    record_status "$?"
  fi

  if command -v "${GCOV_BIN:-gcov}" >/dev/null 2>&1; then
    if run 'CMake host-coverage configure' "${CMAKE_BIN}" --preset host-coverage; then
      if run 'CMake host-coverage build' "${CMAKE_BIN}" --build --preset host-coverage; then
        if run 'CMake host-coverage CTest' "${CTEST_BIN}" --preset host-coverage --no-tests=error; then
          if run 'Timeline Core line/branch coverage gate' python3 tools/check_timeline_core_coverage.py; then
            :
          else
            record_status "$?"
          fi
        else
          record_status "$?"
        fi
      else
        record_status "$?"
      fi
    else
      record_status "$?"
    fi
  else
    blocked "gcov unavailable (GCOV_BIN=${GCOV_BIN:-gcov}); line/branch coverage gate was not run."
  fi

  if run 'CMake host-benchmark configure' "${CMAKE_BIN}" --preset host-benchmark; then
    if run 'CMake host-benchmark build' "${CMAKE_BIN}" --build --preset host-benchmark --target timeline_core_v2_benchmark; then
      if run 'Host-only Timeline Core microbenchmark' build/cmake/host-benchmark/app/src/main/cpp/timeline_core_v2/timeline_core_v2_benchmark; then
        :
      else
        record_status "$?"
      fi
    else
      record_status "$?"
    fi
  else
    record_status "$?"
  fi
else
  blocked "CMake/CTest/Ninja unavailable (CMAKE_BIN=${CMAKE_BIN}, CTEST_BIN=${CTEST_BIN}, NINJA_BIN=${NINJA_BIN}); host debug/release/sanitizer/coverage/benchmark gates were not run."
fi

if run 'Whitespace/error check' git diff --check; then
  :
else
  record_status "$?"
fi

if command -v java >/dev/null 2>&1; then
  if run 'Android JVM tests and requested debug assemble' ./gradlew :app:testDebugUnitTest :app:assembleDebug; then
    :
  else
    record_status "$?"
  fi
else
  blocked 'Java/JDK absent; Android Gradle tests and :app:assembleDebug cannot start.'
fi

if [ "${status}" -eq 0 ]; then
  printf '\nGATE PASS\n' | tee -a "${LOG}"
else
  printf '\nGATE BLOCKED/FAILED (exit %s)\n' "${status}" | tee -a "${LOG}"
fi
printf 'Evidence log: %s\n' "${LOG}"
exit "${status}"
