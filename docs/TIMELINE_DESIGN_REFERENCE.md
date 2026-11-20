# Portrait Timeline UI reference

The original user-provided editor screenshot is preserved at [`design-reference/CinemaCutPro_Editor_Toolbar.png`](design-reference/CinemaCutPro_Editor_Toolbar.png). Keep it with the project; it is the visual acceptance reference for the portrait Timeline section.

## Required visual structure

- Keep the preview and project header above the editing controls; place the compact Timeline/Color tabs below the work area.
- Use a rounded near-black command bar with grouped Undo/Redo controls, a red Import Video action, Split/Delete, and zoom controls.
- Render the timeline as a black editing surface with a narrow, fixed left track-label column, a compact time ruler, subtle track separators, and a thumbnail-first clip strip.
- Selected clips use a fine red outline, red trim grips, and a readable label/duration over the thumbnails.
- Preserve the red playhead marker and continuous vertical red line. It must remain visible over the ruler and clips, and its drag/scrub behavior must stay independent of clip edits.
- Keep scroll/zoom, virtualization, Core v2 edit preview/commit, and clip-selection semantics intact while changing presentation.

This design note and the retained screenshot are not device-rendering evidence. The Compose implementation must still pass CI and be checked on a handset by the user.
