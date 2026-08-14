# M3.1 Open workspace and external-file requirements

Status: accepted requirements on 2026-08-14. This specification is part of M3.1 and must be implemented before M4 begins.

## Decision

The Files menu's **Open** command opens a full-screen **Open workspace** surface. It is organized around what the user wants to open, not ambiguous Android storage locations. Lean4Android does not present separate Internal storage, App storage, and Home entries.

Active Lean/Lake workspaces remain app-managed copies under `filesDir/projects/<project-id>`. Android's Storage Access Framework (SAF) is an import/export boundary, not a live Lake workspace. This preserves stable filesystem paths, ordinary filesystem semantics, atomic saves, project validation, offline behavior, and predictable process cleanup when a document provider represents cloud, removable, or virtual storage.

## Open workspace destinations

The full-screen surface contains these primary destinations:

1. **Recent** — recently opened app-managed projects and standalone-file scratch projects, with enough path/project context to distinguish duplicate names.
2. **My Projects** — all valid projects managed inside Lean4Android, with search/sort when the list becomes large.
3. **Import Project Archive** — choose a supported project ZIP through Android's file picker and import it through the existing bounded, traversal-safe project importer.
4. **Import Project Folder** — choose a directory through Android's folder picker, validate and copy it through staging into a new app-managed project.
5. **Open Lean File** — choose one `.lean` document through Android's file picker, then choose **Open as standalone** or **Add to project**.
6. **New Project** — create a project from an app-owned, pinned-toolchain template.

Back returns to the editor without changing the current workspace. Selecting an existing project opens it directly. Import/create operations show progress, remain cancellable before activation, and report validation failures without replacing the current project.

## Existing app-managed projects

**My Projects** and **Recent** open only validated internal projects. Each item shows a display name plus useful secondary context such as the project-relative entry file, last-opened time, and toolchain compatibility. Missing, corrupt, or incompatible projects are not silently repaired or executed; they receive an explicit diagnostic/recovery path.

Recent entries are stable project/file identities rather than raw display-name strings. Removing a recent entry does not delete its project.

## Import Project Archive

Use SAF `ACTION_OPEN_DOCUMENT` to select one archive. Copy the selected content into bounded staging and use the existing safe ZIP import boundary. Preserve these checks:

- project schema and pinned `lean-toolchain` compatibility;
- maximum entry count, total uncompressed size, individual size, and compression-ratio limits;
- traversal, absolute-path, duplicate/case-folded-name, symlink, and unsupported file-type rejection;
- rejection of unsupported Git/network dependencies, arbitrary scripts, native/executable targets, and downloaded executable code; and
- validation before atomic activation, with staging cleanup that never follows links.

Import creates a new internal project identity. A collision requires Rename imported project, Replace with confirmation and recovery protection, or Cancel; it never overwrites implicitly.

## Import Project Folder

Use SAF `ACTION_OPEN_DOCUMENT_TREE` to let the user select a directory. Treat the returned tree as an import source only:

1. enumerate and validate with file-count and total/per-file byte limits;
2. reject unsupported or ambiguous content before activation;
3. stream files into a staging sibling without trusting provider display names as filesystem paths;
4. generate or validate project metadata and the pinned toolchain boundary; and
5. atomically activate the internal project only after complete validation.

Do not run Lean/Lake against `content://` URIs, provider-backed pseudo paths, removable storage, or cloud documents. Persistable URI permission may support an explicit future **Reimport from source** feature, but it is not project identity and import correctness cannot depend on the provider remaining available.

## Open Lean File

Use SAF `ACTION_OPEN_DOCUMENT` restricted to openable Lean/text documents. After selecting one `.lean` file, present:

### Open as standalone

- Copy the document into a newly created scratch project with an app-generated unique identity.
- Generate the minimal supported Lake project metadata and pinned `lean-toolchain` declaration.
- Preserve the original filename when it is a valid Lean source path; otherwise request a valid destination.
- Open the imported copy and clearly label the workspace as a scratch project.
- Do not imply that sibling files were imported. If imports cannot resolve, explain that the user should import the containing project folder/archive.

Scratch projects are real app-managed projects: they participate in Recent, recovery, Save, Save As, Run, export, and deletion. They are removed on app uninstall unless exported.

### Add to project

- Let the user choose an existing compatible project and a validated project-relative destination.
- Default to the selected document's filename, but require resolution of collisions.
- Copy atomically, update the project tree/session snapshot, and open the new internal file.
- Leave the external source unchanged.

## Save, Save As, and export boundary

- **Save** writes the active internal project file.
- **Save As** creates another internal project file and retains the original.
- **Export Project** writes a portable project archive through SAF. Its user-facing drawer workflow, dirty-buffer behavior, archive contract, limits, cleanup, and acceptance are specified by `../project/M3_3_PROJECT_EXPORT_REQUIREMENTS.md` and remain pending until M3.3 completes.
- A future **Export Copy** may write one Lean file through SAF.

Do not use Save As to imply a durable link to an external provider document. Imported files are copies. The UI warns that app-managed projects and scratch projects are removed on uninstall and offers export guidance without interrupting ordinary editing.

## Security and lifecycle requirements

- No broad storage permission or legacy raw-storage mode is introduced.
- Provider URIs and host-specific absolute paths are not persisted in exported project metadata or logs.
- Import cancellation/failure leaves the current workspace unchanged and no partially activated project.
- Activity/process recreation restores the Open workspace navigation state or safely returns to the prior editor; it never repeats an import without confirmation.
- Imported content cannot broaden the supported executable/network capability boundary.
- Project services and native children are stopped/rebound consistently when switching projects, with exact no-orphan validation.

## Acceptance criteria

Host tests cover recent identity, archive/folder bounds, collisions, cancellation, standalone scratch generation, add-to-project path validation, unsupported workflows, atomic activation, and recovery cleanup. Android/device tests cover:

- opening Recent and My Projects;
- importing a valid and hostile archive;
- importing a folder through SAF;
- opening a single Lean file both as standalone and into an existing project;
- unresolved sibling-import guidance;
- switching projects with dirty buffers and running the imported project offline;
- picker cancellation, Activity recreation, process recreation, and unavailable-provider handling;
- export guidance for uninstall-sensitive app storage; and
- no residual Lean/Lake process after project switches, failure, cancellation, or close.
