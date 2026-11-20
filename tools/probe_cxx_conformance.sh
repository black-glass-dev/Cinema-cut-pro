#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CXX="${CXX:-c++}"
STANDARD="${TL_PROBE_STANDARD:-c++23}"
OUTPUT="${1:-${ROOT}/build/generated/cxx_features.hpp}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
mkdir -p "$(dirname "${OUTPUT}")"

"${CXX}" -std="${STANDARD}" -Wall -Wextra -Werror \
  "${ROOT}/cmake/ProbeCxxConformance.cpp" -o "${TMP}/probe"
"${TMP}/probe" > "${OUTPUT}.tmp"

if printf 'int main() { return 0; }\n' | "${CXX}" -std=c++2c -x c++ -o "${TMP}/cxx2c-probe" - >/dev/null 2>&1; then
  sed -i 's/#pragma once/#pragma once\n#define TL_CXX2C_FLAG_AVAILABLE 1/' "${OUTPUT}.tmp"
else
  sed -i 's/#pragma once/#pragma once\n#define TL_CXX2C_FLAG_AVAILABLE 0/' "${OUTPUT}.tmp"
fi
mv "${OUTPUT}.tmp" "${OUTPUT}"
printf 'Generated %s using %s -std=%s\n' "${OUTPUT}" "${CXX}" "${STANDARD}"
cat "${OUTPUT}"
