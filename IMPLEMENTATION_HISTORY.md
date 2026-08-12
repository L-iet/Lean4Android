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
- The Android Lean link, distribution audit, APK integration, and device execution have not passed yet.
