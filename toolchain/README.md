# Android Lean toolchain

This directory owns reproducible construction of the native Lean distribution. It intentionally contains no downloaded binaries.

## Pinned inputs

| Input | Version |
|---|---|
| Lean | `v4.32.1` |
| Android NDK | `28.2.13676358` |
| Target ABI | `arm64-v8a` |
| Minimum API | `29` |
| Toolchain revision | `android1` |

The output ID is `lean-4.32.1-android1`. Executable ELF files are staged as `liblean_exe.so` and `liblake_exe.so` in generated APK `jniLibs`; they must never be copied to and executed from writable app storage. The audited non-executable sysroot is filtered into generated APK assets. See [MVP_AND_REBUILDING.md](../MVP_AND_REBUILDING.md) for the complete build and incremental-rebuild procedure.

## Build sequence

The build is split deliberately: a native host bootstrap produces the compiler that generates C, then the Android stage compiles that C and the runtime with NDK Clang. LibUV and OpenSSL are built statically for Android first; GMP and mimalloc are disabled for the initial feasibility artifact.

From the repository root:

```shell
toolchain/scripts/fetch-sources.sh
toolchain/scripts/build-host.sh
toolchain/scripts/build-android-deps.sh
toolchain/scripts/build-android.sh
toolchain/scripts/assemble-distribution.sh
```

Work and output directories are ignored. Set `LEAN4ANDROID_JOBS` to control parallelism or `LEAN4ANDROID_WORK_DIR`/`LEAN4ANDROID_OUTPUT_DIR` to relocate large build trees. Every source checkout is pinned to `versions.toml`; local source drift is rejected. `assemble-distribution.sh` audits each ELF with the NDK `llvm-readelf`, rejects non-AArch64 files and unexpected dynamic dependencies, and emits a hashed `manifest.json` conforming to `manifest.schema.json`.

The produced distribution is staged into generated APK inputs by the app's Gradle pre-build tasks. The source distribution remains separate and ignored; only the audited output is eligible for staging. Physical-device conformance remains required after every runtime, layout, or asset-filter change.
