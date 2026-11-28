# Advanced Timeline Editing: Research and Implementation Audit

**Audit date:** 2026-10-10 (Arena session date)

## Research signals

Professional desktop timelines distinguish normal move/overwrite, insert, ripple, roll, slip, and slide edits instead of treating every drag as a reorder. Adobe's help material also describes timeline markers for sync points/edit decisions and ripple/roll trimming as distinct operations. DaVinci Resolve 20.2 documents per-track Sync Lock, independent from track auto-selection, to preserve intentional cross-track sync during ripple work. Mobile-oriented KineMaster reviews emphasize multi-layer timelines, frame-accurate trim, pinch zoom, keyframe animation, and separate audio controls.

Sources:

- [Adobe Premiere Pro: trim clips (ripple, roll, slip, slide)](https://helpx.adobe.com/premiere-pro/using/trimming-clips1.html)
- [Adobe Premiere Pro: remove clips and close gaps with ripple delete](https://helpx.adobe.com/premiere-pro/using/remove-clips.html)
- [Adobe Premiere Pro: markers and timeline navigation](https://helpx.adobe.com/premiere-pro/using/whats-new/2024-1.html)
- [DaVinci Resolve 20.2 New Features Guide (official PDF)](https://documents.blackmagicdesign.com/SupportNotes/DaVinci_Resolve_20.2_New_Features_Guide.pdf)
- [KineMaster: multi-layer editing](https://www.kinemaster.com/features/multi-layer)
- [KineMaster: keyframe animation](https://www.kinemaster.com/features/keyframe)

## User recording review

Reviewed the supplied `Screen_Recording_20261010_172827_Cinema Cut Pro.mp4` from Google Drive (64-second capture). It shows a new project being created, a portrait source being imported, and the clip appearing selected on the first video track near timeline zero. The captured clip stays in that visible location while the source preview changes; the recording does not clearly show the finger-down/drop point or the nearest-gap toast from the earlier evidence, so it is not sufficient by itself to prove the exact erroneous target frame. It does confirm why the UI must not silently reinterpret a drop: the selected clip's timeline position and source-preview position are distinct. The source diagnosis was cross-checked against the screen callbacks and the existing movement planner: the screen callback previously called `resolveStartFrame` before the planner could displace unlocked blockers, which replaced the requested frame with a nearby gap.

## Implemented in this work

- **Precise move/drop semantics:** the requested timeline frame is retained. On a same-track collision, unlocked clips that block the requested interval are displaced forward in one previewed/undoable command batch; locked blockers are never moved and use the nearest legal gap fallback. Normal and long-press move paths share the planner.
- **Narrow move snapping:** clip-move snapping is capped at 2 px in both standard and advanced timelines. Trim snapping keeps its existing user threshold.
- **User-facing project markers:** the advanced timeline's Marker tool creates an undoable marker at the tapped frame; markers render with their label and frame-aligned guide in both timeline canvases.
- **Arbitrary multi-selection:** the advanced tool rail has a Multi toggle. Selected clips are shown as a set and dragged as a single native group command; linked-selection behavior remains independently available.

## Existing timeline capabilities found in the repository

- Selection, trim, blade/split, ripple trim/delete, roll, slip, slide, nudge, track reorder, track lock, visibility, mute/solo, playhead timecode, snapping, zoom/pinch, undo/redo, linked selection/locking, proxy-backed preview, and basic clip-level inspector fields.
- The native edit core validates edits atomically and has undo/redo history; the standard/advanced Compose surfaces already use native edit preview and commit for their core trim/move paths.

## Important gaps that remain

| Priority | Gap | Why it matters / current state |
|---|---|---|
| P1 | Per-track sync-lock for ripple operations | There is a global same-track/all-tracks ripple choice and track locking, but no independent per-track sync-lock bit. Resolve's documented model separates sync-lock from track selection/locking. Adding it safely requires project persistence, UI, native transport, and tests. |
| P1 | Audio waveform generation and timeline rendering | `MediaAsset.waveformUri` exists, but this repository has no audio-peak generation/render pipeline connected to timeline clips. Waveforms are essential for dialogue/music alignment on a mobile timeline. |
| P1 | Explicit insert / overwrite / replace / close-gap operations | Core commands cover move/insert/delete, but the user-facing editing workflow does not yet expose a clear source-to-timeline insert/overwrite choice or a reliable selected-gap close operation. These should be separate commands with clear ripple/sync scope. |
| P1 | Frame-step/typed timecode placement and trim | The playhead shows timecode, but there is not yet a complete, validated numeric frame/timecode edit surface for clip start/end and marker placement. |
| P2 | Drag-box/lasso selection and selection-wide delete/ripple UI | Multi-selection is now available by toggling Multi and tapping clips; lasso selection and a clear selection action menu remain absent. |
| P2 | Marker editing | Markers can be added and shown; rename, delete, clip/track markers, marker colors/types, and drag-to-move are not yet exposed. |
| P2 | Audio keyframes/envelopes and transition handles | The model has audio/adjustment primitives, but a polished timeline keyframe/envelope and transition-editing surface is not complete. |
| P2 | Device performance/accessibility acceptance | Existing virtualization tests cover large project data in JVM tests, but sustained 500+ clip device testing, touch-target checks, and accessibility pass still need real Android hardware/Perfetto evidence. |

These gaps are deliberately recorded rather than marked complete based only on model classes or source stubs. The immediate placement regression is addressed first; remaining P1 items should be implemented as separately testable, persistence-aware features.
