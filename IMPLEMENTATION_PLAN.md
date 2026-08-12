# Lean4Android implementation plan

Status: proposed roadmap  
Date: 2026-08-11

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

The broad architecture in `plan.md` is sound, but the following feasibility spike is the real first milestone.

### 2.1 Executable placement

Do not copy native executables to `filesDir` and run them. Apps targeting Android 10/API 29 or later cannot `execve()` files from their writable app home because of Android's W^X policy. Package each executable as an ABI-specific native artifact in the APK/AAB and execute it from the installed, read-only native library directory (`ApplicationInfo.nativeLibraryDir`). If Android packaging requires shared-library naming, package command executables with `.so` filenames and preserve their executable ELF entry point.

Writable data remains elsewhere:

```text
nativeLibraryDir/                 read-only, installed by Android
  liblean_exe.so
  liblake_exe.so
  required runtime .so files

noBackupFilesDir/toolchains/<id>/ writable data, restored from signed assets
  lib/lean/                       .olean, .ilean, sources, headers
  share/

filesDir/projects/<project-id>/   writable project workspaces
  lakefile.toml
  lean-toolchain
  lake-manifest.json
  Main.lean
  .lake/

cacheDir/                         temporary extraction/log files
```

The spike must prove that a packaged executable can locate the data sysroot outside its own directory. If Lean or Lake assumes a colocated distribution, patch upstream path discovery or launch with an explicit `LEAN_SYSROOT`; do not duplicate writable executables.

### 2.2 Android cross-build

Lean is bootstrapped, so this is not just a normal one-pass CMake cross-compile. Use a pinned Lean release and NDK version and split the build into:

1. a host build that runs the bootstrap compiler and generates C;
2. an Android `arm64-v8a` target build using the NDK Clang toolchain and Bionic;
3. assembly of a relocatable Android sysroot containing the target executables, runtime libraries, standard `.olean`/`.ilean` files, sources needed by the server, and licenses; and
4. device tests that catch accidental glibc, host-architecture, absolute-path, and unavailable-system-command dependencies.

Keep all Lean/NDK patches in a small, reviewable patch series. The intended build should run in CI from clean checkouts and emit a versioned manifest with upstream commit, NDK version, ABI, minimum API, hashes, licenses, and file list.

### 2.3 Runtime scope

Prove these independently, in this order:

- `lean --version` starts on a physical arm64 device.
- `lean Main.lean` accepts a theorem and reports an invalid theorem.
- `lean --server` completes LSP initialization and publishes diagnostics.
- Lake can read a local project and run `lake lean Main.lean` and `lake build` without network access.
- cancellation terminates the server and all child processes.
- the same operations work after a cold restart and with paths containing spaces and non-ASCII characters.

`lake build` may invoke a C compiler for executable or native targets. Treat proof checking and Lean library builds as the MVP. Shipping an NDK-based `leanc` workflow is a separate capability and must not block editor/LSP delivery.

### 2.4 Feasibility exit criteria

Continue to the product build only when a test APK passes the above checks on the oldest supported API and a current Android release, remains functional offline, and records peak RSS, cold-start time, first-diagnostic latency, and installed size. Initial targets are API 29+, arm64 only, under 3 seconds for first diagnostics on a small Std-only file on the reference phone, and no orphaned Lean processes after cancellation. Performance numbers are budgets to measure and revise, not promises.

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

### 3.2 Process supervision

Implement one component as the only way to start native tools. It must:

- use absolute executable and working-directory paths;
- construct a minimal deterministic environment (`LEAN_SYSROOT`, `LAKE_HOME`, `LEAN_PATH`, `PATH`, and library search path only where needed);
- stream stdout/stderr concurrently to avoid deadlock;
- support timeouts, explicit cancellation, process-tree cleanup, and one LSP server per open project;
- serialize mutating Lake operations per project while allowing safe reads;
- cap retained logs and report exit code/signal separately from Lean diagnostics; and
- redact app-private absolute paths from exported logs.

No command is assembled through a shell. Arguments are always a list, and user text is never interpreted as a command.

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

### 4.2 Offline dependency policy

The initial product supports:

- the bundled Lean core/Std libraries;
- app-produced, version-matched library packs containing source plus prebuilt Lean artifacts; and
- local project dependencies already contained in an imported project, when they require no external tools.

Network-based `lake update`, arbitrary Git dependencies, post-install native plugins, and packages with external build scripts are explicitly unsupported at first. Lake can depend on tools such as Git, tar, and curl for those workflows, and arbitrary packages may execute build logic. Add them only with a security model and explicit UI.

### 4.3 Mathlib pack

Build Mathlib in CI against the exact Android Lean toolchain. A pack contains a signed manifest, licenses, source files needed for navigation, `.olean` and `.ilean` artifacts, and any proven-compatible runtime data. Test `import Mathlib`, goals, hover, definition navigation, and a representative tactic suite on a physical device.

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

### M1 — Android Lean feasibility spike (2–6 weeks; highest uncertainty)

- Produce the arm64 Android Lean runtime/toolchain.
- Package executable code in the APK-native location and data in a versioned sysroot.
- Build the process supervisor test screen.
- Pass all checks in section 2.3 on physical devices and publish measurements.

Exit: a fresh offline install checks valid/invalid Lean files and completes an LSP handshake. If this fails, decide on launcher/JNI before continuing.

### M2 — Project runner (2 weeks)

- Add toolchain installer/health check and project templates.
- Implement create/list/open, atomic save, `lake lean`, and supported `lake build`.
- Add structured job output, cancellation, error states, and basic import/export.

Exit: an instrumentation test creates a two-module project, catches an error, fixes it, builds it, exports it, deletes it, and reimports it offline.

### M3 — Usable editor (3–5 weeks)

- Complete the editor bake-off and record the choice.
- Add file tree, tabs, dirty state, search, syntax highlighting, undo/redo, rename/delete, and recovery after activity/process recreation.
- Support phone, tablet, portrait/landscape, hardware keyboard, and accessibility basics.

Exit: a user can edit the two-module sample without losing changes across rotation or forced activity recreation.

### M4 — Interactive Lean experience (4–6 weeks)

- Implement LSP lifecycle, document sync, live diagnostics, hover, completion, and go-to-definition.
- Add a cursor-synchronized goals/messages pane using the pinned Lean server protocol.
- Add crash recovery, stale-response handling, progress, and server restart controls.

Exit: automated protocol tests plus a device scenario demonstrate correct diagnostics/goals during rapid edits, save, file switch, and server restart.

### M5 — Mathlib beta (duration determined by size/performance spike)

- Produce and verify a version-matched Mathlib pack.
- Add pack install/remove/status UI and low-storage recovery.
- Profile memory, import latency, goal latency, and thermal behavior on a mid-range device.

Exit: representative Mathlib files work offline without process death, and the distribution method meets store and license requirements.

### M6 — Hardening and beta release (3–5 weeks)

- Threat-model imported ZIPs/projects, WebView bridge, native processes, and package manifests.
- Run compatibility, soak, cancellation, corruption, low-storage, and process-death tests.
- Add crash reporting with opt-in/privacy controls, onboarding, licenses, backup policy, and a support bundle exporter.
- Publish known limitations and supported Lean/package versions.

Exit: signed beta passes the release test matrix with no critical data-loss, sandbox-escape, startup, or orphan-process bugs.

## 6. Test strategy

### Host unit tests

- JSON-RPC framing with fragmented/coalesced UTF-8 messages and malformed headers.
- document-version and edit-offset conversion, especially UTF-16 LSP positions;
- environment construction and command argument handling;
- manifest/hash/signature validation;
- project path validation and hostile ZIP fixtures; and
- state restoration and migration logic.

### Android integration tests

- native executable discovery and ABI/API compatibility;
- stdin/stdout/stderr backpressure and cancellation;
- LSP initialize/edit/diagnostics/shutdown transcripts;
- cold install, upgrade, corrupted toolchain, and interrupted pack install;
- low storage, app backgrounding, activity recreation, and app process death; and
- IME, Unicode, hardware keyboard, TalkBack, and large source files.

### Toolchain conformance suite

Maintain a small corpus containing successful proofs, syntax/type errors, multi-module imports, Unicode identifiers, macros, tactics, `#check`, and `#eval`. Run it on desktop Lean and Android Lean and compare normalized outcomes. Add Mathlib cases only to the Mathlib lane.

CI should build and unit-test every change, build the pinned toolchain from scratch on scheduled/release jobs, run emulator smoke tests, and gate releases on physical-device tests. Sanitizer builds may run on host even when unavailable in the Android production configuration.

## 7. Risks and explicit mitigations

| Risk | Consequence | Mitigation / decision gate |
|---|---|---|
| Lean bootstrap or runtime assumes desktop Unix | No viable binary | M1 first; isolate patches and upstream them where possible |
| Android executable restrictions | Process launch fails on modern devices | Execute only APK-installed code; physical API-level matrix; JNI fallback ADR |
| Lean/Lake invokes missing Unix tools | Builds or dependencies fail | Constrain v1 commands; audit subprocesses; expose capabilities, not a terminal |
| LSP/Mathlib exceeds mobile memory | OS kills server or UI stalls | Measure RSS early; one server/project; bounded caches; explicit server stop |
| Toolchain/Mathlib version mismatch | Invalid artifacts or confusing errors | Immutable version IDs and signed compatibility manifests |
| Package size is too large | Store/install failure | Separate data packs, ABI splits/AAB, measure compressed and installed sizes |
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

1. Choose and record a pinned Lean release, NDK release, minimum/target API, Kotlin/AGP versions, application ID, and reference arm64 devices.
2. Create `toolchain/README.md`, the build container, manifest schema, and a tiny conformance corpus.
3. Map Lean's host/target bootstrap steps and enumerate every target ELF dependency with `readelf` in CI.
4. Build the M1 test APK that launches from `nativeLibraryDir`, with no editor work yet.
5. Capture success/failure and measurements in an ADR; only then scaffold the full project/editor modules.

## 10. Reference material

- [Android 10 behavior changes: execution from the writable app home is prohibited](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Lean 4 upstream repository and build documentation](https://github.com/leanprover/lean4)
- [Lean toolchain contents](https://lean-lang.org/doc/reference/latest/Build-Tools-and-Distribution/)
- [Lake command-line, environment, build, and language-server documentation](https://lean-lang.org/doc/reference/latest/Build-Tools-and-Distribution/Lake/)
- [Lean server protocol overview](https://lean-lang.org/doc/api/Lean/Server/ProtocolOverview.html)
- [Android App Bundle format and asset packs](https://developer.android.com/guide/app-bundle/app-bundle-format)

