# M5 Mathlib integration plan

Status: Phase B active — producing Android2-compatible Mathlib artifacts

Milestone: M5

Depends on: the pinned Lean 4.32.1 Android runtime, M1.6's manifest-driven delivery work, and the M4 editor/LSP baseline

Last updated: 2026-08-15

## Current checkpoint and immediate next actions

Android1 is the accepted product line through M4.6. M5 uses the isolated
`lean-4.32.1-android2` runtime and `org.lean4android.app.android2candidate`; it
does not promote or rebuild Android1.

The Android2 Core/Std distribution and isolated candidate APK are packaged and
host-audited. The APK has also passed direct Run, Lean Server readiness, focused
LSP conformance, and forced-cleanup checks on the API-33 reference tablet. This
is focused compatibility evidence, not yet the complete M0-M4 device matrix.

The official Mathlib cache is incompatible with both Android runtimes. Matching
Lean display version and commit are insufficient: the Android2 runtime rejects
the official `Mathlib.Data.Nat.Prime.Basic` artifact with `incompatible header`.
All copied incompatible Mathlib data has been removed from both apps on the
tablet; their Core/Std installations and projects remain intact.

A clean, pinned Android2 producer is now rebuilding the targeted compatibility
gate `Mathlib.Data.Nat.Prime.Basic` with four jobs. At the last documentation
checkpoint it had produced 79 `.olean` files after 2,738 seconds. Preserve the
incremental producer tree and monitor:

```shell
tail -f toolchain/output/mathlib-android2-basic-build.log
```

If the host crashes, rerun the same command below. The wrapper locks the
producer, audits interrupted outputs, removes only proven temporary/damaged
module facets, and resumes healthy work with `--rehash --no-cache`:

```shell
LEAN4ANDROID_JOBS=4 \
LEAN4ANDROID_PROGRESS_INTERVAL_SECONDS=30 \
LEAN4ANDROID_MATHLIB_TARGET=Mathlib.Data.Nat.Prime.Basic \
LEAN4ANDROID_LOG_FILE="$PWD/toolchain/output/mathlib-android2-basic-build.log" \
  mathlib/scripts/build-android2-artifacts.sh
```

Do not start a second producer while the current process is alive. After the
targeted build exits successfully: audit its generated facets, headers, and
hashes; stream only the rebuilt compatible slice to Android2; prove a real
tablet import; and check for residual package-owned Lean/Lake processes. Only
then start the full `Mathlib` target. The early full-build estimate is 8,654
jobs / 8,651 `.olean` files and roughly 75-100 hours (central estimate 85-90
hours) at the observed early `/mnt/d` rate; revise this estimate as the sample
grows.

## 1. Objective

Provide a version-matched Mathlib installation that works fully offline for checking, building, Lean Server features, source navigation, and the supported Run workflow without making Mathlib part of the base APK or enabling arbitrary package downloads.

M5 is primarily a feasibility and integration gate. It must answer these questions with measured physical-device evidence:

1. Which Mathlib source and compiled artifact facets are required by `lake lean`, `lake build`, `lake serve`, navigation, tactics, and supported interpretation?
2. What are the compressed download, expanded installation, update/rollback headroom, installation time, import latency, memory use, and thermal cost?
3. Can one data-only pack format safely serve both Play and independent distribution channels?
4. Can projects opt into the installed pack through typed configuration without allowing Git/network resolution or executable package logic?

## 2. Product and security boundary

- Support one pinned Mathlib revision for one pinned Lean/toolchain identity at a time.
- Treat Mathlib as an optional, immutable, signed **data/library pack**. It may contain Lean source, metadata, licenses, and prebuilt Lean artifacts, but no native executables or downloaded executable plugins.
- Keep Lean/Lake executables in the APK-controlled native-library directory and the core/Std sysroot under the existing verified toolchain lifecycle.
- Do not ship Elan, run `lake update`, clone Git repositories, resolve packages from the network, accept arbitrary registries, or execute package build scripts on-device.
- Keep projects in app-managed storage. Project selection of Mathlib is typed metadata that generates the supported Lake configuration; it is not permission to edit unrestricted `lakefile.lean` or run against a provider URI.
- A missing, corrupt, incompatible, or partially installed pack must disable Mathlib cleanly without damaging Core/Std, projects, or the last known-good pack.

## 3. Intended architecture

The integration has four distinct layers:

1. **Reproducible producer** — pins the Lean identity, Mathlib revision, source inputs, build options, and packaging tools; produces a complete candidate directory and provenance record in CI or an audited release environment.
2. **Dependency-pack format** — describes identity, compatibility, licenses, file paths, sizes, and hashes in a signed manifest. Paths are relative, unique, case-safe, bounded, and restricted to declared data facets.
3. **Shared streaming installer** — adapts Play-delivered assets and an independently obtained signed archive to one verified staging/activation state machine.
4. **Project integration** — exposes installed-pack status and an explicit project dependency choice, then generates consistent Lake/build/LSP configuration from the same typed model.

Mathlib should have its own immutable pack root and marker rather than being copied into every project or merged destructively into the Core/Std installation. A conceptual identity is:

```text
mathlib-<mathlib-revision>-lean-4.32.1-android2
```

The final spelling and schema belong in a versioned manifest and must not depend on an APK's randomized native-library path.

### 3.1 Storage model and open questions

Storage is a first-class M5 feasibility gate, not an implementation detail. The current filtered Core/Std runtime occupies about 2.19 GB expanded on the reference device. Phase A measured the official-cache completeness-first tree at 122,207 records / 6,955,114,198 logical bytes before filtering. It included 8,651 `.olean` files (1,882,088,688 bytes), 8,639 `.olean.private` files (3,685,716,976 bytes), 286,315,370 bytes of `.ilean`, 103,350,296 bytes of `.olean.server`, 274,036,040 bytes of `.ir`, and 486,266,715 bytes of generated C. The source inventory is 9,778 files / 113,350,974 bytes. These measurements establish scale, but not the final Android2 pack size: all compiled facets must be regenerated against Android2 and Phase B must prove which facets may safely be omitted.

Keep these quantities separate in every report:

- source/input checkout size in the producer environment;
- unfiltered and filtered expanded pack bytes and filesystem file-count overhead;
- compressed independent-pack and Play-delivery bytes;
- active installed Core/Std, Mathlib, project, and Lake-cache bytes;
- installer staging bytes and bounded stream buffers;
- rollback bytes while the previous healthy Mathlib pack is retained; and
- minimum free space before installation versus steady-state free space afterward.

Do not estimate safe installation space from compressed download size. A replacement may temporarily require both a complete staging tree and the previous active pack in addition to the installed Core/Std runtime and user projects. The installer must calculate requirements from authenticated manifest values, include a safety margin for filesystem/accounting variance, and show the user download size, installed size, and required free space before starting.

Install Mathlib once into a shared immutable pack root. Projects record a compatible pack identity and must not receive private copies. Project export records the requirement rather than embedding Mathlib. Lake caches remain separately attributable and removable/rebuildable without deleting the pack or source files.

### 3.2 Runtime memory model and hypotheses

Installing Mathlib does not by itself mean that Lean or the editor reads every Mathlib file into memory. The working hypothesis is that Lean loads environments and artifacts reachable from the modules a document imports. Import breadth therefore matters: a narrow module import and `import Mathlib` may have substantially different startup time and resident memory. This behavior must be measured with the pinned Android runtime rather than treated as a guarantee.

Peak application use is the combination of several independently measured consumers:

- the Android app, Compose editor, open text buffers, rendered diagnostics/results, and recovery state;
- the retained `lake serve` child, imported Lean environments, open documents, elaboration state, and server caches;
- a one-shot `lake lean`, build, or Run child and its imported environments;
- Android process/runtime overhead and shared native mappings; and
- temporary installer or file-navigation work if it overlaps an active server.

Report per-process PSS and RSS where available, plus aggregate observations, and state their limitations. Aggregate RSS double-counts shared pages and must not be presented as exclusive memory. Measure initial peak, post-idle retained memory, repeated-edit growth, post-file-switch behavior, server restart recovery, and whether the OS kills either the child or app under realistic pressure.

The first implementation should preserve these controls:

- retain only one Lean Server for the active project and never create one per file or tab;
- open Mathlib source files and populate navigation results on demand, without constructing an in-memory editor model or eagerly synchronized LSP document for the whole Mathlib tree;
- keep open buffers, diagnostics, completion/reference results, hover content, output, and history bounded;
- stop or serialize one-shot Build/Run jobs against the LSP if measured concurrent peaks are unsafe, with truthful UI state instead of relying on Android to reclaim a child;
- provide deterministic server restart and cleanup without requiring caches to survive process death; and
- preserve user buffers/recovery independently of Lean Server lifetime.

These are containment mechanisms, not evidence that full Mathlib is viable. M5 must experimentally establish the support envelope.

### 3.3 Import and device support policy

The conformance corpus must compare at least:

- small, targeted Mathlib module imports;
- representative imports from algebra, analysis, data structures, tactics, and other commonly used areas;
- the broad `import Mathlib` aggregator;
- one-file and multi-file projects with repeated edits; and
- cold server start, warm navigation, one-shot checking/building, and any allowed overlap between them.

If broad imports are materially more expensive, the product may document targeted imports as the recommended mobile practice, but broad `import Mathlib` remains an explicit test case and may be called unsupported only through a recorded M5 decision. The app must never silently rewrite a user's imports.

The final M5 report must state measured minimum and recommended conditions, including free storage and device RAM classes, supported Android/API/ABI boundary, expected cold/warm latency, and known import or concurrency limitations. A single high-memory tablet pass is not enough to define the beta support envelope.

## 4. High-level implementation process

### Phase A — Pin and inventory (complete)

Pinned Mathlib v4.32.1 at commit `520045ab…` with eight locked dependencies.
The official cache supplied 8,639 compressed archives totaling 439,158,169
bytes. The complete inventory and provenance baseline are recorded in
[`M5_PHASE_A_BASELINE.md`](M5_PHASE_A_BASELINE.md). One known x86 host-cache
executable and three symlinks were identified and excluded from Android pack
candidates.

1. Select the Mathlib revision that officially targets the pinned Lean revision and record all upstream source hashes and licenses.
2. Build or obtain its dependency closure from pinned, auditable inputs in a network-enabled producer environment. The Android app remains offline and never performs this resolution.
3. Inventory source and every generated facet by module, including `.olean`, `.ilean`, `.olean.private`, `.olean.server`, `.ir`, and any other files actually consumed by the pinned checker, Lake, or server.
4. Record uncompressed size, compressed size, file count, largest files, module count, and license payload before filtering anything.

Output: an unfiltered, reproducible candidate tree and provenance report bound to exact Lean and Mathlib identities.

### Phase B — Establish the minimum complete artifact set (active)

The official release cache failed the Android1 and Android2 compatibility gate.
For Android2, both artifacts report Lean 4.32.1 commit `f054605…`, but the
official Mathlib header begins `olean 02 01` while Android2 `Init.olean` begins
`olean 02 00`; a real narrow tablet import failed in seven seconds. The clean
producer therefore combines host stage1 executables with the audited Android2
`lib/lean` dependency closure and rebuilds every Mathlib artifact against that
exact closure. Facet minimization and broad conformance remain blocked on this
producer compatibility gate.

1. Start from completeness rather than an assumed `.olean`-only package.
2. Run representative host workflows and compare filesystem access/audits to determine which facets each supported capability uses.
3. Package the complete candidate first and run it on Android through direct Lean, `lake lean`, `lake build`, and `lake serve`.
4. Exercise imports across major Mathlib areas, elaboration errors, goals, hover, completion, go-to-definition, references, representative tactics, and supported `#eval`/interpretation cases.
5. Remove a facet only through a separate measured experiment. A smaller pack is accepted only if the full conformance matrix and source/navigation behavior remain intact.

Output: a documented inclusion policy and conformance corpus. Passing `import Mathlib` alone is not sufficient evidence.

### Phase C — Define and produce the signed pack (partial)

The dependency-pack manifest parser/verifier and bounded staging-source
installer exist in `core-toolchain`, with focused hostile path, hash,
executable-content, and symlink tests passing. Reproducible Mathlib pack output,
signing, channel artifacts, and their byte audits remain pending.

1. Extend or specialize the existing manifest model for dependency packs. At minimum, bind:
   - schema version and immutable pack ID;
   - required Lean/toolchain ID and Mathlib revision;
   - pack type and supported capabilities;
   - every relative path, byte count, and SHA-256 digest;
   - total file count and expanded bytes;
   - license/provenance entries; and
   - packaging-tool version or reproducibility metadata.
2. Reject absolute paths, traversal, links, duplicate or case-folded paths, undeclared entries, native executable formats, unsupported schemas, and identity mismatches.
3. Produce both channel artifacts from the same staged tree and manifest:
   - a Play Asset Delivery candidate, preferably on-demand because Mathlib is optional; and
   - an independent ZIP with a detached signature from the offline release key.
4. Audit archive structure, signature, hashes, compression ratios, and absence of native executable content. Measure each artifact rather than inferring its size from the source tree.

Output: byte-audited Play and independent artifacts that represent the same logical pack.

### Phase D — Implement recoverable installation lifecycle (partial)

Bounded streaming from a declared dependency-pack source is implemented and
tested. Atomic activation, rollback, durable status, cancellation recovery,
update/removal coordination, and full on-device lifecycle validation remain
pending.

1. Add a dependency-pack source abstraction or safely generalize the existing `RuntimePayloadSource` boundary without weakening Core/Std validation.
2. Before installation, authenticate the pack metadata, check exact toolchain compatibility, report download and expanded sizes, and preflight enough space for staging plus any retained rollback copy.
3. Stream declared entries into a sibling staging directory with bounded memory while checking path, size, and hash. Never extract first into an additional full-size temporary copy.
4. Verify the complete staged tree, write the installation marker last, and atomically activate it. Preserve the previous healthy version until the replacement is proven active.
5. On cancellation, process death, low space, corrupt input, or activation failure, recover on the next start by removing only identified incomplete staging state or restoring the known-good version.
6. Removal must stop affected builds/LSP sessions, detach projects or mark their dependency unavailable, delete only the selected immutable pack without following links, and leave Core/Std operational.

Output: an idempotent install/update/remove/recovery state machine shared by both delivery channels.

### Phase E — Add status and project selection UI (planned)

1. Add a Mathlib/dependency-pack screen showing Not installed, Downloading/Importing, Verifying, Installed, Incompatible, Corrupt, Failed, and Removal states with version and storage information.
2. Provide install/import, retry, verify, and remove actions with truthful progress, cancellation boundaries, and actionable errors.
3. Add installed signed-pack selection to typed project configuration. Generate a supported Lake dependency mapping from this selection and use the same effective configuration for builds, Run, and `lake serve`.
4. Serialize configuration changes with project saves, active jobs, and LSP lifecycle. Restart/reopen the workspace only after durable configuration succeeds; preserve dirty editor buffers and recovery snapshots.
5. A project requiring unavailable Mathlib must remain editable and exportable while Build/Run/LSP reports a clear dependency error. It must never silently attempt network resolution.
6. Decide archive portability explicitly: project export records the compatible pack requirement but does not duplicate the large installed Mathlib payload. Reimport reports whether the required pack is installed.

Output: a user can install Mathlib once, enable it per project, work offline, and understand missing/incompatible states.

### Phase F — Validate feasibility and choose the release shape (planned)

Run the complete matrix on the API-33 reference tablet, at least one representative mid-range arm64/API-29+ device, and the current Android target:

- fresh install, update, interrupted install, corrupted entry, bad signature, wrong Lean identity, insufficient-space preflight, cancellation, rollback, removal, and reinstall;
- offline project creation/import/export with and without Mathlib selected;
- `lake lean`, `lake build`, Run, and `lake serve` with representative Mathlib imports;
- diagnostics, goals, expected types, hover, completion, definition, references, source access, server restart, Activity/process recreation, and no stale document results;
- cold and warm first diagnostic, goal, build, and Run latency;
- targeted-module versus broad `import Mathlib` latency and memory behavior;
- Lean Server and one-shot job peak PSS/RSS both separately and, if allowed, concurrently; app/editor memory; shared-mapping caveats; and OS process-death behavior;
- long editing sessions with repeated edits, file switches, navigation into Mathlib source, bounded-result pressure, post-idle retention, and checks for monotonic memory growth;
- sustained-edit/build thermal behavior, throttling, battery impact where measurable, and storage/cache growth over repeated workflows;
- exact teardown checks proving that cancellation, project switching, pack removal, and app shutdown leave no Lean/Lake child.

Record compressed delivery bytes, expanded pack bytes, staging/rollback peak requirements, install/verify time, and runtime measurements separately. Do not describe aggregate RSS as exclusive memory and do not use a warm result as a cold-start claim.

Output: an M5 decision report selecting the shipped channel/configuration or identifying a measured blocker and the narrowest follow-up experiment.

## 5. Suggested implementation slices

The work should land in recoverable, independently reviewable slices. Current
status is shown below:

1. **Complete:** pins, provenance, official-cache inventory, and license audit.
2. **Active:** Android2-compatible producer, Android conformance corpus, and complete-facet proof.
3. **Partial:** dependency-pack manifest/parser/verifier and hostile fixtures.
4. **Pending:** reproducible pack producers for independent and Play channels.
5. **Partial:** streaming staging exists; activation/removal/recovery lifecycle remains.
6. **Pending:** status/progress/storage UI.
7. **Pending:** typed per-project selection and consistent Lake/LSP wiring.
8. **Pending:** full host, APK, migration, offline, and physical-device acceptance.
9. **Pending:** final size/performance/security decision and release documentation.

Each lengthy production or device-validation step must leave a checkpoint in `IMPLEMENTATION_HISTORY.md` with the trustworthy artifact paths and hashes, live-process state, failures, and exact resume command.

## 6. Acceptance gates

M5 is complete only when all of the following hold:

- the Mathlib pack is reproducible from pinned inputs and its licenses/provenance are complete;
- every installed byte is declared and verified, and no downloaded/imported native executable content is accepted;
- both delivery sources feed the same safe installer contract, or one channel is explicitly deferred with a documented release consequence;
- fresh install, update, interruption, corruption, cancellation, rollback, removal, and insufficient-space behavior preserve a healthy Core/Std installation and user projects;
- project configuration enables Mathlib consistently for checking, building, Run, and Lean Server without Git/network access;
- representative Mathlib checking, tactics, editor intelligence, source navigation, and supported interpretation work offline on physical Android devices;
- measured storage, latency, memory, and thermal behavior fit a documented beta support envelope;
- the release documentation states minimum/recommended free storage and RAM/device conditions plus any import or job-concurrency limitations; and
- all exit paths leave no orphan Lean/Lake processes.

## 7. Explicit decision points

M5 may require a deliberate decision rather than automatic continuation when:

- the complete pack exceeds current Play or independent-channel limits;
- staging plus rollback requires impractical free space;
- server memory or thermal behavior causes repeatable process death;
- required artifacts cannot be made compatible with the pinned Android runtime;
- on-demand Play delivery is unavailable for the chosen release category; or
- a Mathlib workflow requires executable/native package behavior outside the approved product boundary.

Preferred responses are, in order: improve data-only packaging or install strategy; narrow the documented supported Mathlib capability with evidence; investigate a reproducible compatible artifact build; or defer Mathlib. Do not weaken signature, manifest, source-integrity, executable-code, or offline-package boundaries merely to pass the milestone.

Possible evidence-based outcomes include:

- ship the complete optional pack when full workflows fit the support envelope;
- ship it with explicit storage/RAM requirements and preflight warnings;
- recommend targeted imports and serialize LSP versus Build/Run when broad imports or concurrency exceed safe peaks;
- omit a proven-unnecessary data facet only after the complete conformance matrix still passes; or
- defer Mathlib or redesign its data-only packaging when storage, memory, thermal, compatibility, or delivery limits remain unacceptable.

## 8. Related documents

- [`IMPLEMENTATION_PLAN.md`](../../IMPLEMENTATION_PLAN.md), sections 4.3–4.4 and M5
- [`M1_6_DECISION.md`](M1_6_DECISION.md), shared delivery and streaming-installer decision
- [`M1_6_BASELINE.md`](M1_6_BASELINE.md), current core runtime size baseline
- [`M5_PHASE_A_BASELINE.md`](M5_PHASE_A_BASELINE.md), pinned Mathlib inventory and size evidence
- [`mathlib/README.md`](../../mathlib/README.md), producer, monitoring, and crash-recovery commands
- [`PROJECT_CONFIGURATION_DESIGN.md`](../project/PROJECT_CONFIGURATION_DESIGN.md), typed dependency selection and unsupported package workflows
- [`MVP_AND_REBUILDING.md`](../../MVP_AND_REBUILDING.md), build, recovery, APK, and device-validation procedures
- [`docs/adr/0001-android-lean-process-and-runtime-boundary.md`](../adr/0001-android-lean-process-and-runtime-boundary.md), executable-code and child-process boundary
