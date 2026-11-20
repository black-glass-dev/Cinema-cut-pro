# ADR 0001: One Native Timeline Edit Authority

- Status: Accepted for the v2 migration; integration remains in progress.
- Date: 2026-11-11.

## Context

The current app has Kotlin core edits, a separate model-based `TimelineEngine`, an `AdvancedEditEngine`, a JNI edit adapter, and multiple native snap/layout paths. In the inspected move and trim paths these implementations do not share one result contract. The user requires exactly one authoritative edit/snap/history implementation and no silent Kotlin fallback.

## Decision

Create one pure C++23 timeline core as the authority for integer time, project validation, edit results/errors, snapping, and command history. Kotlin timeline models remain persistence/UI DTOs during migration; the JNI adapter converts values and returns immutable results but does not calculate edit outcomes. UI ghost and commit requests call the same native operation. If the core library is unavailable, edit actions report an explicit unavailable error and do not mutate project/history.

## Alternatives considered

1. Keep Kotlin as authority and use JNI only for acceleration: rejected because it preserves duplicate semantics and violates the requested ownership contract.
2. Keep native and Kotlin engines in parallel with runtime fallback: rejected because fallback behavior already diverges and can silently change edit outcomes.
3. Replace all application persistence/media models in one step: deferred; the C++ core will first own semantic operations, while DTO conversion and schema changes are separately tested.

## Consequences

- JNI signatures, errors, and model serialization become compatibility boundaries and require tests.
- Kotlin cannot optimistically recalculate trim/snap results.
- Android integration remains unverified until JDK/SDK/NDK become available; host tests alone do not prove JNI correctness.
- Legacy engines must be removed or compile-guarded after call sites are migrated; see `docs/MIGRATION.md`.
