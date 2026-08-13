# Implementation history

This is the durable engineering log for Lean4Android. Entries summarize shipped code, validation, and decisions; detailed future work remains in `IMPLEMENTATION_PLAN.md`.

## 2026-08-11 — M0 scaffold and M1 probe boundary

### Implemented

- Created an Android multi-module Gradle scaffold: `app`, `core-model`, `core-process`, and `core-toolchain`.
- Added a minimal Compose screen that reports whether the packaged Lean/Lake executables and writable Lean sysroot are present.
- Added typed toolchain layout/health models. Executable paths resolve only through Android's read-only `nativeLibraryDir`; sysroot data resolves under `noBackupFilesDir`.
- Added a bounded, shell-free one-shot process runner with separate stdout/stderr draining, a real blocking-process timeout, and forced termination. Long-lived LSP supervision remains separate by design.
- Added pinned toolchain inputs, a JSON manifest schema, and valid/invalid Lean conformance fixtures.
- Added initial unit tests for toolchain-ID/path safety and shell-free argument preservation.
- Added repository ignores and explicit build dependency versions.
- Added the official Gradle 8.13 wrapper launcher and JAR, with distribution URL validation enabled.

### Decisions

- Initial platform: `arm64-v8a`, minimum API 29, target/compile API 36.
- Build stack: AGP 8.13.2, Gradle 8.13, Kotlin 2.3.0, JDK 17. This avoids beginning on AGP 9's built-in-Kotlin migration while remaining on a supported stable stack.
- Initial Lean pin: 4.32.1 with toolchain revision ID `lean-4.32.1-android1`; NDK pin: 28.2.13676358.
- Native executables will be APK-installed `.so`-named ELF executables. Writable app directories contain data and projects only, never downloaded executable code.
- Backups are disabled for the initial scaffold until project backup/export and large toolchain-data behavior are deliberately designed.

### Validation

- Source/configuration structure was inspected locally.
- The toolchain JSON schema parses successfully; official wrapper artifacts were downloaded and checksummed.
- Installed JDK 17 and the pinned local Android SDK 36, build-tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1, and platform-tools packages.
- Migrated all modules to Kotlin 2.3's typed `compilerOptions` JVM-target DSL and fixed the toolchain locator's `File` constructor reference found by the first compilation.
- `./gradlew testDebugUnitTest :app:assembleDebug` passes; the model/process unit tests succeed and the debug APK is produced.
- Installed the debug APK on a physical arm64 Android device. The Compose probe launched successfully and correctly reported all three not-yet-packaged toolchain components as absent.
- No Lean Android binaries exist yet, so the probe is expected to report a missing toolchain.

### Next

- Implement the reproducible Lean host-bootstrap/Android-target build and ELF audit.
- Replace the inspection-only probe with `lean --version`, valid-file, invalid-file, and LSP-handshake device probes.

## 2026-08-11 — M1 reproducible cross-build pipeline

### Implemented

- Pinned the exact Lean, LibUV, and OpenSSL commits in addition to their release tags.
- Added scripts that fetch and verify source revisions, build the native host bootstrap, cross-build static Android dependencies, build Lean's generated C/runtime for Android arm64 API 29, assemble a distribution, audit ELF architecture/dependencies, and generate a hashed manifest.
- Added a narrow upstream patch that applies Lean's ELF/PIC linker behavior to Android. Android retains upstream symbol exports required by interpreted/native module loading.
- Kept generated sources, build trees, and distributions outside version control; promotion into APK inputs remains explicit.

### Validation in progress

- The fetch stage resolves Lean `f054605aea4b840552cca2e725580bffd1e1b704`, LibUV `e9f29cb984231524e3931aa0ae2c5dae1a32884e`, and OpenSSL `7b371d80d959ec9ab4139d09d78e83c090de9779` and applies the Android patch cleanly.
- LibUV 1.48.0 and OpenSSL 3.6.0 cross-compile successfully with NDK 28.2 for arm64 API 29; archive members are verified ELF64 AArch64 objects.
- Lean's host stage0 bootstrap completes and runs. The Android target correctly uses it as the previous-stage compiler instead of unnecessarily building host stage1.
- Source validation now rejects revision drift, untracked dependency sources, and any Lean source delta other than the exact checked-in Android patch.
- Resume check: no build process was left running. `bash -n toolchain/scripts/*.sh` passes, the host bootstrap reports Lean `4.32.0-pre`, all three Android dependency archives are present, and the pinned source checkout integrity checks pass.
- Resumed `LEAN4ANDROID_JOBS=16 toolchain/scripts/build-android.sh`: CMake configuration and all native runtime/core targets completed, then the stdlib phase failed because Clang was invoked for generated `Lean/Meta/InferType.c` before that file existed. This is under investigation as a parallel generated-source dependency race; no Android executable was linked by this run.
- A `-j4` retry reproduced the missing `InferType.c`: the interrupted build had retained its primary `.olean` while its generated C side output was incomplete. Moving the still-active `.tmp` was aborted after it exposed a corrupt sparse file; the run was stopped before accepting that output.
- Quarantined the complete generated `InferType` artifact set under `/tmp/lean4android-infertype-recovery` and restarted at `-j1`. The serial rebuild recreated valid `.olean`, `.ilean`, and UTF-8 C output atomically and is continuing cleanly through Lean compiler/meta modules. This recovery build is currently running; the Android executable has not linked yet.
- After a host crash, confirmed no build process survived and resumed `LEAN4ANDROID_JOBS=1 toolchain/scripts/build-android.sh` from the intact build tree. CMake accepted the recovered `Lean/Meta/InferType` outputs, reused completed native targets, and advanced into new `Lean/Compiler/LCNF` modules without error; the serial recovery build is running again.
- The resumed serial build completed the remaining LCNF passes, the `Lean/Compiler` aggregate, parser compiler, module parser, and pretty-printer/delaborator layers. It has entered elaborator modules with no recurrence of missing or malformed generated C, including successful downstream use of the recovered compiler/meta outputs.
- The same run subsequently completed core term/command elaboration, structural and well-founded recursion, pre-definition, deriving, built-in simplifier procedures, and the BVDecide prover/tactic aggregate. It also rebuilt `Lean/Meta/Sym/InferType`, the last `InferType`-named module with a crash-era dependency marker, without error; the serial build continues toward top-level libraries and linking.
- The recovery build then completed the full symbolic-meta, `grind`, Cutsat/linear/commutative-ring arithmetic, `Lean/Meta/Tactic`, user-facing tactic, `do` verification-condition/proof-mode, Omega, `Lean/Elab/Tactic`, and `Lean/Elab` aggregates. No generated-source or compiler failure recurred; remaining work in this run is top-level Lean/server library assembly and native linking.
- The run completed `Lean/Meta`, linter, completion, file-worker, RPC, widget, server, utility, and top-level `Lean.lean` generation, then native C compilation exposed the other artifact left incomplete by the host crash: `Std/Sat/AIG/RefVecOperator.c.tmp` existed while the final `.c` did not, despite completed primary Lean outputs. Clang therefore failed with a missing input; this is the same interrupted atomic-output condition seen for `InferType`, not a new source dependency failure.
- After the next computer restart, no build process remained. The filesystem shows that the interrupted generator completed its atomic rename before termination: `RefVecOperator.c` is present and nonempty, and its `.tmp` no longer exists. The intact serial build tree is ready to resume at `-j1` without quarantining or deleting artifacts.
- Resumed `LEAN4ANDROID_JOBS=1 toolchain/scripts/build-android.sh`. Configuration completed, the runtime/core targets through `leancpp` were reused, and native compilation of the generated Lean library C sources is progressing cleanly through `Lean/Meta`; the recovered `RefVecOperator.c` was accepted with no missing-input recurrence. This serial build is currently running.
- The resumed run completed native compilation of all 2,271 generated main Lean C modules, including the crash-adjacent `Lean/Meta/Sym/InferType.c`, and produced the 313 MB Android `libLean.a`, plus `libLeanc.a` and `libLeanIR.a`. It has advanced into Lake source/C generation and remains active at `-j1` without errors; final executables have not linked yet.
- The run completed all Lean/Std/Lake generation and reached `make_stdlib`, then the first shared-library link failed on `-lc++_static`. ELF inspection revealed the underlying configuration defect: Lean's generated `leanc.sh` had captured CMake's generic host `clang` path without the Android toolchain's separate target arguments, so the generated native objects and archives were x86-64. The build now pins `LEANC_CC` to the NDK's API-29 AArch64 driver, and the upstream patch routes both `leanmake` and `leanc.sh` through that configured compiler. `.olean` outputs remain reusable, but all target C objects/archives must be rebuilt and audited as AArch64.
- Deleted only architecture-dependent `.o`, `.o.export`, `.a`, and `.so` files from the Android build tree and restarted at `-j1`, preserving generated C and Lean artifacts. Regenerated `stdlib.make`/`leanc.sh` both select `aarch64-linux-android29-clang`; newly rebuilt CMake objects and an extracted `libleanshell.a` member are verified ELF64 AArch64. The corrected rebuild is currently running through native runtime/core targets.
- After resuming again, the source-integrity guard stopped before compilation because the checked-in patch omitted a trailing blank context line from its final hunk. The patched Lean sources themselves were unchanged; the patch was canonicalized to exactly match `git diff --no-ext-diff`, restoring the guard's byte-for-byte invariant.
- The corrected AArch64 rebuild completed `make_stdlib`, `leanrt_initial-exec`, and the Lean/Lake shared-library targets through 72%, then failed linking `lake`: `libleanshared.so` retained unresolved C++ ABI symbols. The NDK's static libc++ interface is its API-specific `-lc++` linker script, which groups `libc++_static.a` with `libc++abi.a`; the prior `-lc++_static` override selected only the first archive. The Android configuration now uses `-lc++` so exception, RTTI, and allocation symbols are included. Existing AArch64 objects remain reusable; only shared libraries and executables require relinking.
- With `-lc++`, the targeted relink succeeded: all Lean/Lake shared libraries and executables linked and the full build reached 100%. Distribution audit then correctly rejected their unbundled `libc++_shared.so` dependency. NDK driver inspection showed its static-STL sequence is `-Bstatic -lc++ -Bdynamic`; the configuration now spells those mode switches explicitly so the API linker script supplies both `libc++_static.a` and `libc++abi.a` without introducing a shared-STL runtime dependency.
- The static-STL relink completed successfully. Independent `llvm-readelf` inspection confirms all five shared libraries and the `lean`, `lake`, `leanc`, `leanir`, and `leanchecker` executables are ELF64 AArch64; their dynamic dependencies are limited to packaged Lean libraries and Android system libraries, with no `libc++_shared.so`. `assemble-distribution.sh` then passed its complete ELF/dependency audit and generated the hashed `lean-4.32.1-android1` distribution: 14,899 files occupying 3.1 GB, including its manifest.
- Added explicit APK staging from the audited distribution. All native executables/shared libraries are staged under `jniLibs/arm64-v8a`; the writable sysroot asset excludes build-only archives, exports, IR, private/server caches, native objects, generated C, and duplicate shared libraries. The staged runtime payload is approximately 523 MB. Added a background first-run installer that recursively copies packaged sysroot assets into a temporary sibling directory, validates `Init.olean`, atomically renames the completed tree into `noBackupFilesDir/toolchains/lean-4.32.1-android1`, and writes an installation marker only after activation. The Compose probe now invokes installation off the UI thread before locating the toolchain.
- Kotlin compilation of the installer passed during the first Gradle run. That run was manually stopped after Gradle spent more than ten minutes redundantly processing the 273 MB debug-symbol-bearing Lean shared library; APK packaging had not completed. `jniLibs.keepDebugSymbols` is now enabled to bypass that redundant strip. A second build entered filtered toolchain staging and was manually stopped at the user's logout checkpoint; no code/build error was reported. ADB host access is approved and its daemon starts, but no physical device was connected at the checkpoint.
- On resume: rerun `GRADLE_USER_HOME=/mnt/d/code/Lean4Android/.gradle-user-home ./gradlew testDebugUnitTest :app:assembleDebug --stacktrace`; verify the APK's native entries and asset exclusions/size, then connect the arm64 device, install the APK, run first-start sysroot installation, and execute the version, valid/invalid file, LSP, Lake, cancellation, cold-restart, Unicode-path, and performance probes.
- APK assembly and device execution have not passed yet.
