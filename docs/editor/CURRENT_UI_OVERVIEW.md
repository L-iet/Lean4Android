# Current UI overview

This document is a map of the UI a user sees today and the code a new contributor
should read first. Lean4Android uses a native Jetpack Compose interface; it does
not embed a WebView or VS Code.

Status: Android1 UI complete through M5.3 and physically accepted on the API-33
reference tablet. The foreground lane is M6 hardening. Android2 is frozen at the
shared Auto Goals/portrait IME baseline while its Mathlib lane proceeds.

## 1. Build the UI

Follow [the repository quick start](../../README.md) first. A UI build needs:

- x86-64 Linux or Ubuntu under WSL 2;
- JDK 17, Python 3, Git, `jq`, and Make;
- Android SDK platform 36 and Build Tools 35.0.0; and
- the audited Android1 distribution at
  `toolchain/output/lean-4.32.1-android1`.

The NDK/native cross-build is unnecessary for Kotlin/Compose-only changes when
that audited distribution already exists. Check and build with:

```shell
make doctor
make ui
```

`make ui` runs app unit tests and Kotlin compilation through the durable Gradle
runner. Build the installable UI-bearing APK with:

```shell
make apk
```

The APK appears at `app/build/outputs/apk/debug/app-debug.apk`. A warm focused UI
validation usually takes about 2–5 minutes in the recorded environment; cold
toolchain staging on `/mnt/d` can take much longer. See
[MVP_AND_REBUILDING.md](../../MVP_AND_REBUILDING.md) for setup, timing, and recovery.

## 2. Visible application structure

The main screen is built by `LeanEditorScreen` in
[`MainActivity.kt`](../../app/src/main/java/org/lean4android/app/MainActivity.kt).

```text
Top app bar
├── navigation drawer
├── active file/project identity
├── Run
├── Files menu
└── More menu (output and LSP actions)

Adaptive workspace
├── browser-style file tabs
├── optional Find row
├── line-number gutter + source editor
├── optional caret-adjacent completion popup
├── configurable Lean/Unicode symbol row
├── Messages (diagnostics)
├── Output (docked pane or in-app popup)
└── Goals / expected type / hover
    ├── right side in landscape under Auto
    └── bottom in portrait under Auto
```

Goals, Output, and Messages have independent collapse behavior. Goals and docked
Output have accessible draggable/keyboard splitters and persisted fractions. A
docked IME temporarily suppresses Output without changing its saved state. Auto
Goals placement uses stable configuration orientation, not IME-reduced constraints,
so opening Samsung's keyboard does not rebuild the editor and lose focus.

## 3. Menus, drawer, and settings

The drawer contains:

- a contained hierarchical project tree with folder/file actions;
- Build project and runtime verification actions;
- project import/export, rename, and deletion;
- Open workspace / Recent / My Projects; and
- Settings pages.

Files covers new/open/save/save-as/close and project creation. Long-press menus on
projects, tabs, and tree entries provide guarded rename/delete actions. Unsupported
or dirty destructive transitions require explicit choices.

Current settings include:

- light/dark appearance;
- Goals position: Auto, Right side, or Bottom;
- Docked or Popup Output;
- automatic LSP completion;
- symbol-row visibility, ordered contents, and restore defaults;
- editor font sizes 12/14/16/18/20/22 sp;
- interface scale 85/100/115/130 percent; and
- About with copyable app/package/build/toolchain/schema identity and real
  shell-free Lean/Lake version probes.

## 4. Editor behavior

Each recovered/open file has a visible tab. Dirty buffers are retained separately
and recover atomically across process death. Lean files receive syntax decoration,
diagnostic underlines, LSP synchronization, completion, goals, hover, definition,
and references. Supported ordinary text files use the plain-text fallback and are
not sent to Lean Server. Invalid UTF-8/binary content is handled explicitly rather
than decoded blindly.

The native selection toolbar preserves Android editing actions and adds one More
entry that opens accessible Hover, Definition, and References actions. Definition
opens contained targets at the returned UTF-16 position. References appear in a
bounded sorted/deduplicated chooser. External/toolchain targets remain visible but
non-navigable.

## 5. Output and program input

Output is distinct from live language-server state. Build/Run always reveals the
selected Docked/Popup presentation and keeps bounded chronological display text.
Terminal results retain independent raw stdout and stderr revisions for separate
SAF export.

Run offers three explicit stdin modes:

- immediate EOF;
- interactive Send line / EOF / Cancel; or
- exact bytes from a validated saved project file, with dirty Save and Run / Run
  saved version choices.

`ProjectJobService` retains the sequence and input channel across Activity
recreation. Prompt text is ordinary output; the UI never claims automatic prompt
detection. Details are in [STDIN_SUPPORT.md](../project/STDIN_SUPPORT.md).

## 6. Code ownership map

| UI area | Primary code | Responsibility |
| --- | --- | --- |
| App shell, drawer, screens, dialogs | [`MainActivity.kt`](../../app/src/main/java/org/lean4android/app/MainActivity.kt) | Compose structure, transient UI state, callbacks |
| Theme and semantic styles | [`LeanTheme.kt`](../../app/src/main/java/org/lean4android/app/ui/theme/LeanTheme.kt) | Material root, dimensions, component styles, font scaling |
| Tabs and dirty recovery | [`EditorSessionStore.kt`](../../app/src/main/java/org/lean4android/app/EditorSessionStore.kt) | tab identity/state and atomic snapshots |
| Editor transforms | [`LeanEditorEngine.kt`](../../app/src/main/java/org/lean4android/app/LeanEditorEngine.kt) | undo/redo, search, UTF-16 mapping, decorations |
| Pane policy | [`EditorPaneLayout.kt`](../../app/src/main/java/org/lean4android/app/EditorPaneLayout.kt) | orientation, IME visibility, clamping, severity |
| Completion | [`LspCompletion.kt`](../../app/src/main/java/org/lean4android/app/LspCompletion.kt) | bounded/stale-safe completion presentation and edits |
| Hover Markdown | [`HoverMarkdown.kt`](../../app/src/main/java/org/lean4android/app/HoverMarkdown.kt) | safe bounded native Markdown rendering |
| Output mode | [`OutputPresentation.kt`](../../app/src/main/java/org/lean4android/app/OutputPresentation.kt) | Docked/Popup state policy |
| Project tree | [`ProjectTree.kt`](../../app/src/main/java/org/lean4android/app/ProjectTree.kt) | compact hierarchical row model |
| Editor/LSP bridge | [`EditorLspCoordinator.kt`](../../app/src/main/java/org/lean4android/app/EditorLspCoordinator.kt) | versioned sync and stale-result rejection |
| Retained LSP | [`LeanLspService.kt`](../../app/src/main/java/org/lean4android/app/LeanLspService.kt) | per-project server ownership/reconnection |
| Retained Build/Run | [`ProjectJobService.kt`](../../app/src/main/java/org/lean4android/app/ProjectJobService.kt) | sequence, streams, cancellation, snapshots |

Most composables still live in `MainActivity.kt`; semantic visual decisions have
moved to `LeanTheme.kt`. Incremental component extraction is allowed, but state,
adaptive policy, accessibility, and process ownership must not be hidden in style
objects. See [UI_STYLE_ARCHITECTURE.md](UI_STYLE_ARCHITECTURE.md).

## 7. Data flow by surface

- **Messages** consumes current-version diagnostics for the active Lean file; the
  same data produces editor underlines.
- **Goals** consumes current cursor goals/expected type and safe Hover content,
  plus Lean Server status/navigation feedback.
- **Completion** consumes bounded current-position candidates and applies one
  normal editor-history edit.
- **Output** consumes `EditorRunState` snapshots from `ProjectJobService`, not LSP.
- **Project tree/tabs** consume repository and session models; they never traverse
  arbitrary filesystem/provider paths directly.

The complete cross-module picture is in [ARCHITECTURE.md](../../ARCHITECTURE.md).

## 8. Tests and acceptance

The primary unit-test directory is
[`app/src/test/java/org/lean4android/app`](../../app/src/test/java/org/lean4android/app).
Notable suites cover editor transforms, session recovery, pane layout, LSP
coordination, completion, output presentation, project trees, Hover Markdown, and
theme invariants.

After a UI behavior change:

```shell
scripts/run-ui-gradle.sh :app:testDebugUnitTest :app:compileDebugKotlin
```

Before device handoff:

```shell
make apk
git diff --check
```

Changes involving layout, IME, accessibility, Activity/service recreation,
storage, or native processes require proportional emulator/physical-device
validation. Preserve the continuous offline editor, project, LSP, build/run, and
no-orphan baselines documented in
[IMPLEMENTATION_HISTORY.md](../../IMPLEMENTATION_HISTORY.md).
