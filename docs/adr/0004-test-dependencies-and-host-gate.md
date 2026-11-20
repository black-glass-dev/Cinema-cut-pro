# ADR 0004: Keep the Initial Host Core Gate Dependency-Free

- Status: Accepted temporarily; replacing it with verified GoogleTest/RapidCheck is still required.
- Date: 2026-11-11.

## Context

The environment has GCC 12.2 and Python, but no system CMake/Ninja, JDK, Android SDK, or NDK. A source/version check for GoogleTest/RapidCheck and reproducible dependency resolution have not yet been completed. The user forbids invented pins or test claims.

## Decision

Start with a small deterministic C++23 host test executable using only the standard library, direct `g++` compile command, and fixed-seed generated cases. Do not label that executable GoogleTest, RapidCheck, or fuzzing. Add CMake/Ninja presets for the core and make the main gate report Android toolchain absence as blocked rather than skip silently. Replace or extend the harness only after upstream tags/license/build are verified.

## Alternatives considered

1. Guess and pin recent GoogleTest/RapidCheck releases: rejected because it violates the version-verification rule.
2. Add unverified test-framework source to the repository: rejected until source SHA/license and build are checked.
3. Stop all core work because the frameworks are not preinstalled: rejected; host unit/property-style checks can still prove the core subset.

## Consequences

- Initial host test output is useful but does not satisfy the requested named framework stack.
- Android/JNI gates remain blocked in the present environment.
- A later ADR must record verified test-framework pins before replacing the harness.
