# Lean4Android

An Android-native Lean 4 code editor and proof-assistant environment. The project is in its initial feasibility phase: the Android shell and toolchain contracts exist, while the Lean Android cross-build is still under construction.

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the roadmap and [IMPLEMENTATION_HISTORY.md](IMPLEMENTATION_HISTORY.md) for completed work and decisions.

## Current prerequisites

- JDK 17
- Android SDK Platform 36 and Build Tools 35.0.0 or newer
- Android NDK 28.2.13676358 (needed for toolchain work, not the Kotlin-only shell)
- arm64 device or emulator running Android 10/API 29 or newer

Build with the committed Gradle 8.13 wrapper:

```shell
./gradlew :app:assembleDebug
```

The resulting application intentionally reports that the Lean toolchain is missing until the M1 cross-build places the native executable artifacts and sysroot assets.
