#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CXX="${CXX:-c++}"
OUT_DIR="${ROOT}/build/timeline-core-v2-tests"
mkdir -p "${OUT_DIR}"
"${CXX}" -std=c++23 -O2 -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Werror \
  -I "${ROOT}/app/src/main/cpp/timeline_core_v2/include" \
  "${ROOT}/app/src/main/cpp/timeline_core_v2/src/timeline_core.cpp" \
  "${ROOT}/app/src/main/cpp/timeline_core_v2/tests/timeline_core_test.cpp" \
  -o "${OUT_DIR}/timeline_core_v2_tests"
"${OUT_DIR}/timeline_core_v2_tests"
