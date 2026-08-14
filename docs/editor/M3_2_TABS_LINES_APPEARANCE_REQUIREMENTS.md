# M3.2 tabs, line numbers, and appearance requirements

M3.2 is the final editor-shell milestone before M4 document synchronization. It extends the device-proven M3.1 shell without changing the supported offline project, process, or toolchain boundary.

## File tabs

- The editor always shows one horizontal, scrollable tab for every open buffer when at least one file is open.
- Opening or creating a file focuses its tab. Previously open files remain visible and retain their buffer, dirty state, selection, undo history, and recovery snapshot.
- Selecting an inactive tab changes only editor focus; it does not save, close, rebuild, or discard another file.
- Active and inactive tabs are visually and semantically distinct. Dirty tabs have a visible marker. Close continues through the M3.1 Save/Discard/Cancel workflow.

## Line numbers

- A read-only gutter displays one-based numbers for every logical source line, including the final empty line after a trailing newline.
- The gutter and source use the same monospaced line metrics and remain vertically aligned while scrolling.
- Line numbers are presentation only: they are not copied into source, included in search, persisted, or sent to Lean.
- Editing, cursor selection, syntax highlighting, Unicode input, search, undo/redo, save, recovery, and accessibility remain supported.

## Settings and appearance

- The navigation drawer gains a top-level **Settings** destination after project actions. Its placement and page model must allow future destinations without restructuring the drawer.
- Settings is a full-screen page with its own Back action and an **Appearance** row. Appearance opens a distinct full-screen child page, also with Back navigation.
- Appearance initially contains a labeled, accessible **Dark theme** switch. More appearance preferences may be added alongside it later.
- The explicit light/dark choice is app-private configuration, persists across Activity/process recreation, and applies immediately and consistently to the shell, editor, drawer, dialogs, Settings, and Appearance.

## Acceptance

Host/state tests cover tab focus/retention and line-number derivation. Physical API-33 acceptance opens two files, switches both ways with an unsaved buffer retained, verifies the gutter after adding/removing lines, navigates Drawer → Settings → Appearance, toggles dark theme, recreates and cold-launches the Activity, restores light mode if that was the initial state, and confirms the existing offline Run path leaves no Lean/Lake child.
