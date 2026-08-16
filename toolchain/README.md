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

Long-running scripts announce named stages and emit a heartbeat every 30 seconds. Operations with a measurable denominator, including distribution copies and manifest hashing, report percentage, item, and byte progress. Set `LEAN4ANDROID_PROGRESS_INTERVAL_SECONDS` to a positive integer to change the interval. Set `LEAN4ANDROID_LOG_FILE` to tee combined stdout/stderr to a durable log while retaining terminal output and the script's real exit status:

```shell
LEAN4ANDROID_LOG_FILE="$PWD/toolchain/output/android2-assembly.log" \
LEAN4ANDROID_ANDROID_BUILD_VARIANT=android-arm64-release \
LEAN4ANDROID_TOOLCHAIN_ID=lean-4.32.1-android2 \
  toolchain/scripts/assemble-distribution.sh
tail -f toolchain/output/android2-assembly.log
```

Use a log path outside any output directory that the invoked script recreates. The same logging and interval variables apply to the toolchain build/fetch/delivery scripts and the Python manifest/conformance scripts. Native CMake, Make, Gradle, Git, curl, tar, zip, and OpenSSL output is preserved in the log; their own percentage display remains authoritative when a reliable repository-level denominator is unavailable.

M5's release-artifact candidate is deliberately isolated from the accepted `android1` build and distribution. After producing host stage1 with a sufficiently recent pinned CMake, build and assemble the candidate with:

```shell
export LEAN4ANDROID_CMAKE_COMMAND="$PWD/toolchain/work/mathlib-producer/producer-tools/cmake-3.31.10-linux-x86_64/bin/cmake"
LEAN4ANDROID_HOST_TARGET=stage1 LEAN4ANDROID_JOBS=4 \
  toolchain/scripts/build-host.sh
LEAN4ANDROID_HOST_PRODUCER_STAGE=stage1 \
LEAN4ANDROID_ANDROID_BUILD_VARIANT=android-arm64-release \
LEAN4ANDROID_JOBS=4 \
  toolchain/scripts/build-android.sh
LEAN4ANDROID_ANDROID_BUILD_VARIANT=android-arm64-release \
LEAN4ANDROID_TOOLCHAIN_ID=lean-4.32.1-android2 \
  toolchain/scripts/assemble-distribution.sh
```

These overrides do not change the checked-in active toolchain identity, app constants, `work/build/android-arm64`, or `output/lean-4.32.1-android1`. Promotion to `android2` is a separate reviewed step after manifest, ELF, APK, migration, offline, and physical-device conformance.

The produced distribution is staged into generated APK inputs by the app's Gradle pre-build tasks. The source distribution remains separate and ignored; only the audited output is eligible for staging. Physical-device conformance remains required after every runtime, layout, or asset-filter change.
