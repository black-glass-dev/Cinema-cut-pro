# New Project and preview layout reference

User-provided reference screenshots, preserved exactly:

- `docs/design-reference/CinemaCutPro_NewProject_Full.png` — full new-project settings screen.
- `docs/design-reference/CinemaCutPro_Custom_Inputs.png` — expanded custom aspect, resolution, and frame-rate controls.
- `docs/design-reference/issue-captures/NewProject_preview_issue_184051.png` — imported-video playback error with oversized diagnostic text.
- `docs/design-reference/issue-captures/NewProject_preview_issue_184200.png` and `NewProject_preview_issue_184207.png` — aspect-ratio-locked portrait viewport and oversized blank preview pane.

Acceptance notes:

- New Project settings use the supplied light-gray canvas, white two-column preset cards, thin neutral separators, selected red `#FF3B30` outlines/indicators, compact centered header, and pinned Cancel/Create actions.
- Aspect ratio options include 16:9, 9:16, 1:1, 4:5, 5:4, 3:2, 4:3, 21:9, 2.35:1, 2.39:1, plus an expandable custom ratio editor with lock control.
- Resolution presets follow the selected aspect ratio. Custom resolution supports independent pixel dimensions and landscape/portrait orientation; fps supports all supplied presets and an editable 1–240 fps custom field.
- Preview canvas ratio is derived from actual project pixel width/height and fitted only inside the measured remainder of the viewport pane. Resize dragging uses measured editor bounds. Playback diagnostics are a single-line status with a details dialog, never a tall block inside the viewport.
- The error shown in the supplied device capture is `UnsupportedOperationException: Replaying when enableReplayableCache is set to false`. The Media3 fallback previously explicitly installed an empty video-effects pipeline for ordinary media; it no longer does, and effects are only attached lazily when a grading effect is activated. Confirm on handset with ordinary local MP4 playback and timeline seeks; code/CI cannot prove device decoder behavior.
