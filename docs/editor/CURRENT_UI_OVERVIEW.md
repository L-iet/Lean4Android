# Current UI overview

Lean4Android currently uses a native Jetpack Compose UI. The main screen is a mobile IDE-style workspace built around a Material `Scaffold`: a top app bar sits above an adaptive editor workspace, while a navigation drawer and several dialogs or full-screen pages provide project, file, import/export, and settings workflows.

The current production UI baseline is complete through M4.6. It includes multiple file tabs, a line-numbered Lean editor, search, syntax and diagnostic decoration, a Lean/Unicode symbol row, live LSP diagnostics and goals, collapsible/resizable panes, light/dark appearance settings, project navigation, and protected file/project lifecycle actions.

The planned separation of these composables from their visual tokens and component styles is specified in [`UI_STYLE_ARCHITECTURE.md`](UI_STYLE_ARCHITECTURE.md). That design preserves this behavioral structure while moving theme, color, typography, dimension, spacing, shape, and component visual contracts into a Compose-native Kotlin design system.

## Main screen structure

The visible editor screen is assembled by `LeanEditorScreen` in [`app/src/main/java/org/lean4android/app/MainActivity.kt`](../../app/src/main/java/org/lean4android/app/MainActivity.kt). That composable owns transient UI state such as the open menus, drawer, search field, text-field selections, undo histories, run state, and collapsed-panel state. It also connects the visible controls to the activity's project, process, persistence, and LSP callbacks.

At a high level, the screen is arranged as follows:

```text
Top app bar
├── navigation drawer button
├── active filename and project-relative location
├── Run
├── Files menu
└── More menu

Adaptive workspace
├── editor/output area
│   ├── file tabs
│   ├── optional Find row
│   ├── line-numbered source editor
│   ├── Lean/Unicode symbol row
│   ├── Messages panel, when diagnostics exist
│   └── Output splitter and Output panel
└── Goals splitter and Goals panel
    ├── right of the editor in landscape by default
    └── below the editor in portrait by default
```

The Goals position can be set to Auto, Right side, or Bottom under Settings → Editor. Auto resolves to Bottom in portrait and Right side in landscape. Goals and Output have draggable, keyboard-operable, accessible splitters and independent persisted sizes/collapsed states. A docked on-screen keyboard temporarily hides Output without changing its saved state; Messages and the symbol row remain available in the resized workspace.

The navigation drawer is also implemented in `MainActivity.kt`. It contains the collapsible hierarchical Project tree, build/runtime actions, project import/export and lifecycle actions, and Settings entry points. Open workspace, Settings, Appearance, Editor settings, confirmation dialogs, and file/project dialogs are rendered from the same main screen implementation.

## Where the requested UI code lives

| Region | Primary code | What it contains |
| --- | --- | --- |
| Top bar | [`MainActivity.kt`](../../app/src/main/java/org/lean4android/app/MainActivity.kt), the `Scaffold`/`TopAppBar` block in `LeanEditorScreen` | Hamburger button, active filename and path, Run button, Files menu, and More/LSP menu. |
| Editor | [`MainActivity.kt`](../../app/src/main/java/org/lean4android/app/MainActivity.kt), primarily `EditorContent`, `FileTabStrip`, `EditorSymbolRow`, and `editorLineNumbers` | Tabs, Find row, gutter, `BasicTextField`, cursor callbacks, Hover context-menu action, symbol insertion, progress/cancel row, and placement of Messages and Output. |
| Output panel | [`MainActivity.kt`](../../app/src/main/java/org/lean4android/app/MainActivity.kt), `OutputPanel`; placement is in `EditorContent` | Displays idle, running, cancelled, failed, completed run/build, and runtime-integrity results in selectable monospaced text. Its splitter and IME visibility behavior are managed by `EditorContent`. |
| Messages panel | [`MainActivity.kt`](../../app/src/main/java/org/lean4android/app/MainActivity.kt), inside `EditorContent` | Shows the active file's current LSP diagnostics and count. It is collapsible, bounded and scrollable; only severity-1 diagnostics switch it to error colors. |
| Goals panel | [`MainActivity.kt`](../../app/src/main/java/org/lean4android/app/MainActivity.kt), `GoalsPanel`; placement is in `LeanEditorScreen` | Shows Lean server status, tactic goals, expected types, Hover Markdown, completion text, navigation messages, and references. It can be placed right or bottom and collapsed or resized. |

All three panels therefore have their visible Compose implementation in `MainActivity.kt`. They are not currently separate Kotlin UI files. `PaneSplitter`, also in that file, is the shared visible resize/collapse control used for Goals and Output; Messages has its own collapse control in its header.

## Editor support files

The editor's visible Compose code is concentrated in `MainActivity.kt`, but several files own important non-visual behavior:

- [`EditorSessionStore.kt`](../../app/src/main/java/org/lean4android/app/EditorSessionStore.kt) defines `EditorTab` and `EditorSessionState`, including active-tab selection, dirty state, add/remove/rename behavior, and the atomic recovery snapshot store.
- [`LeanEditorEngine.kt`](../../app/src/main/java/org/lean4android/app/LeanEditorEngine.kt) provides bounded undo/redo, Unicode-safe Find navigation, UTF-16 offset/position conversion, and the visual transformation for Lean syntax, search matches, and diagnostic underlines.
- [`EditorPaneLayout.kt`](../../app/src/main/java/org/lean4android/app/EditorPaneLayout.kt) defines Goals-pane placement, pane-size clamping, collapsed/IME visibility rules, diagnostic severity coloring, and the compact-landscape project-tree threshold.
- [`EditorLspCoordinator.kt`](../../app/src/main/java/org/lean4android/app/EditorLspCoordinator.kt) coordinates versioned editor documents with the retained Lean language-server session and rejects stale data.
- [`LeanLspService.kt`](../../app/src/main/java/org/lean4android/app/LeanLspService.kt) owns the Android service boundary for the per-project Lean server used by Messages and Goals.
- [`HoverMarkdown.kt`](../../app/src/main/java/org/lean4android/app/HoverMarkdown.kt) parses and renders the safe, bounded Markdown shown in the Goals panel for Hover results.
- [`ProjectTree.kt`](../../app/src/main/java/org/lean4android/app/ProjectTree.kt) builds the compact hierarchical row model rendered by the navigation drawer's project tree.

## Data flow into the panels

`MainActivity` receives versioned diagnostics and cursor inspection results from the retained Lean LSP service and stores the presentation-ready values in its `LspUiState`. `LeanEditorScreen` passes that state into `EditorContent` and `GoalsPanel`:

- `EditorContent` selects diagnostics for the active file. The same diagnostic data produces underlines in the editor and rows in Messages.
- `GoalsPanel` selects tactic goals and expected types for the active file, while Hover, completion, definition/reference feedback, and server status come from the same UI state.
- `OutputPanel` does not use LSP state. It renders `EditorRunState`, which represents one-shot project build/run and runtime-verification work launched through the activity and `ProjectJobService`.

This division is important: Messages and Goals are language-server surfaces that update as the document/cursor changes, while Output is the result surface for explicit build, run, and verification jobs.

## Tests covering these areas

The main supporting unit tests are under [`app/src/test/java/org/lean4android/app`](../../app/src/test/java/org/lean4android/app):

- `LeanEditorEngineTest.kt` and `LeanEditorSupportTest.kt` cover editor transforms, navigation, line numbers, symbols, and related helpers.
- `EditorSessionStoreTest.kt` covers tabs, dirty state, recovery, and file lifecycle state transitions.
- `EditorPaneLayoutTest.kt` covers Goals placement, pane clamping/visibility, diagnostic severity behavior, and compact layout rules.
- `EditorLspCoordinatorTest.kt` covers document synchronization and stale-result rejection.
- `HoverMarkdownTest.kt` covers the safe Markdown model rendered in Goals.

Physical API-33 acceptance for the complete adaptive UI—including portrait/landscape and compact sizes, pane resizing/collapse, docked and floating keyboards, recreation, real diagnostics/goals, and no orphan Lean/Lake processes—is recorded in [`IMPLEMENTATION_HISTORY.md`](../../IMPLEMENTATION_HISTORY.md).
