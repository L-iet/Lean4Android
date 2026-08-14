# M3.1 editor application-shell requirements

Status: accepted requirements on 2026-08-14. This milestone must complete before M4 editor-to-LSP work begins.

## Intent

Adopt a compact Android IDE shell inspired by the useful navigation patterns of Pydroid 3 without copying its branding or broad terminal/package capabilities. Preserve the completed native Compose editor, offline project model, typed process boundaries, recovery behavior, and phone/tablet accessibility baseline.

## Top application bar

The editor screen uses one top app bar with:

- a leading hamburger icon that opens and closes the left navigation drawer;
- a title block next to it showing the active filename prominently and its project-relative parent path as secondary text;
- a Run/Play icon that saves the current project, builds it, and runs the supported Lean project entry behavior;
- a Files icon that opens an anchored menu containing icon-and-text rows for **New**, **Open**, **Save**, **Save As**, and **Close**; and
- a More (three vertical dots) icon that opens an anchored menu containing icon-and-text rows for **Undo**, **Redo**, and **Find**.

Each menu must be visually attached to its triggering icon and must close when the user taps/clicks anywhere outside it, presses Back/Escape, selects an item, or opens the other top-bar menu. At most one attached menu may be open at a time. Menus expose disabled states and have content descriptions and keyboard/focus behavior. They are menus, not full-screen dialogs. Commands that need a path or confirmation may open a focused dialog after their menu item is selected.

## File command semantics

- **New** creates a validated project-relative Lean source and opens it.
- **Open** opens the full-screen Open workspace flow specified in `M3_1_OPEN_WORKSPACE_REQUIREMENTS.md`, supporting Recent, My Projects, safe project archive/folder import, standalone Lean files, and adding one Lean file to a project. External content is imported into an app-managed workspace; it is never a live Lake workspace or a source of network/executable capability.
- **Save** atomically saves the active buffer without building. It clears that buffer's dirty state only after persistence succeeds.
- **Save As** atomically saves the active buffer under a new validated project-relative path, retains the original file, and switches to the new file. This replaces the visible **Rename** command; rename is not mislabeled as Save As.
- **Close** closes the active editor tab but does not delete its file. A dirty tab requires an explicit Save, Discard, or Cancel choice. Closing the last tab leaves a useful empty editor state from which New/Open and the drawer remain reachable.

The existing project repository protections remain mandatory: traversal and absolute paths, symlink escapes, invalid source types, and case-folded collisions are rejected. Recovery snapshots must remain consistent after every file command and Activity/process recreation.

## Run semantics

Run/Play is the primary action and replaces the old prominent **Build project** surface. It must save all applicable dirty buffers, run the supported offline Lake build, then execute the project's supported Lean entry behavior and show bounded structured output. It must not silently enable native Lake targets, arbitrary scripts, downloaded executable code, Git/network dependencies, or a general terminal. Failure and cancellation must leave dirty/saved state truthful and no orphan Lean/Lake process.

## Navigation drawer

The hamburger icon controls a left navigation drawer that works on compact and expanded layouts. Its first group is a collapsible **Project** section containing the hierarchical project file tree. The drawer also contains **Build project** and **Verify runtime** actions, with room for later destinations without redesigning the editor top bar. M3.2 subsequently added Settings; planned M3.3 adds **Export project** after project actions and before global Settings as specified in `../project/M3_3_PROJECT_EXPORT_REQUIREMENTS.md`.

The drawer preserves expansion state across ordinary Activity recreation, clearly indicates the active file, and closes when the user taps/clicks outside it, presses Back/Escape, or activates the hamburger control again. Opening the drawer closes any attached top-bar menu. File-tree nodes and actions require accessible names, selected/expanded state, adequate touch targets, and keyboard traversal.

## Search and editor behavior

Find remains an inline editor surface. Its previous **Close Search** text button becomes a trailing X icon button with the accessible label **Close search**. Undo, Redo, Find, Ctrl-F, Ctrl-Z, Ctrl-Shift-Z, Ctrl-Y, and Ctrl-S remain functional; Ctrl-S now means Save rather than Save-and-build. A separate documented shortcut may invoke Run.

## Acceptance

Host tests cover command semantics, dirty-state transitions, Save As collision/path handling, close confirmation, menu enablement, and drawer state restoration. Physical-device validation covers compact and tablet portrait/landscape layouts, anchored menus, drawer/tree expansion, keyboard/focus and accessibility semantics, process/Activity recreation, Save without build, Save As retaining the original, Run output, cancellation, and exact orphan-process checks.
