# M3.3 user-facing project export requirements

Status: accepted requirements on 2026-08-14. M3.3 is a documentation-only planned milestone and must complete before M4 begins. The existing repository export primitive is not evidence of a completed user workflow.

## User entry point

The left navigation drawer contains an accessible **Export project** destination after the project actions and before global Settings. It exports the active app-managed project. It is disabled while a conflicting project mutation or build owns the project, has a clear accessible name and enabled state, and closes the drawer when activated.

Export launches Android's Storage Access Framework `CreateDocument` contract with a portable ZIP MIME type and a sanitized default name such as `<project-name>.lean4android.zip`. The system provider owns destination selection and overwrite confirmation. Lean4Android requests no broad storage permission and never exposes its `/data/user/...` path.

## Dirty-buffer semantics

An export must never silently omit or persist unsaved work. If any open buffer in the active project is dirty, show:

- **Save and Export** — atomically save every dirty buffer, then export the resulting coherent project;
- **Export saved version** — leave editor buffers dirty and export only the last durable project files; or
- **Cancel** — leave all state unchanged and do not open the destination picker.

Save failure prevents export and identifies the affected file. Export itself does not close tabs, switch projects, mark buffers saved, build, run, or mutate the internal project.

## Portable archive contract

The archive contains only portable inputs required to recreate the supported project:

- `.lean` sources and other validated regular project inputs supported by the offline workflow;
- `lakefile.toml`;
- the pinned `lean-toolchain` declaration;
- `.lean4android-project` or a versioned portable replacement needed for safe reimport; and
- applicable project manifest/configuration files that contain no device-private identity or path.

The archive excludes:

- `.lake/` build artifacts and downloaded package state;
- editor recovery snapshots, application preferences, caches, staging, and temporary files;
- sockets, devices, symbolic/hard links, or non-regular files;
- logs, diagnostics bundles, provider URIs, randomized APK/native-library paths, and host/device absolute paths;
- signing material, credentials, tokens, and other secrets; and
- toolchain/runtime files already supplied and verified by the app.

Archive entry paths use normalized forward-slash project-relative names, deterministic ordering, and no absolute or traversal components. The format/schema and supported toolchain identity are explicit enough for import to reject incompatible archives readably. Timestamps and compression details are not project identity.

## Streaming, limits, and failure behavior

Export walks without following links and revalidates containment. Use the same supported project limits as import unless a separately documented lower export limit applies: at most 10,000 entries, at most 64 MiB per regular file, and at most 256 MiB aggregate uncompressed bytes. ZIP generation streams with bounded memory; it must not materialize the full archive in RAM.

The preferred flow streams from a validated app-owned snapshot/staging view to the provider so concurrent saves cannot create a mixed archive. App-owned staging is removed without following links after success, cancellation, Activity/process interruption recovery, or failure. If provider output fails after document creation, attempt to delete that exact partial document when the provider supports it; otherwise report that a partial destination may remain and never claim success.

Do not persist the destination URI as project identity or a live synchronization relationship. Export is a point-in-time copy. The user may export repeatedly to different providers, including Downloads, removable storage, or cloud-backed documents, without changing where Lake runs.

## Security and lifecycle boundary

- No Lean, Lake, shell, Git, network, or project build process is needed to export.
- Do not resolve or follow project links during traversal or cleanup.
- Do not include a live dependency outside the validated app-managed project/package boundary.
- Cancellation and Activity recreation must leave the internal project usable and all dirty/saved states truthful.
- Provider absence, permission loss, zero-byte writes, insufficient space, disconnects, and user cancellation are ordinary recoverable errors.
- Exported metadata must not broaden the supported workflow on reimport; normal import validation remains authoritative.

## Validation and exit evidence

Host tests cover deterministic archive membership/order, byte-identical source/config contents, limits, path containment, link rejection, exclusion rules, staging cleanup, and injected read/write failure. Android tests cover `CreateDocument` result handling, cancellation, dirty choices, recreation, and provider failure where practical.

Physical API-33 acceptance must:

1. export a clean multi-module project from the drawer to a real SAF provider;
2. exercise each dirty-buffer choice and verify the resulting internal/exported bytes;
3. cancel the picker without changing project/editor state;
4. reimport the exported ZIP under a fresh internal identity;
5. build and run the reimported project fully offline with equivalent expected output;
6. inspect the ZIP for required and forbidden entries;
7. cover tablet portrait, forced landscape, and compact layout access to the drawer action;
8. check recovery/cleanup after an interrupted or failed export; and
9. prove no exact Lean/Lake child remains and restore all device/provider fixtures and display/network state.
