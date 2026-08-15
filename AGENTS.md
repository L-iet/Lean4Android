# Lean4Android agent instructions

These instructions apply to the entire repository. They complement the general agent rules; the more specific instruction wins if there is a conflict.

## Start every work session from durable state

1. Read (the last few lines of) `IMPLEMENTATION_HISTORY.md`, the relevant milestone and immediate-next-actions sections of `IMPLEMENTATION_PLAN.md`, and the relevant recipe in `MVP_AND_REBUILDING.md` before changing code or starting a long command.
2. Inspect `git status --short`. The worktree may contain user changes or generated recovery state. Preserve unrelated work and never infer that an untracked or modified file is disposable.
3. Treat `IMPLEMENTATION_HISTORY.md` as the project's long-term operational memory. Generated files and old process IDs are not evidence of current state after a machine or WSL crash.
4. Confirm whether a supposedly running build/process actually survived before resuming it. Inspect its output and artifacts, then resume incrementally when safe.

## Keep progress recoverable

The host machine sometimes crashes. Do not let important knowledge exist only in terminal output or chat context.

- Regularly add a dated entry or an explicit `Validation in progress`/checkpoint item to `IMPLEMENTATION_HISTORY.md` when beginning any task, lengthy build, device migration, or multi-step investigation. Do not wait until tasks are done to add to the implementation history. Add to the **end** of the file, not the beginning.
- Update that entry regularly at meaningful boundaries: configuration completed, a new artifact was produced, a failure/root cause was identified, a device state was changed, or the next exact resume command became known. Do not wait until the end to record all progress.
- A checkpoint must state what completed, what is still running (including command, job count, and relevant paths), what failed or remains unknown, what artifacts are trustworthy, and the exact next validation/resume step. Record whether any relevant process remains alive.
- When the work completes, revise the in-progress entry into the completed state rather than leaving contradictory running-state prose. Record implementation, decisions, validation evidence, measurements/artifact hashes when material, device/network state, residual-process checks, and remaining boundary.
- Do not claim success from compilation alone when the milestone requires APK, instrumentation, offline, migration, or physical-device evidence. Distinguish observed results from inferred or pending validation.
- Update `IMPLEMENTATION_PLAN.md`, `MVP_AND_REBUILDING.md`, README files, ADRs, delivery notes, scripts, and inline comments whenever new information changes the roadmap, supported boundary, rebuild procedure, architecture, measurements, or operational facts. Keep future work in the plan and exact historical outcomes in the history.
- Before handing off or stopping, leave the repository documentation sufficient for a new session to continue without reconstructing terminal history.

## Current project boundary

- M0, the API-33 reference-device portion of M1/M1.5, M1.6, and M2 are complete as recorded in the history. Continue from the plan's M3 immediate actions unless the user chooses another task.
- Preserve the visible two-tab Lake project editor and the offline M2 lifecycle as continuous acceptance baselines.
- Initial support remains one pinned Lean toolchain, `arm64-v8a`, API 29+, offline core/Std, and APK-installed executable code. Do not silently broaden this to arbitrary Git/network dependencies, downloaded executable code, native Lake targets, Elan, or a general terminal.
- Respect `docs/adr/0001-android-lean-process-and-runtime-boundary.md`. Reopen the child-process decision only with new device/API evidence.

## Build and source workflow

- Use the checked-in Gradle wrapper, JDK 17, project-local Android SDK, and project-local Gradle state:

  ```shell
  GRADLE_USER_HOME="$PWD/.gradle-user-home" \
    ./gradlew --no-build-cache --no-daemon --console=plain \
    testDebugUnitTest :app:assembleDebug
  ```

- Use `--no-build-cache` for toolchain-bearing APKs. On `/mnt/d`, staging and compression of the multi-gigabyte runtime are slow; lack of recent output is not proof of a hang.
- Run narrow module tests/compilation for quick feedback, then run verification proportional to the changed boundary. Changes crossing Android filesystem, process, metadata, installer, service, or UI boundaries require Android/device validation.
- Follow the change-specific recipes in `MVP_AND_REBUILDING.md`. Do not rebuild the entire Lean toolchain when an audited Kotlin-only or packaging-only change does not require it.
- All upstream Lean changes must remain in the single canonical checked-in patch. The patched checkout's `git diff --no-ext-diff` must match that patch byte-for-byte. Never weaken the source-integrity guard to tolerate drift.
- Keep source pins exact. For ABI, NDK, API, compiler, or linker changes, use a clean/new target work tree or remove only proven architecture-dependent outputs. Audit archive members and final ELF files as AArch64 with the NDK tools.
- Preserve the last known-good audited distribution until its replacement passes assembly, manifest/hash, ELF, dependency, APK, and applicable device checks.
- Production process launches are shell-free argument lists with deterministic environments. UI code must use the typed command/process boundaries and must not reconstruct executable paths or environment maps.

## Crash and interrupted-build recovery

- Prefer incremental diagnosis and recovery. Lean generation has primary and companion side outputs; a crash can leave a `.tmp`, `.olean`, generated C file, object, or archive in an inconsistent combination.
- Identify the exact damaged or missing artifact before deleting anything. Do not broadly clean source distributions, toolchain work trees, Gradle state, or known-good output.
- Do not interrupt an active Lean generator merely because a downstream file is not present yet. Check the process and dependency state first.
- For an interrupted AGP `compressDebugAssets` duplicate-entry failure, stop Gradle and remove only the documented derived directory `app/build/intermediates/compressed_assets/debug/compressDebugAssets`, then retry. Do not generalize this cleanup to other failures without evidence.
- Distinguish restricted local sockets, WSL/USB permissions, disk pressure, and `/mnt/d` I/O latency from code/build failures. Record relocated work/cache paths exactly if used.

## ADB and reference-device workflow

- Use `.android-sdk/platform-tools/adb`, not `/usr/bin/adb`, and use its libusb backend (`ADB_LIBUSB=1`).
- The reference device is the authorized Samsung SM-T870/API 33, normally serial `R52R40K1PPN`. It also has Secure Folder user 150, so install, launch, stop, uninstall, and package operations must explicitly target owner `--user 0` where supported.
- WSL USB attachment permissions commonly reset after reconnect or restart. First use `lsusb` to resolve the current Samsung `04e8:6860` bus/device node; never reuse an old `/dev/bus/usb/BBB/DDD` path without checking it.
- If that exact node is `root:root` mode `0600`, repair only that resolved node with `sudo -A` (never interactive `sudo`):

  ```shell
  sudo -A chgrp plugdev /dev/bus/usb/BBB/DDD
  sudo -A chmod 0660 /dev/bus/usb/BBB/DDD
  .android-sdk/platform-tools/adb kill-server
  ADB_LIBUSB=1 .android-sdk/platform-tools/adb start-server
  ADB_LIBUSB=1 .android-sdk/platform-tools/adb devices -l
  ```

- Use `sudo -A` for every command that genuinely requires sudo in this workspace. Resolve and validate the narrow target before changing permissions; do not recursively loosen USB or workspace permissions.
- Device authorization may require the user to approve Android's prompt. Do not treat `unauthorized`, `offline`, or an absent transport as an app failure.
- Preserve and report device state. If a test disables Wi-Fi, changes app data, corrupts a facet, installs test artifacts, or changes permissions, restore the intended state when safe and record both the mutation and restoration.
- After process/service/cancellation tests, explicitly check that no Lean or Lake child remains. Avoid substring-only checks that confuse the Android app process with a native child.

## Validation expectations

- Match validation to risk and milestone exit criteria. Relevant layers include host unit tests, APK/instrumentation assembly, ZIP/APK structure and hashes, fresh install, update migration, cold launch, visible editor operation, offline behavior, and physical-device conformance.
- Installer/layout/filter changes require fresh-install and update/recovery coverage; a preserved healthy sysroot alone is insufficient. Never accept toolchain health solely because `Init.olean` or an install marker exists.
- Project/storage changes must preserve traversal protection, atomic/monotonic saves, bounded output, safe ZIP import/export, schema/toolchain checks, and rejection of unsupported network/native workflows.
- LSP/service changes must preserve one-reader ownership, generation-aware reconnection, stale-version rejection, lifecycle ordering, bounded cleanup, activity recreation, and no orphan processes.
- Keep measurements comparable: distinguish compressed delivery size, expanded sysroot size, rollback/staging headroom, cold versus warm latency/PSS, and aggregate RSS caveats.
- For a final code handoff, run `git diff --check`, inspect `git diff` and `git status`, and document every test that actually ran plus anything that did not. Perform any whitespace checks and then `git commit` at the end.

## Safety and repository hygiene

- Avoid broad destructive commands. Do not delete generated roots, app data, device data, or known-good artifacts merely to obtain a clean build. Prefer a new work directory or narrowly remove a proven derived artifact.
- Installer cleanup must not follow symlinks. Preserve the established `Files.walkFileTree` no-follow behavior; do not replace it with `File.deleteRecursively()` where toolchain rollback/layout links are present.
- Android randomizes the APK native-library directory after updates. Resolve `ApplicationInfo.nativeLibraryDir` at runtime, refresh writable compatibility links, and never persist the randomized path as immutable toolchain identity.
- Keep secrets, signing private keys, device-private data, and host-specific absolute paths out of commits and exported logs. Public-key and manifest changes require explicit review and corresponding delivery documentation.
