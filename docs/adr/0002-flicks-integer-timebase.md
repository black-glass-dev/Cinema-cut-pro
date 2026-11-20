# ADR 0002: Integer Flicks Timebase with Rational Frame Rates

- Status: Accepted for the v2 core.
- Date: 2026-11-11.

## Context

The persisted app model uses frame-count `Long`s and project frame rates may be rational (including 24000/1001). Floating-point seconds in edit math cause rounding and parity problems. The requested core contract specifies 705,600,000 flicks per second.

## Decision

Use a strong `Flicks` value type (`int64_t`) and a normalized rational frame rate in the core. Convert a frame boundary to flicks with integer arithmetic and an explicit round-to-nearest, ties-to-even rule; use checked/saturating intermediates and return a stable overflow error rather than committing a saturated invalid range. Persisted frame-count DTOs are converted at the Kotlin/JNI boundary. A conversion that cannot round-trip to the requested frame boundary is rejected and tested, not silently approximated.

## Alternatives considered

1. Keep frame counts as the internal core unit: rejected because timeline and audio/source timebases need rational rescaling.
2. Use `double` seconds: rejected due nondeterministic edit boundaries and the user's explicit contract.
3. Use nanoseconds: rejected because the specified flick timebase exactly represents common video and audio rates while retaining integer math.

## Consequences

- Source ranges, timeline ranges, and available media ranges must all use the same explicit units inside the core.
- Conversion tests cover common integer and fractional frame rates, overflow, ties, and round trips.
- Export/preview adapters must be audited before claiming frame-identical output.
