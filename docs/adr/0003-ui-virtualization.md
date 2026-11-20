# ADR 0003: Virtualize Timeline Layout at the UI Boundary

- Status: Accepted for the v2 UI migration; device performance remains unverified.
- Date: 2026-11-11.

## Context

The current Compose canvas emits and measures every clip in the inspected layout path, even though only part of the timeline is visible. The requirement targets 500+ clips and 20+ tracks, but no device or Android benchmark runner is present in this workspace.

## Decision

Keep semantic edit decisions in the native core and virtualize UI composition/measurement using a visible time range plus bounded overscan. Use a single gesture state machine. The UI draws the exact immutable native preview result and sends the same request for commit. Perfetto/Macrobenchmark results must name the device and remain `UNVERIFIED` until a real device run exists.

## Alternatives considered

1. Keep all clips composed and optimize child composables: rejected because work remains proportional to the whole project on every viewport change.
2. Move all drawing to one Canvas but keep a second UI-side edit model: rejected because it would duplicate semantic calculations.
3. Claim the native layout timer proves 60 fps: rejected; it does not measure Compose, thumbnails, frame scheduling, or device thermal behavior.

## Consequences

- Visible-range math must be shared with the core and culling boundaries tested.
- Thumbnail generation must be cancelled/reused as clips leave the overscan window.
- A host layout benchmark is useful but is not a substitute for Android Macrobenchmark/Perfetto evidence.
