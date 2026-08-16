# Lean4Android implementation plan

Status: active roadmap; M0 complete, M1 reference-device conformance substantially complete; M1.5 runtime-layout hardening in progress
Last revised: 2026-08-13

## 1. Goal and first release boundary

Build an offline-capable Android IDE that can edit and check Lean 4 projects on-device. The first useful release should let a user:

1. create or open a small Lean project;
2. edit multiple `.lean` files;
3. see live diagnostics and goals;
4. explicitly check a file and build a project;
5. use a bundled, version-pinned Lean standard library without Termux or another installed app; and
6. import/export the project through Android's Storage Access Framework.

The first release is **not** a general Unix environment. It will not initially support arbitrary Lean versions, Git dependencies, a shell, compiling Lean executables to Android machine code, or the full VS Code Lean widget ecosystem. Mathlib support follows after the core toolchain is proven usable within Android's storage, process, memory, and package-size constraints.

## 2. Decisions to validate before building the full app

The broad architecture in `initial_plan.md` is sound, but the following feasibility spike is the real first milestone.

### 2.1 Executable placement

Do not copy native executables to `filesDir` and run them. Apps targeting Android 10/API 29 or later cannot `execve()` files from their writable app home because of Android's W^X policy. Package each executable as an ABI-specific native artifact in the APK/AAB and execute it from the installed, read-only native library directory (`ApplicationInfo.nativeLibraryDir`). If Android packaging requires shared-library naming, package command executables with `.so` filenames and preserve their executable ELF entry point.

Writable data remains elsewhere:

```text
nativeLibraryDir/                 read-only, installed by Android
  liblean_exe.so
  liblake_exe.so
  required runtime .so files

noBackupFilesDir/toolchains/<id>/ writable data, restored from signed assets
  bin/lean                        refreshed symlink to APK-installed executable
  .lake/build/bin/lake            refreshed symlink to APK-installed executable
  .lake/build/lib/lean            compatibility link to the Lean library
  lib/lean/                       .olean plus server/private/IR facets and .ilean
  src/lean/                       sources needed by server/navigation
  include/, share/, licenses

filesDir/projects/<project-id>/   writable project workspaces
  lakefile.toml
  lean-toolchain
  lake-manifest.json
  Main.lean
  .lake/

cacheDir/                         temporary extraction/log files
```

Device testing has proven that packaged executables can run, but Lean/Lake assume a conventional colocated desktop distribution. The supported adapter is:

- resolve executable targets from `ApplicationInfo.nativeLibraryDir` on every app version;
- recreate writable symlinks after sysroot activation and after every APK update;
- make Lean's internal path discovery honor `LEAN_SYSROOT`;
- launch Lake with `LAKE_HOME`, `LAKE_OVERRIDE_LEAN=true`, and `LEAN_SYSROOT`; and
- never persist the randomized APK/native-library path or copy executable bytes to writable storage.

The installer is not healthy until both its data marker and executable-layout links validate against the current APK installation.

### 2.2 Android cross-build

Lean is bootstrapped, so this is not just a normal one-pass CMake cross-compile. Use a pinned Lean release and NDK version and split the build into:

1. a host build that runs the bootstrap compiler and generates C;
2. an Android `arm64-v8a` target build using the NDK Clang toolchain and Bionic;
3. assembly of a relocatable Android sysroot containing runtime libraries, all required split Lean module artifacts (`.olean`, `.olean.server`, `.olean.private`, `.ir`, and `.ilean`), sources needed by the server, headers/share data, and licenses; and
4. device tests that catch accidental glibc, host-architecture, absolute-path, and unavailable-system-command dependencies.

Keep all Lean/NDK patches in a small, reviewable patch series. The intended build should run in CI from clean checkouts and emit a versioned manifest with upstream commit, NDK version, ABI, minimum API, hashes, licenses, and file list.

The current patch set also needs to preserve these validated Android adaptations:

- Android receives Lean's ELF/PIC/dynamic-loader CMake behavior;
- generated C uses the configured NDK AArch64 API driver;
- libc++/libc++abi are statically linked without `libc++_shared.so`;
- Lean disables Bionic heap pointer tagging before runtime initialization; and
- internal Lean sysroot discovery gives `LEAN_SYSROOT` precedence over executable-relative paths.

The source checkout must exactly match the canonical checked-in patch. Scheduled clean builds must prove that incremental-build success does not conceal undeclared inputs.

### 2.3 Runtime scope

Prove these independently, in this order:

- `lean --version` starts on a physical arm64 device.
- `lean Main.lean` accepts a theorem and reports an invalid theorem.
- `lean --server` completes LSP initialization and publishes diagnostics.
- Lake can read a local project and run `lake lean Main.lean` and `lake build` without network access.
- cancellation terminates the server and all child processes.
- the same operations work after a cold restart and with paths containing spaces and non-ASCII characters.
- app update refreshes all executable-layout symlinks after Android changes the randomized native-library path;
- first install, interrupted install, and corrupted split module artifacts fail atomically and readably; insufficient storage is rejected up front with a readable required/available-space message; and
- the process environment works without a shell, inherited host variables, or network access.

`lake build` may invoke a C compiler for executable or native targets. Treat proof checking and Lean library builds as the MVP. Shipping an NDK-based `leanc` workflow is a separate capability and must not block editor/LSP delivery.

### 2.4 Feasibility exit criteria

Continue to the product build only when a test APK passes the above checks on the oldest supported API and a current Android release, remains functional offline, and records peak RSS, cold-start time, first-diagnostic latency, APK/download size, installed native size, writable-sysroot size, first-install time, and update time. Initial targets are API 29+, arm64 only, under 3 seconds for first diagnostics on a small Std-only file on the reference device, and no orphaned Lean processes after cancellation. Performance numbers are budgets to measure and revise, not promises.

The current full Lean 4.32 runtime sysroot is approximately 2,175,501 KiB on-device because ordinary imports require server/private/IR facets. This makes distribution feasibility a separate exit gate: select and validate an asset-delivery strategy before treating a monolithic debug APK as the release architecture.

If child execution from the installed native directory proves unreliable across supported devices, stop and write an architecture decision record comparing: (a) a small native launcher, and (b) embedding Lean behind a narrow JNI boundary. Do not build the IDE UI around an unproven process model.

## 3. Proposed architecture

Use a single Android app with clear module boundaries:

```text
Compose UI / editor
        |
EditorSession + ProjectViewModel
        |
LeanService interface
   +----+------------------+
   |                       |
LspClient              CommandRunner
long-lived             bounded one-shot jobs
lake serve             lake lean / lake build
   |                       |
ProcessSupervisor + ToolchainEnvironment
        |
packaged arm64 Lean/Lake + writable sysroot/projects
```

Recommended Gradle modules:

```text
app/                    activities, navigation, dependency wiring
core-model/             project, document, diagnostic, goal models
core-projects/          safe filesystem operations, import/export
core-toolchain/         installation, manifest verification, environment
core-process/           launch, streams, cancellation, crash logs
core-lsp/               JSON-RPC framing, LSP lifecycle, Lean extensions
feature-editor/         editor, tabs, diagnostics, goal/hover UI
feature-projects/       project list/tree/create/import/export
feature-settings/       toolchain/library/storage settings
toolchain/              reproducible build scripts, patches, manifests
```

Use Kotlin, coroutines/Flow, Jetpack Compose, Room only for app metadata, and ordinary files as the source of truth for projects. Put process ownership in a bound foreground service when background execution is required; the UI must be able to reconnect after activity recreation.

### 3.1 Editor choice

The M3 bake-off selected a native Jetpack Compose editor. The production UI uses Compose UI, Foundation, and Material 3; the editor is built from `BasicTextField`, annotated text, and project-owned state and behavior. Do not introduce a WebView, browser-based editor, JavaScript bridge, or web asset/runtime stack for ordinary editor or application UI work.

Keep the UI dependency set deliberately small. Prefer existing Compose and Android platform primitives, plus focused project-owned code, when the behavior is reasonably bounded. Add another UI framework or library only when it solves a specific demonstrated problem substantially better and implementing the equivalent correctly in this repository would require a large, unnecessary body of code. Document the problem, expected maintenance/size/security cost, considered native approach, and validation boundary before adoption. WebView or other web-related dependencies require especially strong justification because they add a second UI/runtime model and expand packaging, accessibility, security, offline, and lifecycle obligations.

This policy does not require reimplementing a mature specialist component merely to avoid a dependency. A narrowly scoped library is acceptable when its benefit clearly outweighs its footprint and integration cost, it works fully offline, and it preserves typed Kotlin ownership of files, processes, toolchains, and LSP state.

### 3.2 Process supervision

Implement one component as the only way to start native tools. It must:

- use absolute executable and working-directory paths;
- construct a minimal deterministic environment (`HOME`, app-writable `TMPDIR`, `LEAN_SYSROOT`, `LEAN_PATH`, `PATH`, and the native library search path; plus `LAKE_HOME` and `LAKE_OVERRIDE_LEAN=true` for Lake);
- provide an app-owned TZif fallback and set `TZ` explicitly because Android lacks `/etc/localtime`, which Lean Server requires after `initialized`;
- derive all executable targets from current Android application metadata and validate/refresh sysroot compatibility links before launch;
- stream stdout/stderr concurrently to avoid deadlock;
- support timeouts, explicit cancellation, process-tree cleanup, and one LSP server per open project;
- serialize mutating Lake operations per project while allowing safe reads;
- cap retained logs and report exit code/signal separately from Lean diagnostics; and
- redact app-private absolute paths from exported logs.

No command is assembled through a shell. Arguments are always a list, and user text is never interpreted as a command.

Add a typed `ToolchainEnvironment`/`ToolchainCommandFactory` rather than constructing environment maps in UI code. It must distinguish direct Lean, Lake, and LSP commands, include capability flags for unsupported native-build tools, and expose redacted diagnostics for layout failures.

### 3.3 LSP client

Use `lake serve` for workspace-aware language-server startup rather than calling an assumed command indirectly. Implement byte-accurate `Content-Length` JSON-RPC framing over stdin/stdout; process logs belong on stderr and must never enter the protocol parser.

Implement the smallest sequence first:

1. `initialize` / `initialized` / `shutdown` / `exit`;
2. `textDocument/didOpen`, incremental `didChange`, `didSave`, `didClose`;
3. `publishDiagnostics` with document-version filtering;
4. hover, completion, definition, and references;
5. Lean goal/RPC requests and code actions after inspecting the protocol for the pinned Lean version.

Debounce edits, but preserve their order. Each editor buffer has a monotonically increasing version; discard stale diagnostics and responses. Restart a crashed server with bounded backoff and reopen current documents. Add protocol transcript capture behind a developer setting, with source text disabled by default.

### 3.4 Projects and storage

Keep active projects in internal app storage so Lake receives real stable paths and normal filesystem semantics. The Storage Access Framework is an import/export boundary, not the live build workspace.

- Give every project an internal ID separate from its display name.
- Validate relative paths and reject traversal, absolute paths, symlink escapes, and duplicate case-folded names.
- Write files atomically using temp-file-plus-rename and preserve unsaved editor state through process death.
- Confirm destructive actions and use an internal trash/undo window where practical.
- Export a documented ZIP format; safely extract imports with file-count, uncompressed-size, path, and compression-ratio limits.
- Generate projects from app-owned templates rather than invoking arbitrary shell commands.

For v1, the bundled toolchain is authoritative. A project's `lean-toolchain` is displayed and validated; incompatible versions produce a clear error rather than causing Elan or Lake to download and execute a different toolchain.

User-facing project configuration follows `docs/project/PROJECT_CONFIGURATION_DESIGN.md`: start with typed, atomically generated General/Source layout/Build/safe Lean-option settings that remain consistent with M4 LSP configuration; add signed offline dependency-pack selection with M5. Unrestricted `lakefile.toml`, executable `lakefile.lean`, arbitrary toolchains, Git/network resolution, and executable/native/custom targets remain unsupported until separately designed and threat-modeled.

## 4. Toolchains, dependencies, and Mathlib

### 4.1 Version model

Ship exactly one supported Lean version initially. Give each toolchain an immutable ID such as `lean-4.x.y-android1`; verify its manifest and hashes before activation. App upgrades install a new ID side-by-side, migrate only after a health check, and retain the previous version until migration succeeds.

Do not ship Elan in v1. Automatic arbitrary toolchain installation conflicts with Android's executable-code restrictions and makes reproducibility and support much harder.

An immutable toolchain ID identifies data format and Lean revision, but its executable symlinks are mutable installation metadata because APK paths change. Activation therefore consists of immutable verified data plus an idempotent link-refresh step. The installation marker must include enough schema/version information to rerun layout migration safely.

### 4.2 Core toolchain delivery and footprint

Do not assume the core/Std data fits comfortably in the base APK. Measure three representations independently: source distribution, filtered uncompressed runtime, and compressed delivery artifact. The current required runtime includes large `.olean.private` and `.ir` sets and is over 2 GB installed.

Evaluate, in order:

1. Android App Bundle/on-demand asset delivery for Play-compatible builds;
2. a separately downloaded or user-imported signed core data pack for independent/F-Droid builds;
3. compression/container formats that support streaming installation and per-file hash verification without peak disk usage near twice the installed size; and
4. a deliberately rebuilt flattened/minimal Lean distribution only if it preserves checking, interpretation, LSP, navigation, and Lake semantics.

The base APK must continue to contain executable native code. Downloaded packs contain data only. Installation must preflight free space, stream into staging, validate every manifest entry, atomically activate, and clean recoverably after interruption.

### 4.3 Offline dependency policy

The initial product supports:

- the bundled Lean core/Std libraries;
- app-produced, version-matched library packs containing source plus prebuilt Lean artifacts; and
- local project dependencies already contained in an imported project, when they require no external tools.

Network-based `lake update`, arbitrary Git dependencies, post-install native plugins, and packages with external build scripts are explicitly unsupported at first. Lake can depend on tools such as Git, tar, and curl for those workflows, and arbitrary packages may execute build logic. Add them only with a security model and explicit UI.

### 4.4 Mathlib pack

Build Mathlib in CI against the exact Android Lean toolchain. A pack contains a signed manifest, licenses, source files needed for navigation, and every split module facet empirically required by checking/server workflows—not merely `.olean`/`.ilean`. Test `import Mathlib`, goals, hover, definition navigation, interpretation where supported, and a representative tactic suite on a physical device.

Distribute Mathlib separately because of size. Support two channels behind one installer abstraction:

- Play builds: on-demand asset delivery, subject to confirming that the chosen asset-delivery program is permitted for this app category;
- independent/F-Droid builds: user-imported signed pack or a download from the project's release server.

Install into a staging directory, verify signature/hash/version/free-space, then atomically activate it. Never treat downloaded or imported native code as executable.

## 5. Delivery milestones

### M0 — Repository and reproducibility (1 week)

- Create the Gradle multi-module skeleton and a minimal app.
- Add pinned-version files, toolchain manifest schema, CI jobs, and architecture decision records.
- Define reference devices and collect empty-app size/startup baselines.

Exit: clean CI builds an APK and records reproducible inputs.

### M1 — Android Lean runtime feasibility (complete on the API-33 reference device)

- Produce the arm64 Android Lean runtime/toolchain.
- Package executable code in the APK-native location and data in a versioned sysroot.
- Maintain the canonical Android patch and complete clean-build reproducibility.
- Pass version and valid/invalid file checks through the production process boundary.
- Provide a minimal single-file Compose editor probe that saves app-private source and displays direct Lean output for visual testing.
- Preserve the passing API-33 checks for Lake child builds, LSP lifecycle initialization, process termination, cold restart, and Unicode/space paths while adding document diagnostics, offline verification, RSS/timing data, and API-level coverage.
- Publish native, sysroot, memory, startup, and latency measurements.

Exit: a fresh offline install checks valid/invalid Lean files, builds a local Lean library with Lake, completes an LSP handshake, and leaves no orphan processes. The child-process architecture is retained unless the remaining matrix exposes an execution defect.

### M1.5 — Runtime layout and upgrade hardening

- [x] Move all environment construction into a typed command factory.
- [x] Create/refresh Lean/Lake compatibility symlinks and reject absent/stale links in health checks.
- [x] Detect and repair APK/native-directory changes independently of immutable sysroot data installation; validated by update-install on the API-33 reference tablet.
- [x] Add schema-2 marker migration bound to the complete packaged manifest digest; stream-verify every installed runtime file once during install/migration, retain fast routine health checks, and fall through to staged repair when legacy/schema-1 verification fails.
- [x] Add deterministic host coverage for interrupted activation and rollback recovery, including a failed rollback rename.
- [x] Validate a physical complete-facet corruption, staged replacement, cold APK update/link refresh, post-repair full audit, and Lean/LSP operation on the API-33 reference tablet.
- [x] Validate physical process interruption with a complete-but-unmarked staging tree, restart cleanup, restaging, schema-2 activation, and Lean execution.
- [x] Keep non-destructive free-space preflight and readable storage errors; defer manufactured low-storage pressure beyond the MVP.
- [x] Record an ADR for the child-process decision, Bionic heap-tagging compromise, upstream patch maintenance, and supported capability boundary.

Exit: install, restart, APK replacement, and interrupted migration tests all recover automatically; no UI code constructs native command paths or environments.

### M1.6 — Core toolchain delivery spike

- [x] Record the monolithic debug-APK baseline, separating native code and runtime data and breaking compressed/uncompressed runtime size down by artifact facet.
- [x] Measure the monolithic APK, Play asset-pack AAB, independent pack, and installed footprint with all required split module facets.
- [x] Prototype install-time Play Asset Delivery and an independent RSA-signed data-pack source behind one streaming installer interface.
- [x] Stream-install and hash-verify the complete 14,864-file signed pack; quantify staging/rollback peak temporary disk requirements and timing boundaries.
- [x] Defer a smaller Lean artifact build: both delivery channels are viable without weakening the conformance-proven split-facet runtime.
- [x] Keep executable/native entries in base, put runtime data plus its manifest in one install-time asset pack or signed ZIP, and document current limits and release-time revalidation.

Exit: at least one viable Play path and one viable independent-distribution path install the full core toolchain within documented storage/time budgets. If neither is viable, revisit the supported Lean artifact model before product UI work.

### M2 — Project runner and durable process service

- [x] Add a two-module template and strict schema/toolchain compatibility checks on top of the hardened installer.
- [x] Implement create/list/open/delete, monotonic atomic save, `lake lean`, and supported `lake build`.
- [x] Add bounded structured job output, cancellation/cleanup states, errors, and traversal-safe atomic ZIP import/export.
- [x] Put LSP and project job ownership in reconnectable, non-exported bound services; defer foreground promotion until background jobs become user-visible.
- [x] Reject Git/network dependencies, executable/native targets, and shell download workflows before Lake attempts them.
- [x] Add a simple two-tab Compose editor that saves both modules and builds the project.
- [x] Pass the offline API-33 instrumentation exit scenario against the final migration-fix APK (6.791s) and verify no residual Lean/Lake process.

Exit: an instrumentation test creates a two-module project, catches an error, fixes it, builds it, exports it, deletes it, and reimports it offline.

### M3 — Usable editor (3–5 weeks)

- [x] Complete the editor bake-off and record the native Compose choice in `docs/editor/M3_EDITOR_DECISION.md`.
- [x] Add file tree, tabs, dirty state, search, syntax highlighting, undo/redo, rename/delete, and recovery after activity/process recreation.
- [x] Validate the adaptive phone/tablet portrait/landscape surface, hardware-keyboard commands, and accessibility basics on device.

Exit: a user can edit the two-module sample without losing changes across rotation or forced activity recreation.

### M3.1 — IDE application shell and file workflow (pre-M4)

- [x] Replace the editor header with the Pydroid-inspired application shell specified in `docs/editor/M3_1_APP_SHELL_REQUIREMENTS.md`: hamburger/drawer control, active filename plus project-relative path, Run/Play, Files, and More icons.
- [x] Implement accessible anchored icon menus: Files exposes New/Open/Save/Save As/Close, and More exposes Undo/Redo/Find, with icon-plus-text rows and truthful enabled states/focus. Each closes on outside click, Back/Escape, item selection, or opening the other menu; only one popup may be open.
- [x] Add atomic Save without build; replace the visible Rename workflow with real Save As semantics that retains the original; add safe tab Close with Save/Discard/Cancel handling and a usable empty-editor state.
- [x] Implement the full-screen Open workspace flow in `docs/editor/M3_1_OPEN_WORKSPACE_REQUIREMENTS.md`: Recent, My Projects, Import Project Archive, Import Project Folder, Open Lean File, and New Project.
- [x] Keep SAF as an import/export boundary: safely stage and activate archives/folders; open a single Lean file as a generated scratch project or add it to a selected project; never run Lake against provider URIs or external pseudo paths.
- [x] Move the hierarchical file tree into a collapsible **Project** drawer section; place Build project and Verify runtime in the drawer, close it on outside click/Back/hamburger, and keep its structure extensible for later Settings and other destinations.
- [x] Make Run/Play save, build, and execute the supported offline Lean project entry behavior with bounded output/cancellation while preserving the no-network, no-native-target, no-terminal boundary.
- [x] Replace the Find surface's Close Search text with an accessible X icon, change Ctrl-S to Save, and preserve existing undo/redo/find shortcuts and recovery behavior.
- [x] Validate anchored menus, drawer/tree expansion, Save/Save As/Close, Run, adaptive phone/tablet portrait/landscape behavior, keyboard/focus, accessibility, recreation, and no-orphan cleanup on a physical device.

Exit: a user can navigate the project from the drawer; open recent/internal projects; safely import a project archive/folder or one Lean file; manage files from conventional anchored menus; save without building; Save As without losing the original; close safely; and run the supported project flow from the top bar on phone and tablet. M4 does not begin until this exit is device-proven.

### M3.2 — Editor tabs, line numbers, and appearance settings (pre-M4)

- [x] Add a persistent browser-style tab strip for every open file. Opening or creating a file adds and focuses its tab while keeping prior open tabs visible and selectable; active, inactive, and dirty states must remain distinct.
- [x] Add a line-number gutter that stays aligned with the editable Lean source, scales to the current line count, and preserves cursor/edit/search/recovery behavior.
- [x] Add an extensible **Settings** destination to the navigation drawer. It opens as a full-screen page with an **Appearance** destination; Appearance opens its own page and contains a labeled, accessible dark-theme toggle.
- [x] Persist the explicit light/dark choice across Activity and process recreation and apply it consistently to the app shell, editor, dialogs, drawer, Settings, and Appearance pages.
- [x] Add host/state coverage and physical-device acceptance for multi-file tab focus/retention, tab switching with dirty buffers, line-number updates and scrolling, Settings/Appearance navigation, theme switching/persistence, adaptive layouts, recreation, and the existing offline Run/no-orphan baseline.

Exit: multiple project files behave like browser tabs, every editor line has a stable aligned number, and the user can navigate Drawer → Settings → Appearance to select a persistent dark theme. M4 does not begin until this exit is device-proven.

### M3.3 — User-facing project export (pre-M4)

- [x] Implement `docs/project/M3_3_PROJECT_EXPORT_REQUIREMENTS.md`: add an accessible **Export project** action to the navigation drawer for the active app-managed project.
- [x] Use SAF `CreateDocument` to stream a portable ZIP to a user-selected provider destination without storage permission, exposing an internal app-private path, persisting the provider URI as project identity, or running Lean/Lake against exported content.
- [x] Define and test the portable archive contents: include validated sources, `lakefile.toml`, pinned `lean-toolchain`, and required portable project metadata; exclude `.lake/`, recovery snapshots, caches, temporary files, links, device paths, logs, and secrets.
- [x] Protect dirty buffers with explicit **Save and Export**, **Export saved version**, and **Cancel** choices; keep the active project and editor state unchanged by export.
- [x] Bound entry count, per-file and aggregate bytes, stream with bounded memory, report provider/cancellation failures truthfully, and clean app-owned staging plus a partial destination where the provider permits deletion.
- [x] Add archive unit/instrumentation coverage and physically prove export through SAF, reimport under a fresh internal identity, offline build/run equivalence, cancellation/failure recovery, adaptive drawer behavior, recreation, and exact no-orphan cleanup.

Exit: a user can choose **Export project** from the drawer, save a portable project ZIP outside uninstall-sensitive app storage, and reimport that ZIP into a working offline project without leaking generated/device-private state. M4 does not begin until this exit is device-proven.

### M4 — Interactive Lean experience (4–6 weeks)

- [x] Connect every open editor buffer to the retained per-project `LeanLspService`: initialize the pinned `lake serve` workspace, send ordered/versioned open/change/save/close notifications, use correct UTF-16 positions, and reopen current documents after a generation change.
- [x] Render live version-filtered diagnostics in the source surface and a bounded Messages view. Reject stale diagnostics/responses after edits, file switches, project switches, and server restarts without losing dirty buffers or blocking normal Save/Run behavior.
- [x] Implement hover, completion, go-to-definition, references where supported, and the pinned Lean goal/RPC request needed for cursor-synchronized goals. Keep requests cancellable/generation-tagged and degrade individual unsupported capabilities without destabilizing the document session.
- [x] Add a dedicated **Goals** pane alongside the existing editor and output panel. Add **Settings → Editor → Goals pane position** with exactly **Auto**, **Right side**, and **Bottom** choices. Persist the choice app-privately and apply it immediately: Auto resolves to Bottom in portrait and Right side in landscape; the explicit choices do not change merely because orientation changes.
- [x] Make the three work surfaces practically resizable. A visible, keyboard/accessibility-operable splitter adjusts the Goals pane width in right-side mode and height in bottom mode; the editor/output split is likewise adjustable on the axis where they share space. Clamp every pane to usable minimum/maximum bounds, persist independent normalized sizes for right/bottom and editor/output arrangements, restore them across Activity/process recreation, and re-clamp rather than hide content when window size, orientation, font scale, or system insets change.
- [x] Define adaptive behavior for compact windows and IME use: no overlapping panes, splitters remain reachable, editor text/cursor and diagnostic navigation remain usable, and a collapsed/temporarily hidden pane has an accessible way to restore it. Tablet portrait/landscape and phone-sized portrait/landscape must preserve the completed drawer, tabs, gutter, menus, output, and dark-theme baselines.
- [x] Add visible server status/progress and an explicit restart action in the Goals pane, which is the single server-status surface; do not duplicate status between the editor and output panel. Implement bounded crash recovery/backoff, one-reader ownership, deterministic teardown, cancellation, and exact no-orphan cleanup while retaining one server per active project.
- [x] Validate with the full split-artifact runtime: automated framing/lifecycle/document-version/UTF-16/restart tests; Compose/state tests for placement, resizing, persistence, rotation, accessibility, and stale UI rejection; and physical offline device scenarios covering rapid edits, save, file switch, cursor movement/goals, hover/completion/definition, output interaction, process recreation, forced server restart/crash, and all Auto/Right/Bottom layouts. Measure cold and warm diagnostic/goal latency and peak LSP PSS separately from one-shot checking.

Exit: automated protocol and UI tests plus physical offline device scenarios demonstrate correct current-version diagnostics and cursor-synchronized goals during rapid edits, save, file switch, Activity/process recreation, and server restart; hover, completion, and go-to-definition work for the supported two-module project; Auto/Right/Bottom Goals placement and persisted bounded resizing keep editor, output, and Goals usable in tablet and phone-sized portrait/landscape layouts; and shutdown/cancellation leave no Lean/Lake child.

### M4.1 — Named project creation and direct Files-menu access

- [x] Let the user choose a project name when creating a project. Validate and normalize the name before any filesystem mutation, show collisions and invalid names inline, and create/activate the project atomically without losing the current workspace or dirty buffers on cancellation/failure.
- [x] Add **New Project** to the folder/Files popup alongside New, Open, Save, Save As, and Close. Keep **New** scoped to creating a file in the current project and **New Project** scoped to the named-project flow; both labels, enabled states, focus order, keyboard dismissal, and accessibility semantics must remain unambiguous.
- [x] Reuse the same named-project flow from Open workspace → New Project so both entry points have identical validation, defaults, cancellation, recovery, recent-project registration, and offline pinned-toolchain metadata.
- [x] Add host/state tests plus physical phone/tablet coverage for valid names, invalid/reserved names, case-folded collisions, cancellation, Activity recreation, Files-menu access, project activation, first edit/save/Run, and exact no-orphan cleanup.

Exit: a user can choose **New Project** directly from the Files menu or Open workspace, assign a valid name, and enter an atomically created offline-ready project; file-level **New** remains clearly distinct and failures never disturb the prior project or dirty editor state.

### M4.2 — Expected types and richer editor inspection

- [x] Request `$/lean/plainTermGoal` at the same debounced, generation/version-checked cursor position as `$/lean/plainGoal`. Retain tactic goals and term expected types independently: display whichever non-empty result exists, display both under distinct **Goals** and **Expected type** sections when both exist, and display **No goals** only when neither exists.
- [x] Extend the editor's native long-press/selection context menu with **Hover** while preserving the platform Cut, Copy, Paste where applicable, and Select all actions. Route the action through the existing hover request/state path at the active selection or cursor and reveal the result in the existing Goals pane without changing source or selection.
- [x] Render Lean hover Markdown as structured, selectable content rather than raw Markdown punctuation. Support bounded paragraphs, headings, lists, inline code, emphasis, links as readable labels, and fenced code blocks; apply the existing Lean syntax highlighter to `lean`/`lean4` fences and a safe monospaced fallback to other code fences. Do not add WebView, network content loading, raw HTML execution, or an unrestricted Markdown engine.
- [x] Add protocol/parser/render-model tests for all four goal combinations plus physical API-33 acceptance for real tactic-only, term-only, and neither states; context-menu Hover alongside standard editing actions; Markdown and Lean-fence rendering in the current dark tablet layout and compact-window recreation; stale-response rejection; and exact no-orphan cleanup.

Exit: the Goals pane truthfully distinguishes tactic state from term expected type, editor selection offers a non-destructive Hover shortcut alongside standard Android actions, and hover documentation is readable with highlighted Lean code without introducing network or executable-content behavior.

### M4.3 — Mobile editor navigation and symbol input (pre-M5)

- [x] Correct named-project scaffolding so a freshly created project opens `Main.lean` without an `unknown module prefix` diagnostic and builds/runs offline with the generated Lake package and module names.
- [x] Make the complete navigation drawer vertically scrollable while keeping project navigation practical when either the file tree or the action/settings section is long.
- [x] Replace the flat centered project-path list with a left-aligned hierarchical tree. Compact chains of single-child directories in the VS Code style, preserve expandable folders, and expose typed folder/file icon metadata so later file-type-specific icons do not require rebuilding tree traversal.
- [x] Give the project tree its own bounded horizontal and vertical scrolling region, independent from the outer drawer scroll, so deep paths and many siblings remain reachable without hiding Settings and other drawer actions.
- [x] Add a persistent, horizontally scrollable mobile symbol row at the bottom of the editor and directly above the IME when it is visible. Insert braces, carets, and a curated first set of Lean/Unicode symbols at the current selection/cursor through the normal editor edit/history/LSP path.
- [x] Add host/state/UI coverage for scaffolding, tree construction/compaction/icons, independent scroll behavior, symbol insertion/replacement/undo, accessibility, and IME/adaptive layouts; physically validate Samsung keyboard variants, deep/wide trees, project creation, offline Run, recreation, and exact no-orphan cleanup on the API-33 reference tablet.

Exit: a newly named project is immediately diagnostic-free and runnable; every drawer action remains reachable with a large project; the project tree is recognizable, left-aligned, independently two-axis scrollable, and extensible for file-type icons; and the symbol row reliably edits at the current cursor/selection above supported Samsung keyboard layouts. M5 does not begin until this exit is device-proven.

### M4.4 — Collapsible panes and compact-landscape reachability (pre-M5)

- [x] Add direct accessible collapse/restore controls attached to the Output and Goals splitters and the Messages header. Preserve each pane's last expanded size independently from its collapsed state; move the Goals control with its resolved Right/Bottom placement.
- [x] When a docked/non-floating IME is visible, temporarily suppress Output so only the editor, symbol row, and expanded Messages consume the resized workspace. Restore Output and its prior collapsed/expanded state when the IME closes; retain Android's overlay behavior for floating keyboards.
- [x] Give an expanded Project tree a practical minimum height in extreme landscape windows where height is less than half the width, while keeping the drawer action region vertically scrollable and every action reachable.
- [x] Add host/state and semantics coverage plus physical API-33 Samsung floating/docked keyboard, Right/Bottom Goals, Messages/Output collapse, extreme-landscape tree, recreation, baseline Run, and no-orphan acceptance.

Exit: Goals, Messages, and Output can each be collapsed and restored at their visible boundary; docked typing preserves source/symbol/message space without Output consuming resized height; and an expanded project tree remains usable in extreme landscape without hiding drawer actions. M5 remains gated on device evidence.

### M4.5 — Pre-M5 UI polish

- [x] Reduce the Output/Goals splitter footprint while retaining attached, accessible collapse controls, compact the mobile symbol row, and reduce the collapsed Messages height.
- [x] Preserve the open/closed navigation drawer and Project tree across configuration recreation, including folder expansion state.
- [x] Remove the artificial gap between a closed Project tree and drawer actions while retaining the expanded tree's independent allocation.
- [x] Use neutral Messages colors unless at least one LSP diagnostic has error severity; retain error-container treatment when an error is present.

Exit: panel boundaries and the symbol row preserve more workspace, navigation state survives orientation changes, collapsed drawer actions remain evenly spaced, and Messages reserves red treatment for actual errors. M5 remains next.

### M4.6 — Project and tree lifecycle actions (pre-M5)

- [x] Add guarded project rename/delete actions below Export project in the drawer, with active/recent preference and recovery migration plus a valid fallback after active deletion.
- [x] Add Rename/Delete long-press menus to Open workspace project rows and file tabs.
- [x] Add Rename/Delete long-press menus to project-tree files and folders, with prefix-aware open-tab remapping/removal.
- [x] Keep project/file/folder operations contained, collision-safe, link-safe, and atomic where renamed; require explicit destructive confirmation and retain at least one project and one Lean source.
- [x] Cover repository/session operations on the host and physically validate all entry surfaces, active rename/delete recreation, cleanup, Ready LSP restoration, baseline offline Run, and exact no-orphan behavior.

Exit: projects, files, and folders can be renamed or deleted from every requested surface without traversal, silent dirty loss, stale recovery, recreation crashes, or orphan processes. M5 remains next.

### M5 — Mathlib beta (duration determined by size/performance spike)

Execution plan: [`docs/delivery/M5_MATHLIB_INTEGRATION_PLAN.md`](docs/delivery/M5_MATHLIB_INTEGRATION_PLAN.md).

- Produce and verify a version-matched Mathlib pack.
- Add pack install/remove/status UI, capacity reporting, and recoverable install-failure handling.
- Profile memory, import latency, goal latency, and thermal behavior on a mid-range device.

Exit: representative Mathlib files work offline without process death, and the distribution method meets store and license requirements.

#### Parallel execution policy

M5 artifact production is a slow, resumable producer lane, not a global roadmap lock. At most one process may write `toolchain/work/mathlib-android2-producer`; its `flock`, state file, append-only log, conservative facet audit, and `--rehash --no-cache` restart path are the authority for recovery. A host crash or stopped producer does not invalidate finalized modules and must not trigger a clean rebuild.

While a targeted or full Mathlib build runs, continue the foreground product lane with work that does not mutate the producer tree, Android2 Core/Std candidate, Mathlib pins, pack schema, or the same physical-device state needed by an immediate Mathlib gate. In particular, the dynamic Lake module corrective, M5.0 UI work, M5.1–M5.3 host-side implementation, M6 preparation, documentation, and API/emulator coverage may advance with validation proportional to their own boundaries. Schedule brief Mathlib artifact audits and tablet import/install/performance gates at producer checkpoints; those gates block promotion of the Mathlib pack, not unrelated implementation.

Before starting or resuming a producer, run `mathlib/scripts/status-android2-build.sh`. Use the exact targeted/full recipes in the Mathlib integration plan and rebuilding guide. Prefer two producer jobs when foreground Gradle/emulator work needs capacity and up to four when the producer owns the machine; never start a second producer to change job count. Do not run broad cleanup, dependency updates, official cache substitution, or concurrent commands against the producer checkout.

### M5 foreground prerequisite — UI style and theme architecture

Decision and migration plan: [`docs/editor/UI_STYLE_ARCHITECTURE.md`](docs/editor/UI_STYLE_ARCHITECTURE.md).

- Separate visual decisions from screen/activity behavior before adding the next UI features. Move the root Material theme, semantic colors, typography, shapes, elevations, dimensions, spacing, and meaningful component style contracts into a Compose-native Kotlin design-system package.
- Use layered Material 3 foundations, typed semantic design tokens, `CompositionLocal` theme access, and immutable component style objects. Keep adaptive/window/IME policy, pane fractions, state, callbacks, accessibility semantics, and content in explicit UI logic.
- Extract meaningful reusable UI components from `MainActivity.kt` incrementally. Account for applicable width/height bounds, spacing, container/content/text colors, text roles, shapes, elevation, tint, and visual variants at each component boundary without creating a style class for every structural `Row` or `Column`.
- Preserve caller-owned `Modifier` behavior and modifier ordering. Use modifier presets only for narrow repeated decoration; do not introduce class strings, a CSS cascade, an external stylesheet/config parser, arbitrary user-controlled layout, or a new UI dependency.
- Preserve the accepted M4.6 light/dark, adaptive layout, IME, accessibility, editor, pane, and lifecycle baselines. Build future M5.0 popup Output/completion and M5.3 font preferences on the same semantic style system.

Exit: visual tokens and meaningful component styles are discoverable outside screen logic; adaptive behavior remains typed and tested; the current UI is physically equivalent across supported layouts; and subsequent UI work has one safe extension mechanism.

### M5 corrective — Dynamic Lake module coverage

Implement this in the foreground product lane without waiting for the targeted `Mathlib.Data.Nat.Prime.Basic` producer. Complete its own host and Android validation before starting broader/full Mathlib production. This repairs the current generated-project configuration, whose hardcoded `roots = ["Main", "<Project>.Basic"]` recognizes the scaffolded `Basic.lean` module but not subsequently created sibling or top-level modules.

- Replace the `Basic`-specific library boundary with a canonical configuration derived from the current project topology. Keep `Main` explicit; cover the generated inner Lean project directory with a submodule glob; and add explicit roots/globs for every project-root `.lean` file and every other project-root module directory. Account for the case where a top-level `Foo.lean` and `Foo/` directory coexist so both `Foo` and its submodules remain buildable.
- Centralize configuration reconciliation in `core-project`; UI and service code must not construct or patch Lake TOML. Reconcile atomically and deterministically after contained source/directory create or import, save/modify, rename/move, delete, Save As, project/archive/folder import, and project rename when its derived Lean identity changes. Also reconcile before Build/Run and Lean Server startup as a drift-recovery boundary. Do not rewrite the file when canonical bytes are unchanged.
- Treat a content-only save as an idempotent reconciliation trigger even though it ordinarily leaves module membership unchanged. A failed configuration update must not leave the source mutation reported as fully successful with stale build metadata; define transaction/recovery ordering and retain truthful recovery state.
- Change Files → **New** to prefill `<LeanProjectName>/New.lean`, using the project's derived Lean name rather than its display/storage ID. Keep the entire project-relative path editable: users may remove the prefix to create a sibling of `Main.lean` or replace it with another contained module directory.
- Validate every path component used as a Lean module name, preserve traversal and case-folded-collision protections, and give a clear inline error for paths that are safe filesystem names but invalid or ambiguous Lean module paths. Continue to reject unsupported executable/native/network Lake configuration.
- Migrate existing app-managed projects conservatively. Parse and verify the supported generated configuration, preserve supported user-authored package fields, and avoid silently rewriting arbitrary imported Lake projects whose semantics the app cannot safely round-trip. Define an explicit unsupported/manual-repair result where canonical reconciliation is not safe.
- Add host tests for scaffold output, default New path derivation, nested siblings, arbitrary top-level files/directories, file-plus-directory name overlap, every lifecycle trigger, unchanged-byte saves, rename/delete cleanup, crash/failure recovery, existing-project migration, deterministic TOML, and hostile/unsupported configurations.
- Add Android validation that creates and imports modules both beside `Main.lean` and under the generated inner directory, observes successful LSP imports after reconciliation, completes offline Build and Run, survives Activity/process recreation and project rename, and leaves no Lean/Lake child. Retain the visible two-tab editor and M2–M4 lifecycle baselines.

Exit: a user can create, import, save, rename, move, and delete Lean modules anywhere within the supported project tree; Lake and Lean Server use a deterministic up-to-date module configuration; Files → New defaults to the generated inner project directory without preventing top-level placement; and existing supported projects migrate without data loss.

### M5.0 — Near-term output and completion UX

Implement these in the listed priority order after the focused M5 Mathlib compatibility/feasibility gate and before the broader M5.1 editor expansion.

#### Priority 1 — Output presentation and automatic reveal

- Add **Settings → Interface → Output presentation** with persistent **Docked** and **Popup** choices. **Docked** is the existing pane integrated into the workspace; **Popup** is an in-app output window layered over the workspace. This setting affects Output only and must not change the Messages or Goals panes.
- In Docked mode, starting **Build project** or Run/Play automatically expands/reveals Output, including when the user previously collapsed it. Retain the established temporary Output suppression while a docked IME is visible, then reveal it when the layout can do so without obscuring typing.
- In Popup mode, Output remains absent from the normal editor split and opens automatically only in response to Build project or Run/Play. Keep a direct way to dismiss and reopen the current bounded output without restarting the job; Back/Escape, cancellation, Activity recreation, and repeated runs must have deterministic behavior.
- Preserve bounded chronological output, progress/error states, selection/copy, cancellation, pane-size memory for later Docked use, accessibility, compact/tablet layouts, and exact child-process cleanup in both modes. Do not implement this as a system overlay, WebView, or second Activity unless a later design review establishes a concrete need.

#### Priority 2 — Automatic inline LSP completion

- Stop presenting completion candidates as Goals-panel text. When completion is enabled, request candidates automatically from the retained LSP session as the user types, using bounded debounce, document version/generation checks, cancellation, and stale-response rejection.
- Show candidates in a bounded, accessible popup anchored directly below or otherwise adjacent to the active caret, clamped to the visible editor/IME window. Let touch and keyboard users select a candidate, dismiss the popup, and insert the LSP-provided replacement/text edit through the normal editor history, recovery, and LSP-version path.
- Add a persistent **Settings → Editor → Completions** enable/disable control. Disabling it cancels pending automatic requests and dismisses the popup while preserving explicit non-completion LSP features.
- Define prefix replacement, LSP `textEdit`/insert-text handling, selection/caret placement, undo grouping, IME composition, rapid typing, file/project switches, server restart, empty/error results, and Activity recreation before acceptance. Do not silently execute arbitrary snippets or commands returned as completion metadata.

Exit: Build and Run always reveal Output according to the selected Docked/Popup mode without affecting Messages or Goals, and enabled LSP completions appear at the caret and can be inserted safely instead of being printed in Goals.

### M5.1 — General project files and program streams

The detailed process, buffering, lifecycle, UI, security, and validation design is recorded in [`docs/project/STDIN_SUPPORT.md`](docs/project/STDIN_SUPPORT.md).

- Generalize project creation, import/export, tree, tabs, recovery, rename/delete, and text editing from Lean-only files to bounded project-contained text files. Start with a plain-text fallback and explicit encoding/size/error handling; binary files may remain visible/exportable but must not be decoded as text.
- Introduce a highlighter registry selected by file extension or detected content type, with plain text as the mandatory fallback. Keep Lean highlighting as the first registered language; additional language grammars are follow-up work rather than a prerequisite for opening a file.
- Run supported Lean programs with the app-managed project root as their deterministic working directory so project-relative reads can address files visible in the tree. Preserve typed commands, a minimal deterministic environment, traversal-safe app operations, and the rule that this is not a shell or a per-project OS sandbox.
- Replace the implicit always-empty stdin behavior with three explicit run modes: immediate EOF, interactive console, and bytes streamed from a selected project file. A child blocking on a pipe and arbitrary prompt text on stdout/stderr do not provide a reliable structured "input requested" signal, so interactive mode keeps a bounded input channel open and lets the user send text/lines or EOF without claiming automatic prompt detection.
- Capture stdout and stderr as separate bounded streams while retaining a useful combined chronological Output view. Let users export the current stdout or stderr independently through SAF, and configure a contained project file as stdin without granting provider paths to Lean/Lake.
- Define cancellation, backpressure, process-death, rotation/reconnection, EOF, repeated-run, dirty-input-file, and export-failure behavior before enabling the interactive UI. Do not allow blocked input to leave an orphan process.

Exit: a project can safely contain and edit ordinary text files; a Lean program can read a project-relative file; EOF, interactive, and project-file stdin modes behave predictably; stdout/stderr remain bounded and separately exportable; and cancellation/recreation leave no child process or silent data loss.

### M5.2 — Source navigation results

- Change go-to-definition from Goals-panel location text to opening the target project file and placing the cursor at the returned UTF-16 line/column when the URI resolves to a contained, user-accessible project file. Retain a truthful non-navigable location display for toolchain, generated, or external targets.
- Present find-references results in a bounded, accessible popup with project-relative path, line, and column; sort/deduplicate results and let each row open the file and move the cursor immediately.
- Preserve dirty tabs, file identity, generation/document-version checks, cancellation, focus, Back/Escape behavior, and stale-result rejection across both navigation paths.

Exit: definition and reference requests navigate directly to valid contained project locations, inaccessible targets degrade truthfully, and stale or malformed LSP locations cannot open an external path or move the cursor incorrectly.

### M5.3 — Editor and interface preferences

- Add **Settings → Editor** controls to show or hide the symbol row and customize its ordered contents, with validated persistence, restore-defaults behavior, accessible labels, and a supported maximum of 60 entries. Preserve horizontal scrolling and cursor/selection-aware insertion.
- Add independently persisted editor-font and interface-font choices from documented fixed ranges. Apply changes immediately and revalidate pane clamping, tabs, dialogs, drawer reachability, line-number alignment, accessibility scaling, IME behavior, and configuration/process recreation.

Exit: symbol-row visibility/content and both font-size settings are durable, bounded, accessible, and usable across supported phone/tablet layouts without regressing editing or pane reachability.

### M6 — Hardening and beta release (3–5 weeks)

- Threat-model imported ZIPs/projects, any added UI dependency or popup/window boundary, native processes, and package manifests.
- Run compatibility, soak, cancellation, corruption, and process-death tests; treat manufactured low-storage pressure as a stretch hardening case.
- Add crash reporting with opt-in/privacy controls, onboarding, licenses, backup policy, and a support bundle exporter.
- Publish known limitations and supported Lean/package versions.
- Test API 29 and current Android, multi-user/profile behavior, APK path migration, USB-independent production flows, and all supported delivery channels.

Exit: signed beta passes the release test matrix with no critical data-loss, sandbox-escape, startup, or orphan-process bugs.

### M7 — Post-beta editor depth

- Implement Lean-style backslash abbreviation completion from a pinned, reviewed abbreviation data source. Conversion occurs only on the configured delimiter (initially Space), uses deterministic prefix resolution, supports multi-character replacements and `$CURSOR` placement, and forms one coherent undo edit without breaking IME composition, selections, or LSP versions.
- Investigate and then implement syntax-aware auto-indent only after defining Lean newline/dedent behavior, selection replacement, paste handling, undo grouping, IME composition, and LSP-version invariants. Begin with a measured prototype; do not ship indentation rules that unpredictably rewrite existing text.
- Investigate code folding with a stable source-to-visible-text mapping, gutter affordances, cursor/selection behavior, diagnostics/navigation into folded ranges, edits spanning folds, recovery, accessibility, and large-file performance. Prefer Lean syntax information when available; use indentation-based regions only if they are deterministic and validated against representative Lean code.
- Add word wrap only after the native editor can keep wrapped visual rows, the line-number gutter, selection/cursor geometry, diagnostic navigation, scrolling, IME visibility, and large-file performance consistent.
- Add syntax highlighters to the M5.1 registry according to demonstrated demand; opening/editing a file must never depend on a grammar being installed.

Exit: deferred editor features meet correctness, accessibility, performance, undo, and physical IME acceptance criteria without destabilizing the beta's plain-text fallback or Lean editing baseline.

## 6. Test strategy

The device-confirmed visual editor is a continuous acceptance baseline, not a disposable capability. Every milestone that changes application UI, toolchain installation/layout, command construction, process supervision, project storage, or LSP wiring must preserve a visually operable editor and revalidate an end-to-end edit/save/Lean/result path. The M1 Compose probe may be refactored or replaced by the planned production editor, but the repository must not return to a probe-only or headless state.

### Host unit tests

- JSON-RPC framing with fragmented/coalesced UTF-8 messages and malformed headers.
- document-version and edit-offset conversion, especially UTF-16 LSP positions;
- environment construction and command argument handling;
- manifest/hash/signature validation;
- project path validation and hostile ZIP fixtures;
- state restoration and migration logic;
- output-presentation persistence/reveal rules and completion popup/request/edit state;
- generic text/binary classification, encoding and size limits, and highlighter-registry fallback; and
- URI-to-contained-project location resolution plus bounded reference-result normalization.

### Android integration tests

- native executable discovery and ABI/API compatibility;
- split-artifact completeness and installer hash verification;
- compatibility-link creation, stale APK-path repair, and update migration;
- deterministic Lean/Lake environment construction without inherited shell state;
- stdin/stdout/stderr backpressure and cancellation;
- explicit EOF/interactive/project-file stdin modes, separate bounded stdout/stderr capture, stream export, and input-wait recreation;
- LSP initialize/edit/diagnostics/shutdown transcripts;
- cold install, upgrade, corrupted toolchain, and interrupted pack install;
- app backgrounding, activity recreation, and app process death; optionally add manufactured low-storage pressure in the hardening lane;
- IME, Unicode, hardware keyboard, TalkBack, and large source files; and
- Docked/Popup Output behavior plus caret-anchored completion positioning, insertion, dismissal, recreation, and stale-result rejection.

### Toolchain conformance suite

Maintain a small corpus containing successful proofs, syntax/type errors, multi-module imports, Unicode identifiers, macros, tactics, `#check`, and `#eval`. Run it on desktop Lean and Android Lean and compare normalized outcomes. Add Mathlib cases only to the Mathlib lane.

The corpus must separately exercise direct Lean, `lake lean`, `lake build`, and `lake serve`. At least one case must require interpretation/IR, one must depend on server/private module data, and one must use a path containing spaces and non-ASCII characters so future size filtering cannot silently remove required facets.

CI should build and unit-test every change, build the pinned toolchain from scratch on scheduled/release jobs, run emulator smoke tests, and gate releases on physical-device tests. Sanitizer builds may run on host even when unavailable in the Android production configuration.

## 7. Risks and explicit mitigations

| Risk | Consequence | Mitigation / decision gate |
|---|---|---|
| Lean bootstrap or runtime assumes desktop Unix | No viable binary | M1 first; isolate patches and upstream them where possible |
| Android executable restrictions | Process launch fails on modern devices | Execute only APK-installed code; physical API-level matrix; JNI fallback ADR |
| APK reinstall changes native executable paths | Lake links become stale after every update | Never persist APK path as toolchain identity; refresh validated symlinks on startup/update |
| Lean strips Bionic heap pointer tags | Deterministic native abort during initialization | Android-only early runtime `mallopt`; conformance tests on every supported API/device |
| Lean split artifacts exceed 2 GB installed | Base APK/store/install strategy is infeasible | M1.6 delivery gate; data-only asset packs; streaming verified install; investigate flattened artifacts |
| Missing `.olean.server`, `.olean.private`, or `.ir` | Imports fail despite apparently healthy `.olean` | Manifest declares required facet set; installer and health check verify representative and complete hashes |
| Lean/Lake executable-relative path inference | Standard library or Lake installation is not found | `LEAN_SYSROOT` patch, typed environment, conventional-layout symlinks, upgrade tests |
| Lean/Lake invokes missing Unix tools | Builds or dependencies fail | Constrain v1 commands; audit subprocesses; expose capabilities, not a terminal |
| LSP/Mathlib exceeds mobile memory | OS kills server or UI stalls | Measure RSS early; one server/project; bounded caches; explicit server stop |
| Toolchain/Mathlib version mismatch | Invalid artifacts or confusing errors | Immutable version IDs and signed compatibility manifests |
| Package size is too large | Store/install failure | Core delivery spike before UI expansion; separate data packs, AAB/asset delivery, measure compressed/installed/peak temporary sizes |
| Arbitrary package build logic | Security and compatibility issues | Offline allowlisted packs first; no downloaded executable/native plugins |
| An added UI dependency expands the attack or lifecycle surface | Project/data exposure, offline regressions, or inconsistent state | Prefer existing native Compose primitives; require a scoped design, dependency audit, typed ownership boundaries, and proportional device validation |
| App update or crash loses work | User data loss | Atomic saves, recovery snapshots, migration rollback, import/export tests |
| Android background limits kill builds | Interrupted operation | foreground service for user-visible long jobs; persistent job state and cancellation |
| A blocked stdin read looks like a hung program | Confusing UI or orphaned child | Explicit run input mode and visible waiting/running state; send/EOF/cancel controls; never infer a prompt from arbitrary output |
| General project files expose unintended paths or exhaust memory | Data exposure, crashes, or corrupt editing | Contained project resolution, deterministic working directory/environment, text/binary and encoding policy, per-file/aggregate limits, streamed import/export |
| LSP navigation returns stale or external URIs | Wrong-file edits or path escape | Generation/version checks and canonical contained-project URI mapping; show non-navigable external locations without opening them |

## 8. Work that should wait until after beta

- multiple downloadable Lean toolchains and automatic project-version switching;
- arbitrary online Lake/Git dependency resolution;
- compiling/running native Lean executables on-device;
- a general terminal;
- full Git client;
- all Lean infoview widgets and custom package widgets;
- Lean-style Unicode abbreviation completion and word wrap (planned for M7 after their input/cursor/layout invariants are designed);
- bundled syntax grammars beyond the M5.1 plain-text fallback and existing Lean highlighter;
- non-arm64 ABIs; and
- collaborative/cloud editing.

These are valuable, but each expands the executable-code, package-management, UI, or support surface. The beta should establish that Lean checking and interactive proving are reliable on a phone first.

## 9. Immediate next actions

1. Treat Mathlib artifact production as a resumable parallel lane: inspect it with `mathlib/scripts/status-android2-build.sh`, resume the targeted `Mathlib.Data.Nat.Prime.Basic` command when machine capacity permits, and never run concurrent producers. At a successful target checkpoint, audit facets/header and complete the basic Android2 import test.
2. Implement the UI style/theme architecture prerequisite from `docs/editor/UI_STYLE_ARCHITECTURE.md` before further UI feature work, using a staged Compose-native token/component migration that preserves M4.6 behavior.
3. In the foreground lane, implement and validate the M5 dynamic Lake module-coverage corrective: inner-directory globbing, explicit top-level module reconciliation, supported-project migration, and the `<LeanProjectName>/New.lean` default path. It no longer waits for the slow targeted build, but must finish before broader/full Mathlib production.
4. After both the targeted Android import gate and module corrective pass, start/resume the full version-matched Mathlib producer in parallel with independent product work. Measure capacity, recovery, latency, memory, and thermal behavior at explicit pack checkpoints without regressing the completed M2–M4 editor/project/LSP/lifecycle baselines.
5. After the focused M5 Mathlib compatibility/feasibility gate, complete M5.0 in priority order: first Docked/Popup Output presentation with automatic Build/Run reveal, then enabled/disabled caret-anchored automatic LSP completion.
6. Complete M5.1 general project files and explicit program-stream modes, then M5.2 direct definition/reference navigation and M5.3 symbol/font preferences before freezing features for M6 hardening and beta release.
7. Keep auto-indent, code folding, Unicode abbreviation completion, and word wrap in post-beta M7 until their editing, source-mapping, IME, accessibility, and performance invariants are designed and measured.
8. Convert M1.6 prototypes into release configuration: pin the independent-pack public key, add download/status UI if needed, and validate the signed AAB through Play Console/bundletool.
9. Add API-29 and current-Android physical/emulator coverage throughout M5–M6 while retaining the offline M2 lifecycle, M3 editor/file/export behavior, and M4 interactive/pane baselines.
10. Preserve and revisit ADR 0001 if API/device coverage produces evidence against the accepted process/runtime boundary.

## 10. Reference material

- [Android 10 behavior changes: execution from the writable app home is prohibited](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Lean 4 upstream repository and build documentation](https://github.com/leanprover/lean4)
- [Lean toolchain contents](https://lean-lang.org/doc/reference/latest/Build-Tools-and-Distribution/)
- [Lake command-line, environment, build, and language-server documentation](https://lean-lang.org/doc/reference/latest/Build-Tools-and-Distribution/Lake/)
- [Lean server protocol overview](https://lean-lang.org/doc/api/Lean/Server/ProtocolOverview.html)
- [Functional Programming in Lean: worked `cat` example using files and standard input](https://lean-lang.org/functional_programming_in_lean/Hello___-World___/Worked-Example___--cat/)
- [Android App Bundle format and asset packs](https://developer.android.com/guide/app-bundle/app-bundle-format)
