# Lean4Android architecture

This document explains how Lean4Android is divided, how data and processes move
through it, and where a new contributor should make changes. For build mechanics,
start with [README.md](README.md) and [MVP_AND_REBUILDING.md](MVP_AND_REBUILDING.md).
For chronological evidence rather than design intent, use
[IMPLEMENTATION_HISTORY.md](IMPLEMENTATION_HISTORY.md).

## 1. System boundary

Lean4Android is an Android application, not a Linux distribution or terminal. It
supports one pinned Lean toolchain, AArch64 devices, API 29+, offline core/Std, and
app-contained projects. Native executable code is installed by Android as APK
native libraries; writable project and sysroot data stay in app-private storage.
Arbitrary downloaded executables, Elan, unrestricted Git/network dependencies,
native Lake targets, and shell command construction are outside the first-release
boundary.

The accepted production lane is Android1:

```text
application ID: org.lean4android.app
toolchain ID:   lean-4.32.1-android1
```

Android2 is an isolated candidate package/toolchain used for the resumable Mathlib
lane. It is not a second normal build flavor and must not receive routine Android1
changes. The process/runtime decision is recorded in
[ADR 0001](docs/adr/0001-android-lean-process-and-runtime-boundary.md).

## 2. Top-level component map

```text
Jetpack Compose UI (app)
  ├── editor/session/recovery state
  ├── project and file workflows ────────────────┐
  ├── retained Lean LSP service                  │
  └── retained build/run service                 │
                                                  v
core-project ── contained files, Lake policy, import/export
     │
     ├──────────────> core-toolchain ── install/layout/commands
     │                         │
     v                         v
core-process <──────── typed shell-free process launch/supervision
     ^
     │
core-lsp ── JSON-RPC framing/session/document/request policy

core-model ── shared immutable command/toolchain models

APK packaging
  ├── native/arm64-v8a: Lean/Lake executable ELFs and shared libraries
  └── assets/toolchain: filtered immutable core/Std sysroot payload
```

Dependency direction is intentionally toward small typed boundaries. UI code asks
repositories and services to perform work; it does not reconstruct executable
paths, process environments, or containment rules.

## 3. Gradle modules

### `app`

The Android application and Compose UI. Important classes under
`app/src/main/java/org/lean4android/app/` include:

- `MainActivity.kt`: application shell, adaptive editor workspace, menus, dialogs,
  settings, and wiring to project/process/LSP callbacks.
- `EditorSessionStore.kt`: tabs, active file, dirty buffers, and atomic recovery.
- `LeanEditorEngine.kt`: undo/redo, Unicode-safe search, UTF-16 conversion, syntax,
  search, and diagnostic decoration.
- `EditorPaneLayout.kt`: pure Goals/Output/Messages placement and clamping policy.
- `EditorLspCoordinator.kt`: versioned editor-to-LSP synchronization and staleness.
- `LeanLspService.kt`: retained per-project Lean Server ownership.
- `ProjectJobService.kt`: retained build/run sequence and stdin/output ownership.
- `LspCompletion.kt`, `HoverMarkdown.kt`, `OutputPresentation.kt`, and
  `ProjectTree.kt`: bounded presentation models and policies.

The services are non-exported in `AndroidManifest.xml`. They keep native work alive
across Activity recreation while ensuring explicit cancellation and destruction
close owned children.

### `core-model`

Small shared models, especially immutable toolchain identity, executable, command,
and environment descriptions. This module prevents UI code from passing ambiguous
strings where a typed boundary is required.

### `core-process`

Shell-free process launching and supervision. Production launches use argument
lists and deterministic environments. `ProcessJobSupervisor` owns stdout/stderr
drains, bounded byte-faithful capture, stdin modes, cancellation, terminal state,
and cleanup. It is the boundary that prevents command injection and orphaned
children.

### `core-lsp`

Dependency-light Lean Server protocol implementation: `Content-Length` framing,
JSON parsing/encoding, envelope dispatch, initialize/shutdown lifecycle, document
versions, diagnostics, goals, hover, completion, definition, references, and one
reader/supervisor ownership. Android service lifecycle stays in `app`; protocol and
transport correctness stay here.

### `core-project`

App-private workspace repository and supported Lake-project policy. It provides
contained/no-follow path resolution, atomic saves, file/project lifecycle,
deterministic generated Lake configuration, bounded ZIP/SAF import/export,
text/binary classification, saved input revisions, and traversal/symlink defense.
Lean and Lake never run directly against provider URIs.

### `core-toolchain`

Runtime installation and discovery. It validates manifests/hashes, performs staged
activation/rollback, refreshes compatibility links after randomized APK native
paths change, constructs typed Lean/Lake commands and environments, checks storage,
and exposes health/probe state. Cleanup deliberately walks without following links.

### `core_toolchain_pack`

The install-time Play Asset Delivery prototype for the data-only runtime pack. It
is not the default debug-APK path. Delivery decisions and measurements are in
[docs/delivery/M1_6_DECISION.md](docs/delivery/M1_6_DECISION.md).

## 4. Native toolchain and packaging

Lean is bootstrapped: a host Lean stage generates target C/artifacts, and the
pinned Android NDK compiles/links them for Bionic/AArch64.

```text
pinned Lean/LibUV/OpenSSL sources
       │
       ├── host Lean stage0 (runs on build host)
       └── Android LibUV/OpenSSL static dependencies
                         │
                         v
              Android Lean/Lake target build
                         │
                         v
              audited Android1 distribution
                         │
                         v
              Gradle native + asset staging
                         │
                         v
                   debug APK / AAB
```

The canonical upstream Lean delta is the single patch under
`toolchain/patches/`. The generated Lean checkout must match it byte-for-byte.
`toolchain/scripts/assemble-distribution.sh` audits AArch64 ELF identity and dynamic
dependencies, builds the immutable distribution, and writes `manifest.json`.
Gradle filters build-only sysroot files while retaining every empirically required
`.olean`, `.olean.server`, `.olean.private`, `.ir`, and `.ilean` facet.

Detailed build and incremental rebuild rules are in
[MVP_AND_REBUILDING.md](MVP_AND_REBUILDING.md) and
[toolchain/README.md](toolchain/README.md).

## 5. Runtime filesystem layout

Android API 29+ prohibits executing code copied into the writable app home. The
runtime therefore separates code from data:

```text
ApplicationInfo.nativeLibraryDir/       read-only, Android-installed
  liblean_exe.so
  liblake_exe.so
  Lean/Lake shared libraries

noBackupFilesDir/toolchains/<id>/       writable, installer-owned
  bin/lean -> current APK native path
  .lake/build/bin/lake -> current APK native path
  lib/lean/ and src/lean/               verified sysroot data
  install/manifest markers

filesDir/projects/<project-id>/         writable, repository-owned
  .lean4android-project
  lean-toolchain
  lakefile.toml
  user project files
```

APK updates can randomize `nativeLibraryDir`. The locator resolves the live path
and refreshes writable compatibility links; the randomized path is never persisted
as immutable toolchain identity.

## 6. Project and editor data flow

1. `LeanProjectRepository` opens or creates a validated app-private project.
2. `EditorSessionStore` restores bounded tabs and dirty buffers independently of
   disk state.
3. Saves are atomic and monotonically update the buffer's saved revision.
4. Supported source lifecycle changes reconcile generated Lake module roots.
5. Only Lean text documents synchronize to the retained LSP service; general text
   remains editable through the plain-text fallback.
6. Diagnostics and cursor results carry project, generation, path, and document
   version. Stale or external results cannot navigate or overwrite current state.
7. Definition/reference URIs pass through contained project resolution before the
   UI may open a target.

The current UI is described in
[docs/editor/CURRENT_UI_OVERVIEW.md](docs/editor/CURRENT_UI_OVERVIEW.md).

## 7. LSP lifecycle

`LeanLspService` owns one retained `lake serve` session per active project. Below
it, `LeanLspSupervisor` has single-reader ownership of stdout framing and coordinates
request IDs, responses, notifications, lifecycle ordering, and failure. The editor
coordinator sends ordered `didOpen`/`didChange`/`didClose` updates and rejects stale
responses.

Activity recreation reconnects to generation-tagged snapshots rather than starting
a second server. Project switches and service destruction shut down/close children
in deterministic order. Android2 Mathlib evidence shows why aggregate child count
matters: restored heavy documents can each create a 1–2 GB worker. The active
memory-hardening boundary is documented in
[docs/delivery/ANDROID2_MATHLIB_EDITOR_MEMORY_FAILURE.md](docs/delivery/ANDROID2_MATHLIB_EDITOR_MEMORY_FAILURE.md).

## 8. Build/run lifecycle and streams

Explicit project jobs are separate from live LSP work:

```text
UI Run/Build request
  -> ProjectJobService retained sequence
  -> supported Lake build
  -> optional lean --run Main.lean
  -> bounded combined display + independent raw stdout/stderr captures
```

Run stdin is explicit: immediate EOF, interactive Send/EOF, or exact bytes from a
validated saved project-file revision. A prompt is ordinary output; the app does
not pretend to detect a structured input request. The service owns sources and
writers across recreation, closes never-consumed sources on failure, and cancels
children if streaming fails. See
[docs/project/STDIN_SUPPORT.md](docs/project/STDIN_SUPPORT.md).

## 9. UI and design-system architecture

The UI is Compose-native and adaptive. The top-level workspace contains tabs,
editor, symbol row, Messages, Output, and Goals. Output may be docked or an in-app
popup; completion is caret-adjacent; settings control appearance, pane placement,
symbols, editor size, interface scale, and runtime identity.

Behavioral policy remains in screen/state/helper code. Theme, semantic color and
typography, dimensions, shapes, and meaningful component styles belong to the
typed Compose design-system layer described in
[docs/editor/UI_STYLE_ARCHITECTURE.md](docs/editor/UI_STYLE_ARCHITECTURE.md).

For UI-only work with an existing audited distribution:

```shell
make ui
```

For the full Android1 UI-bearing APK:

```shell
make apk
```

## 10. Security and trust boundaries

- Projects and ZIP entries are contained, bounded, and link-safe.
- Installer activation is manifest/hash driven and rollback aware.
- Production process launches never use a shell.
- Only APK-installed native executable code runs.
- LSP navigation cannot open external/toolchain paths as project files.
- SAF is an import/export boundary, not a runtime filesystem.
- Network/Git/native/custom Lake workflows remain unsupported.
- Services are non-exported; app-private data is not a public content surface.

Threat-model and release-hardening work remains in M6; consult
[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) rather than silently broadening
these boundaries.

## 11. Testing strategy

Tests are layered:

- core module unit tests for containment, process, toolchain, protocol, and models;
- app unit tests for editor, session, layout, completion, output, and service policy;
- APK assembly/ZIP/manifest/ELF audits;
- instrumentation for Android lifecycle/storage boundaries; and
- physical-device acceptance for install/update/migration, UI/IME, offline Lean and
  Lake, LSP, cancellation, process death, and no-orphan checks.

Compilation alone is not release evidence when a change crosses Android storage,
process, installer, UI, or device boundaries. The validation matrix and continuous
baselines are in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

## 12. Where to make a change

| Change | Primary location | Minimum first check |
| --- | --- | --- |
| Compose screen/component | `app/.../MainActivity.kt` and UI support files | `make ui` |
| Theme/style token | `app/.../ui/theme/` and styled component | focused app tests + `make ui` |
| Editor transforms/history | `LeanEditorEngine.kt` | focused app unit test |
| LSP protocol/session | `core-lsp` | `:core-lsp:testDebugUnitTest` |
| Process/stream behavior | `core-process` | `:core-process:testDebugUnitTest` |
| Project containment/storage | `core-project` | `:core-project:testDebugUnitTest` |
| Installer/runtime layout | `core-toolchain` | focused tests, APK, fresh/update device tests |
| Upstream Lean/native build | canonical patch + `toolchain/scripts` | incremental target build and full audit |

Use `scripts/run-ui-gradle.sh <tasks...>` for focused Gradle tasks so no-cache,
no-daemon, plain-console, durable-log, and heartbeat policy stays consistent.
