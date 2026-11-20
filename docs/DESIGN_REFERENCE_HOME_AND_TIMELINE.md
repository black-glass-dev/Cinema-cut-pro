# Home and portrait editor acceptance reference

User-provided references are preserved in this repository:

- Home landing page: [`design-reference/CinemaCutPro_Home_v2.png`](design-reference/CinemaCutPro_Home_v2.png)
- Timeline/editor screenshots: [`design-reference/issue-captures/`](design-reference/issue-captures/)
- Earlier timeline toolbar/clip reference: [`design-reference/CinemaCutPro_Editor_Toolbar.png`](design-reference/CinemaCutPro_Editor_Toolbar.png)

## Required palette

- Near-black: `#1A1A1E` for the brand mark, project action card, and dark buttons.
- Page/card neutrals: `#F2F2F4` through `#E4E4E8`, with white recent-project cards.
- Accent red: `#FF3B30`; reserve for the logo, plus/import action, primary Create button, selected tab, and playhead/selection emphasis.

## Required flow and layout

1. Every cold app launch starts at Home, not directly in the editor.
2. Home includes Create Project, Input Project (JSON document picker), recent-project list/grid, project settings entry, and open/edit behavior for each recent item.
3. Editor header/preview and transport sit above the Timeline controls. A large, accessible, draggable separator resizes preview and editing areas without letting either disappear.
4. Timeline taps and drags map from the scrollable track pane's local X coordinate plus horizontal scroll to the same frame coordinate used to draw the red playhead. A tap on a clip selects it and seeks to that tap position.
5. If the selected video source is opening and Media3 has not emitted a first frame, show a decoded poster rather than an indefinite black rectangle. If a ready proxy never emits a frame, retry the original; if Media3 stalls on the original, try the alternate decoder once.

These source requirements need automated verification and handset visual/gesture/playback checks; preserving a reference image is not itself proof of pixel-perfect rendering or runtime playback.
