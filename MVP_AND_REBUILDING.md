# Lean4Android MVP construction and rebuilding guide

This document explains how the M0 scaffold and M1 Android Lean toolchain are constructed, how the build stages fit together, and which stages must be repeated after different kinds of changes. It is an operational guide; chronological discoveries and validation results live in `IMPLEMENTATION_HISTORY.md`, while future gates live in `IMPLEMENTATION_PLAN.md`.

## 1. Current build boundary

The repository currently contains:

- an Android application and Kotlin library modules built by Gradle;
- a pinned host/Android Lean 4.32.1 cross-build pipeline;
- pinned Android builds of LibUV 1.48.0 and OpenSSL 3.6.0;
- a canonical patch adapting Lean to Android;
- an audited Android distribution generated outside Git;
- APK staging that separates immutable native executable code from writable toolchain data; and
- physical-device conformance fixtures for direct Lean and Lake.

The current pins are authoritative:

| Input | Value |
|---|---|
| Lean | 4.32.1, commit `f054605aea4b840552cca2e725580bffd1e1b704` |
| LibUV | 1.48.0, commit `e9f29cb984231524e3931aa0ae2c5dae1a32884e` |
| OpenSSL | 3.6.0, commit `7b371d80d959ec9ab4139d09d78e83c090de9779` |
| Android NDK | 28.2.13676358 |
| Target | `arm64-v8a`, Android API 29+ |
| Toolchain ID | `lean-4.32.1-android1` |
| Android build | compile/target API 36, JDK 17, AGP 8.13.2, Gradle 8.13, Kotlin 2.3.0 |

Change pins only as a deliberate toolchain revision. Update `toolchain/versions.toml`, the patch, manifests, documentation, and conformance expectations together.

## 2. Repository and generated trees

Tracked inputs:

```text
app/                         Android application and APK staging rules
core-model/                  typed models
core-lsp/                    LSP JSON-RPC framing and lifecycle messages
core-process/                shell-free process runner
core-toolchain/              Android toolchain location/installation
toolchain/versions.toml      pinned source/build inputs
toolchain/patches/           canonical Lean source delta
toolchain/scripts/           build, assembly, and audit pipeline
toolchain/conformance/       valid/invalid and Lake fixtures
```

Generated, ignored trees:

```text
toolchain/work/src/                  pinned source checkouts
toolchain/work/build/host/           native host bootstrap
toolchain/work/build/*-android-arm64 dependency/target builds
toolchain/work/prefix/android-arm64/ Android dependency prefix
toolchain/output/<toolchain-id>/     audited distribution
app/build/generated/toolchain/       filtered APK inputs
app/build/                           Gradle intermediates and APK
.gradle-user-home/                   project-local Gradle state
```

These generated trees are large. Do not add them to Git. Do not delete an entire build tree to solve a narrow incremental problem unless its configuration or architecture is known to be invalid.

## 3. Why the build has separate stages

Lean is bootstrapped. The Android target compiler cannot initially generate itself on the host, and Android binaries cannot run on the build host. The pipeline therefore separates code generation from target compilation:

```text
pinned sources
      |
      +--> host Lean stage0 --------+
      |                             |
      +--> Android LibUV/OpenSSL    |
                                    v
                     generate Lean C with host compiler
                                    |
                                    v
                     compile/link with NDK AArch64 Clang
                                    |
                                    v
                       audited Android distribution
                                    |
                                    v
                   filtered APK native libraries + assets
```

The host compiler produces Lean artifacts and generated C. The NDK compiler builds that C and Lean's C/C++ runtime for Bionic/AArch64. Confusing these compilers previously produced valid-looking x86-64 objects in the Android tree, so compiler identity and ELF auditing are mandatory.

## 4. Full clean toolchain build

Run commands from the repository root. Required host tools include JDK 17, Git, CMake, Ninja, Make, Perl, Python 3, `jq`, and the pinned SDK/NDK installed under `.android-sdk` unless overridden.

Control parallelism and storage location if necessary:

```shell
export LEAN4ANDROID_JOBS=4
# Optional, preferably on a fast native Linux filesystem:
# export LEAN4ANDROID_WORK_DIR=/fast/path/lean4android-work
# export LEAN4ANDROID_OUTPUT_DIR=/fast/path/lean4android-output
```

### 4.1 Fetch and verify sources

```shell
toolchain/scripts/fetch-sources.sh
```

This stage:

1. fetches the exact Lean, LibUV, and OpenSSL tags;
2. verifies that each tag resolves to its pinned commit;
3. resets/cleans the generated checkouts; and
4. applies the checked-in Lean Android patch.

The build guard later requires the Lean checkout's complete `git diff --no-ext-diff` to equal the canonical patch byte-for-byte. Untracked Lean source files or any other source drift are rejected.

Warning: `fetch-sources.sh` deliberately resets generated source checkouts. Do not keep unrecorded work under `toolchain/work/src`.

### 4.2 Build the host bootstrap

```shell
toolchain/scripts/build-host.sh
```

This configures Lean's top-level build for the host and builds `stage0`. GMP, mimalloc, Lake, CaDiCaL, and Leantar are disabled for the feasibility bootstrap. The stage ends by running:

```shell
toolchain/work/build/host/stage0/bin/lean --version
```

The host executable is a build tool only. It is never shipped in the Android distribution.

### 4.3 Cross-build Android dependencies

```shell
toolchain/scripts/build-android-deps.sh
```

LibUV is configured through the NDK CMake toolchain for `arm64-v8a`, API 29, static output, and no tests/benchmarks/shared library. OpenSSL uses its `android-arm64` configuration with shared libraries, tests, apps, docs, and legacy provider disabled.

Both install into:

```text
toolchain/work/prefix/android-arm64/
```

Lean later resolves LibUV through that prefix's pkg-config file and links the static OpenSSL archives explicitly.

### 4.4 Cross-build Lean and Lake

```shell
toolchain/scripts/build-android.sh
```

Important configuration choices:

- CMake uses the pinned NDK toolchain and `ANDROID_PLATFORM=android-29`.
- `PREV_STAGE` points to host stage0 for Lean code generation.
- `LEANC_CC` is the NDK `aarch64-linux-android29-clang` driver.
- the target triple is `aarch64-linux-android`;
- GMP, mimalloc, LLVM, CaDiCaL, and Leantar are disabled;
- LibUV and OpenSSL come from the Android prefix; and
- libc++ is linked with the NDK's static linker-script behavior: `-Wl,-Bstatic -lc++ -Wl,-Bdynamic`.

Although `USE_LAKE=OFF` avoids building Lake in the host bootstrap/configuration sense, Lean's target build produces the bundled Lake library and executable targets used by the distribution.

The canonical Lean patch currently provides:

1. Android ELF/PIC/dynamic-loader handling where upstream checks only named Linux;
2. NDK compiler propagation into `leanc` and `stdlib.make`;
3. disabling target tests in the cross build;
4. an early Android-only Bionic `mallopt` call that disables heap pointer tagging before Lean runtime initialization; and
5. internal sysroot discovery that honors `LEAN_SYSROOT` before executable-relative discovery.

The build tree is incremental. A C/C++ runtime change should rebuild its object/archive and relink consumers. A low-level Lean source change may regenerate many downstream `.olean`, split facets, generated C files, and native objects.

### 4.5 Assemble and audit the distribution

```shell
toolchain/scripts/assemble-distribution.sh
```

This recreates `toolchain/output/lean-4.32.1-android1` from the target build:

```text
native/arm64-v8a/
  liblean_exe.so
  liblake_exe.so
  Lean/Lake shared libraries

sysroot/
  lib/lean/
  include/
  share/
  src/lean/
  LICENSE
```

Every packaged native file is checked with the NDK's `llvm-readelf`. The audit requires AArch64 and permits only packaged Lean libraries plus an explicit Android system-library allowlist. It rejects host architecture, glibc, missing packaged libraries, and shared libc++ dependencies. Finally, `write-manifest.py` hashes the distribution into `manifest.json` and `jq` validates the JSON.

Assembly deletes and recreates the output distribution. On `/mnt/d`, copying the roughly 3.1 GB source sysroot can take many quiet minutes. Do not interrupt it merely because it emits no progress.

## 5. Android build and packaging

### 5.1 Gradle modules

The Android project contains `app`, `core-model`, `core-lsp`, `core-process`, and `core-toolchain`. The application currently depends on the model, process, and toolchain libraries plus Compose; `core-lsp` is deliberately isolated until the long-lived server supervisor is implemented. Unit tests cover toolchain IDs/path safety, shell-free process arguments, runtime layout/environment construction, visual-editor support, and byte-accurate LSP framing/lifecycle messages.

### 5.2 Toolchain staging

`app/build.gradle.kts` defines two `Sync` tasks:

- `stageToolchainNative` copies audited native files into generated `jniLibs`;
- `stageToolchainSysroot` filters audited sysroot data into generated assets.

Native executables use `.so` names because Android installs native libraries into an executable, read-only directory. Debug symbols are kept to avoid Gradle redundantly processing the very large `libleanshared.so`.

The sysroot filter must retain all runtime-required split artifacts:

- `.olean`;
- `.olean.server`;
- `.olean.private`;
- `.ir`; and
- `.ilean`.

Device tests proved that removing server/private/IR facets causes successive import failures. The filter still removes static archives, exports, native objects, generated C, dependency files, and duplicate shared libraries. Any future size reduction must pass direct Lean, Lake build, interpretation, and LSP conformance rather than assuming an extension is build-only.

### 5.3 Build and test the APK

Use the project-local Gradle home and disable the build cache for the large asset build:

```shell
GRADLE_USER_HOME="$PWD/.gradle-user-home" \
  ./gradlew --no-build-cache --no-daemon --console=plain \
  testDebugUnitTest :app:assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Large staging, asset compression, and packaging are I/O-bound on `/mnt/d`. The first complete filtered staging has taken about ten minutes; a full package cycle can take longer. `--no-build-cache` prevents Gradle from making another large redundant cache copy.

If `compressDebugAssets` was interrupted and retry reports many “already contains entry” failures, stop Gradle and remove only its derived output before retrying:

```shell
rm -rf -- "$PWD/app/build/intermediates/compressed_assets/debug/compressDebugAssets"
```

Do not remove source distributions or toolchain build trees for this AGP intermediate failure.

### 5.4 Audit the APK

At minimum, verify:

```shell
apk=app/build/outputs/apk/debug/app-debug.apk
stat -c '%y %s %n' "$apk"
sha256sum "$apk"
unzip -Z1 "$apk" | less
.android-sdk/build-tools/35.0.0/aapt dump badging "$apk" | head
```

Check that Lean/Lake executables and all shared libraries occur under `lib/arm64-v8a`, `assets/toolchain/lib/lean/Init.olean` exists, all required split facets are represented, and forbidden build-only extensions are absent.

## 6. On-device installation and runtime layout

### 6.1 ADB under WSL

Use the project SDK's ADB and its libusb backend:

```shell
export ADB_LIBUSB=1
.android-sdk/platform-tools/adb devices -l
```

If `lsusb` sees Samsung `04e8:6860` but ADB does not, inspect the current USB node. A temporary WSL permission repair is:

```shell
sudo -A chgrp plugdev /dev/bus/usb/BBB/DDD
sudo -A chmod 0660 /dev/bus/usb/BBB/DDD
.android-sdk/platform-tools/adb kill-server
ADB_LIBUSB=1 .android-sdk/platform-tools/adb start-server
ADB_LIBUSB=1 .android-sdk/platform-tools/adb devices -l
```

Replace `BBB/DDD` with the current bus/device from `lsusb`; it can change after reconnect. Approve the device authorization prompt. The reference Samsung device also has Secure Folder user 150, so package operations must explicitly use owner user 0.

### 6.2 Install and launch

```shell
export ADB_LIBUSB=1
adb=.android-sdk/platform-tools/adb
"$adb" install --user 0 -r -t app/build/outputs/apk/debug/app-debug.apk
"$adb" shell am force-stop --user 0 org.lean4android.app
"$adb" shell am start --user 0 -W -n org.lean4android.app/.MainActivity
```

The first-run installer copies assets into a sibling staging directory, verifies `Init.olean`, atomically renames the completed sysroot, and writes `.installed` only after activation. The hardened installer must additionally verify/hash all required facets and create or refresh the conventional Lean/Lake layout links.

The intended runtime split is:

```text
/data/app/<random>/.../lib/arm64/       immutable executable ELF/shared libraries
/data/user/0/<package>/no_backup/...   writable sysroot data and compatibility links
/data/user/0/<package>/files/...       projects and app-owned HOME
```

Android randomizes the first path after an APK update. Never store it in the immutable toolchain marker. Resolve it from `ApplicationInfo.nativeLibraryDir` and refresh links on startup/update.

### 6.3 Runtime environment

Direct Lean commands require a deterministic environment containing `HOME`, app-writable `TMPDIR`, `LEAN_SYSROOT`, `LEAN_PATH`, `PATH`, `LD_LIBRARY_PATH`, and an explicit `TZ` pointing to the installer-owned UTC TZif fallback. Lake commands use the same base environment plus `LAKE_HOME` and `LAKE_OVERRIDE_LEAN=true`, but deliberately omit a global `LEAN_PATH` so Lake can construct the project-aware search path for child Lean processes. Device testing showed that `lake lean` needs `TMPDIR` because Android's default temporary location is not writable by the app UID. It also showed that Lean Server exits after `initialized` when `TZ` is unset because Android lacks `/etc/localtime`; the small UTC fallback makes server startup deterministic without depending on device-specific timezone storage. `ToolchainCommandFactory` is the production source of these shell-free command definitions; UI code must not reconstruct their paths or environment maps.

Lake expects conventional paths. The installer/locator must provide links conceptually equivalent to:

```text
<sysroot>/bin/lean                 -> <nativeLibraryDir>/liblean_exe.so
<sysroot>/.lake/build/bin/lake    -> <nativeLibraryDir>/liblake_exe.so
<sysroot>/.lake/build/lib/lean    -> <sysroot>/lib/lean
```

`AndroidToolchainLocator.installSysroot()` refreshes this layout even when the immutable sysroot marker already exists. This is required after APK replacement because Android changes `nativeLibraryDir`; health checks reject absent or stale links rather than silently accepting an unusable Lake installation.

The installation marker is schema- and toolchain-aware. Legacy ID-only and schema-1 markers migrate in place only after the complete packaged runtime manifest validates. An unhealthy active tree is never accepted solely because a marker exists: the installer clears stale staging, preflights the measured 2,194,903,155-byte packaged sysroot plus a 64 MiB reserve, validates a new staging tree before activation, preserves the former tree as `.previous` during the rename, and restores a healthy previous tree after interrupted activation. Activation and rollback are isolated behind a deterministic host-test boundary. Every installer cleanup uses a `Files.walkFileTree` operation without link-following; Kotlin `File.deleteRecursively()` is prohibited here because a rollback tree contains an absolute Lake compatibility link that can otherwise traverse into and erase the newly activated `lib/lean`. Tests cover that exact link topology, a successful swap, failed staging/rollback renames, and cold-start restoration of only a healthy `.previous` tree; physical process interruption remains required.

APK staging also generates `assets/toolchain-manifest.tsv` from the audited distribution manifest. It contains path, size, and SHA-256 for all 14,864 files that survive runtime filtering; generation fails if a staged path is unaudited or its size differs. Hashes are reused from the already-audited distribution so staging does not reread 2.19 GB. The Kotlin parser rejects traversal, duplicates, malformed sizes/hashes, and unknown records, while the streaming verifier detects missing, wrong-size, and same-size corrupted files.

Installation schema 2 binds the SHA-256 of that manifest into `.installed`. Fresh staging and legacy/schema-1 upgrades stream-verify every manifest entry before writing schema 2. A failed legacy/schema-1 verification falls through to the staged repair path rather than blessing or immediately aborting on the corrupt tree. Once schema 2 is present, ordinary editor Check performs only fast marker/representative/layout checks; it does not rehash 2.19 GB. The visual probe exposes a separate **Verify runtime** action that performs the complete manifest audit on demand and reports its elapsed time and every mismatch.

The accepted process/runtime boundary, alternatives, upstream adaptations, and capability limits are recorded in `docs/adr/0001-android-lean-process-and-runtime-boundary.md`. Reopen that decision only when device/API evidence shows the child-process model or cleanup boundary is unreliable.

Writable links are metadata, not copied executable code.

## 7. Rebuild recipes by change type

Always inspect `git status` before building. Existing unrelated changes belong to the user and must not be discarded.

### 7.1 Kotlin, Compose, manifest, or Android resource change

If no audited toolchain input changed:

```shell
GRADLE_USER_HOME="$PWD/.gradle-user-home" \
  ./gradlew --no-build-cache --no-daemon --console=plain \
  testDebugUnitTest :app:assembleDebug
```

Then reinstall with `adb install --user 0 -r -t`. `-r` preserves app-private data. Test both cold launch and the affected UI/process path. If the installer or locator changed, also test a genuinely fresh data install and APK update migration; preserving old data alone is insufficient.

For a fast compile-only check, run the narrow module task first, but do not use it as final verification:

```shell
GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew :app:compileDebugKotlin
```

### 7.2 Unit-test-only or Kotlin library change

Run the affected module test and the aggregate debug tests:

```shell
GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew \
  :core-model:testDebugUnitTest \
  :core-process:testDebugUnitTest \
  :core-toolchain:testDebugUnitTest \
  testDebugUnitTest
```

Build/reinstall the APK if behavior crosses the Android filesystem, process, or application metadata boundary.

### 7.3 Lean C/C++ runtime source change

Example: editing `src/runtime/*.cpp`, `src/util/*.cpp`, or CMake/link behavior.

1. Make the experimental change in `toolchain/work/src/lean4`.
2. Inspect and validate it:

   ```shell
   git -C toolchain/work/src/lean4 diff --no-ext-diff --check
   git -C toolchain/work/src/lean4 diff --no-ext-diff
   ```

3. Update `toolchain/patches/0001-cmake-treat-android-as-elf-platform.patch` so it is exactly the complete checkout diff. Verify exact equality:

   ```shell
   git -C toolchain/work/src/lean4 diff --no-ext-diff > /tmp/lean4android.patch
   cmp /tmp/lean4android.patch \
     toolchain/patches/0001-cmake-treat-android-as-elf-platform.patch
   ```

   A unified diff's blank context line contains the required leading context marker; generic outer-repository whitespace checks may flag it. Exact canonical equality is the controlling invariant.

4. Run the incremental Android build:

   ```shell
   LEAN4ANDROID_JOBS=4 toolchain/scripts/build-android.sh
   ```

5. Confirm the expected object/archive rebuilt and all affected shared libraries/executables relinked.
6. Reassemble and audit the entire distribution:

   ```shell
   toolchain/scripts/assemble-distribution.sh
   ```

7. Rebuild/test the APK with `--no-build-cache`, audit it, reinstall it, and rerun at least version plus valid/invalid conformance. Run Lake/LSP tests if the change can affect them.

A runtime source modification does not normally require rebuilding host stage0 or Android dependencies. It does require distribution assembly because the APK stages only audited output, not files directly from the target build.

### 7.4 Lean `.lean` source change

Follow the same canonical-patch process. Then run `build-android.sh`. The host stage0 compiler will regenerate the changed module and its downstream Lean artifacts/C. The dependency graph can be broad: changing a low-level module such as `Lean.Util.Path` rebuilds many dependents. Do not delete valid `.olean` outputs or interrupt an active generator because progress appears extensive.

After completion, reassemble/audit, rebuild/reinstall the APK, and run direct Lean, Lake, and LSP conformance appropriate to the changed module.

### 7.5 Lean patch-only canonicalization

If the generated checkout already contains the intended source delta but the guard rejects the checked-in patch, compare exact bytes and ordering. The patch must use Git's path order and hunk context exactly:

```shell
git -C toolchain/work/src/lean4 diff --no-ext-diff > /tmp/lean4android.patch
sha256sum /tmp/lean4android.patch \
  toolchain/patches/0001-cmake-treat-android-as-elf-platform.patch
diff -u toolchain/patches/0001-cmake-treat-android-as-elf-platform.patch \
  /tmp/lean4android.patch
```

Do not weaken `require_patched_lean_checkout` to accept arbitrary drift.

### 7.6 LibUV or OpenSSL change

Update its version and commit pin, fetch sources, then rebuild Android dependencies:

```shell
toolchain/scripts/fetch-sources.sh
toolchain/scripts/build-android-deps.sh
toolchain/scripts/build-android.sh
toolchain/scripts/assemble-distribution.sh
```

Because Lean links these dependencies, relink and rerun the complete ELF dependency audit. Then rebuild/reinstall the APK and run native process, Lake, and LSP conformance.

### 7.7 NDK, minimum API, ABI, or compiler/linker change

Treat this as a clean architecture-sensitive rebuild. Update pins/configuration, use a new toolchain revision ID, and rebuild dependencies plus Lean. Old target `.o`, `.a`, and `.so` files must not be reused across architecture/toolchain changes. Prefer a new `LEAN4ANDROID_WORK_DIR` over deleting the known-good tree.

Verify extracted archive members and every final ELF are AArch64. Repeat physical testing at the oldest supported API. Changing ABI requires Android Gradle ABI filters, distribution paths, manifest metadata, and packaging rules as well.

### 7.8 APK sysroot filter or distribution-layout change

No Lean compilation is required if audited distribution bytes are unchanged. Update Gradle staging, run the full unit/APK build, audit extensions/counts/size, and test a fresh first-run install. A filter change can silently produce a buildable APK that fails only at Lean import time, so direct, Lake, interpretation, and LSP conformance are mandatory.

### 7.9 Toolchain installer or marker-schema change

Test all of:

1. fresh install with no destination;
2. already healthy installation;
3. partial `.installing` directory;
4. destination with missing/corrupt facet;
5. APK update with a changed randomized native directory;
6. insufficient storage; and
7. interrupted copy followed by restart.

Use a new schema/revision marker when old installations need migration. Never mark installed before data activation and link refresh both succeed.

## 8. Conformance and release checks

### 8.1 Host checks

```shell
bash -n toolchain/scripts/*.sh
jq empty toolchain/output/lean-4.32.1-android1/manifest.json
GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew testDebugUnitTest
```

Also verify the source patch invariant and inspect `git status`.

### 8.2 Physical-device sequence

Run these independently and record exit codes, stdout/stderr, elapsed time, RSS, and residual processes:

1. `lean --version`;
2. valid Lean fixture;
3. invalid Lean fixture;
4. `lake --version` and project configuration;
5. `lake lean`;
6. Lean-library `lake build` without network;
7. LSP initialize/initialized/open/diagnostics/shutdown/exit;
8. forced cancellation and orphan-process check;
9. cold restart;
10. paths containing spaces and non-ASCII characters; and
11. offline operation.

Do not count a toolchain as healthy merely because `Init.olean` exists. The health contract must cover split facets, native libraries, current links, and a real process probe.

### 8.3 Current measured facts

On the Samsung SM-T870/API 33 reference tablet:

- the final 803,678,420-byte debug APK installs and fresh first-run sysroot activation works;
- `lean --version` reports Lean 4.32.1 for `aarch64-linux-android`;
- valid checking exits 0 and invalid checking exits 1 with the expected source-positioned diagnostic;
- a valid file under a path containing spaces and non-ASCII characters checks successfully;
- the installed sysroot is approximately 2,175,523 KiB after adding compatibility links;
- Lake 5.0.0 starts, `check-build` passes, `lake build` produces Lean library artifacts, and `lake lean` passes with app-writable `TMPDIR`;
- a byte-exact non-PTY Lean Server 0.3.0 initialize/initialized/shutdown/exit exchange returns full capabilities;
- a real Lean server child terminates on SIGTERM without a residual app-UID process;
- a cold app restart reuses the activated sysroot and completes the production version probe in approximately 2.48 seconds; and
- filtered logcat shows no app fatal exception, pointer-tagging abort, or native backtrace from the conformance run.

The current schema-2 APK update also preserves the visual editor and completes direct Lean checking in 2,911 ms. Its user-triggered complete audit hashed all 14,864 installed runtime files successfully in 16,778 ms. The instrumented LSP client measured a warm initialize response in 399 ms and the first invalid-source diagnostic 1,047 ms after `didOpen` (1,450 ms from process launch). With Wi-Fi disabled, the same workflow passed in 413 ms to initialize and 1,035 ms from `didOpen` to diagnostics, then Wi-Fi was restored to its original connected state.

A physical same-size facet corruption plus schema-1 marker forced complete staged repair. That probe exposed and led to correction of symlink-following rollback deletion; the corrected installer replaced the full runtime and returned successful Lean output about 54.2 seconds after Check. A post-repair audit passed in 16,626 ms. The first cold post-repair LSP run measured 544,681 KiB peak aggregate PSS and 3,171 ms launch-to-diagnostic; the immediate warm repeat measured 89,430 KiB PSS and 1,393 ms. Aggregate RSS remains a shared-page-double-counting upper bound. Preserve separate cold and warm PSS/latency results.

Production code now creates and refreshes writable symlinks to the APK-installed Lean/Lake executables and centralizes direct Lean/Lake environments; an API-33 APK update validated stale native-path repair. `core-lsp` now owns a long-lived `RunningProcess`, framed transport, strict dependency-free JSON parsing, typed envelope dispatch, dynamic-registration replies, lifecycle ordering, version-filtered diagnostics, graceful exit, forced early-close cleanup, and a tested single-reader supervisor that distinguishes EOF, explicit close, and protocol/transport failure. The app now declares a non-exported local bound service that retains one supervisor per project, exposes generation-tagged snapshots/listeners for UI reconnection, rejects duplicate ownership, and closes children on destruction. Editor binding, physical activity-recreation validation, foreground policy, and the API-level matrix remain M1 work. Storage remains guarded by a non-destructive byte preflight with readable required/available capacity; manufactured low-storage pressure is a post-MVP stretch test. Continue reporting installed size plus cold/warm PSS and latency so users can make an informed device-capacity decision, without making speculative memory optimization a release gate.

The initial M1.6 packaging breakdown is recorded in `docs/delivery/M1_6_BASELINE.md`. In the measured 804,600,480-byte debug APK, `assets/toolchain/` contributes 692,307,895 compressed bytes for 2,192,429,171 uncompressed bytes; eight arm64 native entries contribute another 79,491,830 compressed bytes. `.olean.private` alone contributes 443,221,498 compressed bytes, but device conformance shows those facets cannot be omitted. Treat native code, delivered-data download size, expanded installed size, and installer rollback headroom as separate budgets.

M1.6 and M2 are now closed on the API-33 reference device. The selected Play prototype is a 693,129,838-byte install-time asset-pack AAB; the independent full signed ZIP is 595,064,864 bytes. Both use the manifest-driven streaming installer documented in `docs/delivery/M1_6_DECISION.md`. The final migration-fix APK passed the offline M2 instrumentation scenario in 6.791 seconds: create a two-module project, observe an error, perform a rapid atomic fix, build, export, delete, reimport, and rebuild, with no residual Lean/Lake process. The visual baseline is now a simple two-tab Lake project editor rather than the earlier direct-Lean single buffer.

M3 is complete on the API-33 reference tablet. The native Compose editor decision is recorded in `docs/editor/M3_EDITOR_DECISION.md`; the editor now provides bounded per-tab undo/redo, Unicode-safe search, Lean syntax decoration, contained file create/rename/delete, atomic dirty-buffer/process recovery, adaptive compact/tablet layouts, hardware Ctrl-F/Ctrl-Z/Ctrl-Shift-Z/Ctrl-Y/Ctrl-S handling, and accessibility descriptions/live output semantics. Distinct dirty buffers survived a full process force-stop/restart, ActivityScenario passed resumed/recreate/resumed after waking the dozing tablet, temporary phone sizing and landscape checks passed and were restored, and hardware Ctrl-F visibly opened search. A visible project build exited 0 in 1,214 ms with the expected theorem and value 42; no exact Lean/Lake child remained.

M3.1 is complete on the API-33 reference tablet. The app now has the filename/path shell, anchored Files/More menus, extensible Project drawer, explicit atomic Save, retaining Save As, protected Close and zero-tab state, full-screen Recent/My Projects/Open workspace flow, bounded SAF archive/folder/Lean-file imports into validated internal workspaces, and cancellable offline build-plus-run. Physical tests covered valid and hostile imports, picker return, Save/Save As/Close, compact/tablet portrait/landscape layouts, recreation, a real offline run, mid-build cancellation, and exact no-orphan cleanup. SAF remains only an import/export boundary; Lake never runs against provider URIs.

M3.2 is complete on the API-33 reference tablet. The editor now shows a horizontally scrollable browser-style tab for every recovered/open buffer, preserves inactive and dirty buffers while focusing newly opened files, and renders a presentation-only one-based gutter with shared source scrolling and monospaced line metrics. The drawer now has an extensible full-screen Settings hierarchy; Appearance contains an accessible explicit dark-theme switch that applies immediately to the complete Compose surface and persists across Activity/process recreation. Physical acceptance covered tab switching with a dirty buffer, gutter alignment, Settings/Appearance navigation, light/dark screenshots, dark cold-launch persistence, restoration to light, compact/tablet portrait/landscape layouts, recreation, offline build-plus-run, and exact no-orphan cleanup.

M3.3 is the next required pre-M4 boundary and is requirements-only, not implemented. `docs/project/M3_3_PROJECT_EXPORT_REQUIREMENTS.md` adds **Export project** to the navigation drawer and specifies a SAF `CreateDocument` portable ZIP workflow with explicit dirty-buffer choices, bounded deterministic contents, generated/cache/secret exclusion, interruption cleanup, and physical export/reimport/offline-build proof. M4 must wait until users can move projects out of uninstall-sensitive app storage through that device-proven workflow.

## 9. Recovery and operational cautions

- Prefer incremental recovery. Lean generation writes side outputs; a crash can leave `.tmp` or a primary artifact without all companion files. Diagnose the exact missing artifact before deleting anything.
- Never accept host-architecture objects in the Android build. Use the NDK `llvm-readelf` on objects/archive members and final ELFs.
- Avoid broad destructive commands in the repository or generated roots. Preserve the last known-good audited distribution until its replacement passes.
- Use `--no-build-cache` for toolchain-bearing APKs. Large Gradle cache writes add time and space without helping the current workflow.
- WSL socket restrictions can prevent Gradle and ADB before task execution. Distinguish infrastructure failure from build failure.
- `/mnt/d` is much slower than a native Linux filesystem for thousands of files. Relocating `LEAN4ANDROID_WORK_DIR`, Gradle state, or outputs can materially reduce rebuild time, but record the exact paths used.
- Android package commands should specify `--user 0` on the reference Samsung device because Secure Folder user 150 is present and restricted.
- Do not use shell-composed commands in production. ADB diagnostic quoting problems around randomized APK paths demonstrate why `ProcessBuilder(List<String>)` is required.

## 10. M0 reproducibility checklist

Before calling a new environment ready to work on M1:

- JDK 17 runs;
- Gradle wrapper checksum/distribution validation succeeds;
- Android SDK 36, build tools, platform-tools, NDK 28.2, and CMake are installed;
- `local.properties` points to the intended SDK;
- all Gradle modules compile and unit tests pass;
- a scaffold APK installs and launches on an arm64 API-29+ device;
- pinned source commits and canonical patch validation pass;
- work/output directories are ignored and have sufficient free space;
- project ADB can enumerate/authorize the reference device; and
- implementation history records the environment and validation result.

For exact historical failures and completed checkpoints, consult `IMPLEMENTATION_HISTORY.md` rather than inferring state from old generated files.
