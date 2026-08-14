# Project configuration design

Status: accepted design direction on 2026-08-14. This document defines the safe user-facing subset of Lake project configuration for Lean4Android; it does not itself authorize broader executable, network, or package-management capabilities.

## Current behavior

Lean4Android creates app-managed projects with a generated `lakefile.toml`, pinned `lean-toolchain`, internal `.lean4android-project` metadata, and ordinary `.lean` sources. Imported projects must contain compatible metadata and a `lakefile.toml`. The editor currently exposes only `.lean` sources, while repository validation rejects known Git/network dependency forms, executable targets, external libraries, and native targets before Lake runs.

The bundled Android toolchain is authoritative. A project's requested toolchain is validated rather than installed through Elan, and Lake runs only inside a validated app-private workspace. SAF remains an import/export boundary.

## Desktop Lean and Lake model

A normal Lake workspace contains `lean-toolchain`, either `lakefile.toml` or `lakefile.lean`, usually `lake-manifest.json`, source files, and Lake-managed `.lake/` artifacts. The package configuration declares package settings, dependencies, and targets. The TOML form is declarative; `lakefile.lean` can use Lean code and Lake's API during configuration. See the [official Lake reference](https://lean-lang.org/doc/reference/latest/Build-Tools-and-Distribution/Lake/).

Common user configuration includes:

- package name, version, and metadata;
- Lean toolchain version;
- library names, module roots, and source directories;
- default build targets;
- registry, Git, or local-path dependencies;
- Lean compiler and language-server options;
- executable, lint, test, external-library, native, or custom targets; and
- build directories, facets, caching, linker arguments, and other advanced build behavior.

On a regular machine, Elan reads `lean-toolchain` and can download/select the requested executable toolchain. Lake can resolve missing registry or Git dependencies and may execute powerful configuration or build logic. Those desktop capabilities are not automatically safe or available on Android. See the [official Elan reference](https://lean-lang.org/doc/reference/latest/Build-Tools-and-Distribution/Managing-Toolchains-with-Elan/).

## Product decision

Lean4Android should expose project configuration, but the default interface is a typed, capability-aware **Project Settings** hierarchy rather than unrestricted Lake source editing. It is distinct from global app Settings because its values belong to one project and affect that project's builds and language server.

The initial hierarchy is:

```text
Project Settings
├── General
├── Source layout
├── Build
├── Dependencies
├── Lean options
└── Advanced configuration
```

The page structure must remain extensible. Every saved change is validated as one configuration transaction, written atomically, and applied consistently to one-shot builds and `LeanLspService`. A failed validation or reconfiguration preserves the previous working configuration and presents an actionable error.

## Initial supported fields

### General

- Project display name, separate from the stable internal ID.
- Lake package name.
- Package version.

### Toolchain

- Required Lean version and installed compatibility status, initially read-only.
- A clear explanation that editing `lean-toolchain` cannot download or activate another toolchain.

### Source layout

- Lean library name.
- Module roots.
- Source directory when it remains contained inside the project.
- Addition and removal of supported Lean-library targets.

### Build

- Supported default Lean-library targets.
- The source/module used by the app's Run action.
- No executable, external-library, native, script, or arbitrary custom targets.

### Dependencies

- Bundled Core/Std.
- Installed, signed, toolchain-matched data/library packs such as the planned Mathlib pack.
- Validated local project dependencies already contained in app-managed storage and requiring no external tools.
- No URL, Git, registry download, implicit `lake update`, or provider-backed live dependency.

### Lean options

- A small typed allowlist whose value types and effects are understood by the app.
- Options affecting elaboration or server behavior must be supplied consistently to builds and the language server.
- Raw compiler, C compiler, linker, plugin, dynamic-library, or server argument arrays remain unsupported until separately threat-modeled.

## Lake file ownership and advanced editing

The first implementation should generate a normalized `lakefile.toml` from the typed project model. **Advanced configuration** displays the generated TOML read-only, explains unsupported desktop capabilities, and offers validation diagnostics. App-specific metadata must remain outside the Lake file rather than adding unknown fields to it.

A later expert mode may edit `lakefile.toml`, but only after a real structured TOML/Lake configuration parser exists. Save must parse into the supported model, reject unknown or unsupported capabilities, show the effective change, validate against the pinned toolchain, and atomically replace the active configuration with rollback. A regular-expression scan is not an adequate final security boundary.

`lakefile.lean` remains unsupported initially. Because it is executable Lean configuration with access to Lake APIs, accepting it would broaden the trust boundary to arbitrary configuration-time logic. It requires a separate security decision rather than an editor toggle.

## Lifecycle and validation requirements

- Preserve a stable internal project ID when the display/package name changes.
- Validate paths, module roots, target names, duplicate case-folded names, and containment before writing.
- Revalidate the complete supported workflow after every change and import.
- Invalidate or reconfigure Lake's cached configuration only after the new source configuration is durable.
- Serialize configuration changes against builds and LSP startup for the same project.
- Reconnect/reopen M4 documents only after a successful configuration generation changes the effective workspace.
- Preserve unsaved `.lean` buffers and their recovery snapshots during configuration changes.
- Export the effective `lakefile.toml`, `lean-toolchain`, manifest where applicable, configuration metadata, and sources together under the portable archive and SAF behavior defined by `M3_3_PROJECT_EXPORT_REQUIREMENTS.md`.
- Never run Lake against SAF URIs or use a document provider as live project identity.
- Clearly report unsupported toolchain, dependency, target, option, and native-build requirements during import and editing.

Host tests must cover model-to-TOML generation, parse/validation errors, atomic replacement and rollback, path/name rules, supported local dependencies, and rejection of network, executable, native, custom, plugin, and `lakefile.lean` workflows. Device acceptance must cover settings persistence, build/LSP reconfiguration, process/recreation interruption, import/export round-trip, offline behavior, and exact no-orphan cleanup.

## Roadmap boundary

The minimal General, Source layout, Build, and safe Lean-options model should be designed with M4 because roots and server options affect document synchronization and language-server configuration. It need not delay the first live-diagnostics slice if the existing generated configuration remains the only active model.

Signed dependency-pack selection belongs with M5's Mathlib/package work. Registry/Git resolution, arbitrary toolchains, `lakefile.lean`, executable/native/custom targets, and unrestricted raw configuration remain outside the initial product boundary until each has an explicit security, delivery, lifecycle, and physical-device validation plan.
