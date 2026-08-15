# Lean4Android

An Android-native Lean 4 code editor and proof-assistant environment. A pinned Lean 4.32.1/Lake 5.0.0 arm64 toolchain cross-builds, packages, installs, and runs on the API-33 reference tablet. Runtime/layout hardening, core delivery prototypes, and the offline two-module project lifecycle are complete there; broader API coverage and the product editor remain in progress.

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

Installer-owned Lean/Lake link refresh and typed commands pass update and corruption-repair tests. `core-lsp` provides typed framing/dispatch plus retained service ownership. `core-project` provides compatible templates, atomic saves, supported Lake commands, and safe repository-level import/export; its complete error/fix/build/export/delete/reimport scenario passes offline on-device. M3 and M4 are device-proven through project import/export, tabs, line numbers, appearance, live diagnostics/goals/inspection, resizable and collapsible panes, named projects, guarded project/file/folder rename and deletion, a hierarchical independently scrollable file tree with an extreme-landscape floor, and a Samsung-IME-tested Lean/Unicode symbol row with docked-IME Output suppression. M5 remains the next Mathlib size/performance gate. Before M6 beta hardening, M5.1–M5.3 then add general project text files and explicit stdin/stdout/stderr workflows, direct definition/reference navigation, and symbol/font preferences; Unicode abbreviation completion and word wrap remain post-beta M7 work.
