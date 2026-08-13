# Lean4Android

An Android-native Lean 4 code editor and proof-assistant environment. The project is in its runtime-feasibility phase. A pinned Lean 4.32.1/Lake 5.0.0 arm64 toolchain now cross-builds, packages, installs, and runs on the API-33 reference tablet; runtime-layout hardening, delivery design, API coverage, and the product editor remain in progress.

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the roadmap, [IMPLEMENTATION_HISTORY.md](IMPLEMENTATION_HISTORY.md) for completed work and decisions, and [MVP_AND_REBUILDING.md](MVP_AND_REBUILDING.md) for build, deployment, and incremental-rebuild procedures.

## Current prerequisites

- JDK 17
- Android SDK Platform 36 and Build Tools 35.0.0 or newer
- Android NDK 28.2.13676358 (needed for toolchain work, not the Kotlin-only shell)
- arm64 device or emulator running Android 10/API 29 or newer

Build the audited Android toolchain first as described in [MVP_AND_REBUILDING.md](MVP_AND_REBUILDING.md), then build the toolchain-bearing APK with the committed Gradle 8.13 wrapper:

```shell
GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew --no-build-cache testDebugUnitTest :app:assembleDebug
```

The current debug APK contains immutable Lean/Lake executables plus the complete filtered core/Std sysroot. On the reference tablet, fresh installation, `lean --version`, valid/invalid checking, Unicode paths, Lean-library `lake build`, `lake lean`, an LSP lifecycle handshake, cold restart, and child termination pass. The APK is about 803.7 MB and the writable sysroot about 2.18 GB, so this monolithic packaging is a feasibility artifact rather than the intended release-delivery design.

Installer-owned Lean/Lake link refresh and typed deterministic command construction now pass an APK-update test on the reference tablet. A new `core-lsp` module provides tested byte-accurate JSON-RPC framing and lifecycle messages. Current priorities are supervised real-server lifecycle/document diagnostics, installer integrity and recovery, offline/API-level coverage, and the core-toolchain delivery spike.
