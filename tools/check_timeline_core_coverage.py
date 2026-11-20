#!/usr/bin/env python3
"""Report and gate gcov line/branch coverage for the Timeline Core v2 implementation."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_NAME = "timeline_core.cpp"
OBJECT_DIR = ROOT / (
    "build/cmake/host-coverage/app/src/main/cpp/timeline_core_v2/"
    "CMakeFiles/timeline_core_v2.dir/src"
)
GCOV = os.environ.get("GCOV_BIN", "gcov")
MINIMUM_LINE = float(os.environ.get("TL_MIN_LINE_COVERAGE", "80"))
MINIMUM_BRANCH_TAKEN = float(os.environ.get("TL_MIN_BRANCH_TAKEN", "60"))


def main() -> int:
    command = [GCOV, "-b", "-c", "-f", f"{SOURCE_NAME}.gcda"]
    try:
        result = subprocess.run(
            command, check=False, text=True, capture_output=True, cwd=OBJECT_DIR
        )
    except FileNotFoundError as error:
        print(f"coverage tool/data unavailable; configure/build/test the host-coverage preset first: {error}",
              file=sys.stderr)
        return 2
    except OSError as error:
        print(f"coverage tool unavailable: {GCOV}: {error}", file=sys.stderr)
        return 2

    if result.returncode != 0:
        if result.stdout:
            print(result.stdout, end="")
        if result.stderr:
            print(result.stderr, end="", file=sys.stderr)
        print(f"gcov exited {result.returncode}", file=sys.stderr)
        return result.returncode

    lines = result.stdout.splitlines()
    source_index = next(
        (index for index, line in enumerate(lines)
         if line.startswith("File '") and line.endswith(f"/{SOURCE_NAME}'")),
        None,
    )
    if source_index is None:
        print(f"gcov did not report {SOURCE_NAME}", file=sys.stderr)
        return 1

    source_summary = lines[source_index + 1:source_index + 5]
    line_match = next((re.search(r"Lines executed:([0-9]+(?:\.[0-9]+)?)% of ([0-9]+)", line)
                       for line in source_summary if line.startswith("Lines executed:")), None)
    branch_match = next((re.search(r"Taken at least once:([0-9]+(?:\.[0-9]+)?)% of ([0-9]+)", line)
                         for line in source_summary if line.startswith("Taken at least once:")), None)
    if line_match is None or branch_match is None:
        print("gcov did not report source line and branch coverage", file=sys.stderr)
        print("\n".join(lines[source_index:source_index + 8]), file=sys.stderr)
        return 1

    line_percentage = float(line_match.group(1))
    executable_lines = int(line_match.group(2))
    branch_percentage = float(branch_match.group(1))
    branch_count = int(branch_match.group(2))
    print("\n".join(lines[source_index:source_index + 4]))
    print(
        f"Timeline Core v2 coverage: lines {line_percentage:.2f}%/{MINIMUM_LINE:.2f}% minimum "
        f"({executable_lines} executable); branches taken {branch_percentage:.2f}%/"
        f"{MINIMUM_BRANCH_TAKEN:.2f}% minimum ({branch_count} branches)"
    )
    if line_percentage < MINIMUM_LINE or branch_percentage < MINIMUM_BRANCH_TAKEN:
        print("coverage threshold not met", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
