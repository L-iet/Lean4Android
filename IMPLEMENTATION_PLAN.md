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

Run a short prototype comparing a native Android code editor with CodeMirror 6 in a WebView. Select using measured requirements: incremental document edits, Unicode, large-file behavior, IME correctness, hardware-keyboard shortcuts, diagnostic decorations, completion UI, accessibility, and bidirectional Kotlin communication.

If CodeMirror wins, keep the bridge small and typed. JavaScript sends document edits and user actions; Kotlin owns files, processes, and LSP. Never expose a broad `addJavascriptInterface` object or filesystem paths to untrusted page content. Bundle all web assets and disable remote navigation/file access.

Before that bake-off, M1 includes a deliberately disposable native Compose visual probe: one fixed `Main.lean`, a multiline plain-text field, explicit Check action, and selectable process output. It validates the end-to-end edit/save/Lean/result interaction on a real device without prematurely choosing the production editor or coupling UI state to Lake/LSP architecture. It is not the M3 editor and should not grow a project tree, syntax engine, or live protocol client.

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

- [ ] Replace the editor header with the Pydroid-inspired application shell specified in `docs/editor/M3_1_APP_SHELL_REQUIREMENTS.md`: hamburger/drawer control, active filename plus project-relative path, Run/Play, Files, and More icons.
- [ ] Implement accessible anchored icon menus: Files exposes New/Open/Save/Save As/Close, and More exposes Undo/Redo/Find, with icon-plus-text rows and truthful enabled states/focus. Each closes on outside click, Back/Escape, item selection, or opening the other menu; only one popup may be open.
- [ ] Add atomic Save without build; replace the visible Rename workflow with real Save As semantics that retains the original; add safe tab Close with Save/Discard/Cancel handling and a usable empty-editor state.
- [ ] Implement the full-screen Open workspace flow in `docs/editor/M3_1_OPEN_WORKSPACE_REQUIREMENTS.md`: Recent, My Projects, Import Project Archive, Import Project Folder, Open Lean File, and New Project.
- [ ] Keep SAF as an import/export boundary: safely stage and activate archives/folders; open a single Lean file as a generated scratch project or add it to a selected project; never run Lake against provider URIs or external pseudo paths.
- [ ] Move the hierarchical file tree into a collapsible **Project** drawer section; place Build project and Verify runtime in the drawer, close it on outside click/Back/hamburger, and keep its structure extensible for later Settings and other destinations.
- [ ] Make Run/Play save, build, and execute the supported offline Lean project entry behavior with bounded output/cancellation while preserving the no-network, no-native-target, no-terminal boundary.
- [ ] Replace the Find surface's Close Search text with an accessible X icon, change Ctrl-S to Save, and preserve existing undo/redo/find shortcuts and recovery behavior.
- [ ] Validate anchored menus, drawer/tree expansion, Save/Save As/Close, Run, adaptive phone/tablet portrait/landscape behavior, keyboard/focus, accessibility, recreation, and no-orphan cleanup on a physical device.

Exit: a user can navigate the project from the drawer; open recent/internal projects; safely import a project archive/folder or one Lean file; manage files from conventional anchored menus; save without building; Save As without losing the original; close safely; and run the supported project flow from the top bar on phone and tablet. M4 does not begin until this exit is device-proven.

### M4 — Interactive Lean experience (4–6 weeks)

- Implement LSP lifecycle, document sync, live diagnostics, hover, completion, and go-to-definition.
- Add a cursor-synchronized goals/messages pane using the pinned Lean server protocol.
- Add crash recovery, stale-response handling, progress, and server restart controls.
- Validate server behavior with the full split-artifact runtime and measure its peak RSS separately from one-shot checking.

Exit: automated protocol tests plus a device scenario demonstrate correct diagnostics/goals during rapid edits, save, file switch, and server restart.

### M5 — Mathlib beta (duration determined by size/performance spike)

- Produce and verify a version-matched Mathlib pack.
- Add pack install/remove/status UI, capacity reporting, and recoverable install-failure handling.
- Profile memory, import latency, goal latency, and thermal behavior on a mid-range device.

Exit: representative Mathlib files work offline without process death, and the distribution method meets store and license requirements.

### M6 — Hardening and beta release (3–5 weeks)

- Threat-model imported ZIPs/projects, WebView bridge, native processes, and package manifests.
- Run compatibility, soak, cancellation, corruption, and process-death tests; treat manufactured low-storage pressure as a stretch hardening case.
- Add crash reporting with opt-in/privacy controls, onboarding, licenses, backup policy, and a support bundle exporter.
- Publish known limitations and supported Lean/package versions.
- Test API 29 and current Android, multi-user/profile behavior, APK path migration, USB-independent production flows, and all supported delivery channels.

Exit: signed beta passes the release test matrix with no critical data-loss, sandbox-escape, startup, or orphan-process bugs.

## 6. Test strategy

The device-confirmed visual editor is a continuous acceptance baseline, not a disposable capability. Every milestone that changes application UI, toolchain installation/layout, command construction, process supervision, project storage, or LSP wiring must preserve a visually operable editor and revalidate an end-to-end edit/save/Lean/result path. The M1 Compose probe may be refactored or replaced by the planned production editor, but the repository must not return to a probe-only or headless state.

### Host unit tests

- JSON-RPC framing with fragmented/coalesced UTF-8 messages and malformed headers.
- document-version and edit-offset conversion, especially UTF-16 LSP positions;
- environment construction and command argument handling;
- manifest/hash/signature validation;
- project path validation and hostile ZIP fixtures; and
- state restoration and migration logic.

### Android integration tests

- native executable discovery and ABI/API compatibility;
- split-artifact completeness and installer hash verification;
- compatibility-link creation, stale APK-path repair, and update migration;
- deterministic Lean/Lake environment construction without inherited shell state;
- stdin/stdout/stderr backpressure and cancellation;
- LSP initialize/edit/diagnostics/shutdown transcripts;
- cold install, upgrade, corrupted toolchain, and interrupted pack install;
- app backgrounding, activity recreation, and app process death; optionally add manufactured low-storage pressure in the hardening lane; and
- IME, Unicode, hardware keyboard, TalkBack, and large source files.

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
| Editor bridge compromises files | Project/data exposure | Bundled content only, narrow typed bridge, disabled navigation/file access |
| App update or crash loses work | User data loss | Atomic saves, recovery snapshots, migration rollback, import/export tests |
| Android background limits kill builds | Interrupted operation | foreground service for user-visible long jobs; persistent job state and cancellation |

## 8. Work that should wait until after beta

- multiple downloadable Lean toolchains and automatic project-version switching;
- arbitrary online Lake/Git dependency resolution;
- compiling/running native Lean executables on-device;
- a general terminal;
- full Git client;
- all Lean infoview widgets and custom package widgets;
- non-arm64 ABIs; and
- collaborative/cloud editing.

These are valuable, but each expands the executable-code, package-management, UI, or support surface. The beta should establish that Lean checking and interactive proving are reliable on a phone first.

## 9. Immediate next actions

1. Complete M3.1's top app bar, anchored Files/More menus, extensible navigation drawer, explicit Save/Save As/Close workflows, and inline-search X control.
2. Implement and validate the Open workspace flow for Recent/My Projects, safe SAF project archive/folder import, and standalone/add-to-project Lean-file import into stable internal workspaces.
3. Complete and device-validate Run/Play save-build-run behavior plus adaptive, keyboard, accessibility, recreation, cancellation, and orphan-process acceptance.
4. Only after M3.1 exits, begin M4 by connecting editor document changes to `LeanLspService`, preserving generation-aware reconnection, one reader, stale-diagnostic rejection, and restart controls.
5. Add live version-filtered diagnostics and the first cursor-synchronized goals/messages surface without weakening the completed M3/M3.1 recovery and file-workflow behavior.
6. Convert M1.6 prototypes into release configuration: pin the independent-pack public key, add download/status UI if needed, and validate the signed AAB through Play Console/bundletool.
7. Add API-29 and current-Android physical/emulator coverage while retaining the offline M2 lifecycle and M1 conformance/performance cases.
8. Preserve and revisit ADR 0001 if API/device coverage produces evidence against the accepted process/runtime boundary.

## 10. Reference material

- [Android 10 behavior changes: execution from the writable app home is prohibited](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Lean 4 upstream repository and build documentation](https://github.com/leanprover/lean4)
- [Lean toolchain contents](https://lean-lang.org/doc/reference/latest/Build-Tools-and-Distribution/)
- [Lake command-line, environment, build, and language-server documentation](https://lean-lang.org/doc/reference/latest/Build-Tools-and-Distribution/Lake/)
- [Lean server protocol overview](https://lean-lang.org/doc/api/Lean/Server/ProtocolOverview.html)
- [Android App Bundle format and asset packs](https://developer.android.com/guide/app-bundle/app-bundle-format)
