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

The output ID is `lean-4.32.1-android1`. Executable ELF files must be packaged as `app/src/main/jniLibs/arm64-v8a/liblean_exe.so` and `liblake_exe.so`; they must never be copied to and executed from writable app storage. Non-executable sysroot data will be packaged as assets once the cross-build is working.

## Next toolchain task

Map Lean's host bootstrap and generated-C target stages for the pinned release, then implement a containerized build. The build must emit `manifest.json` conforming to `manifest.schema.json` and verify every ELF file with `readelf` before it can be copied into the Android app.

