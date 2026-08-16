# Rebuilding the Android1 APK

This document records how to reproduce and preserve the accepted Android1 APK without confusing it with the isolated Android2 candidate.

## Milestone boundary

`lean-4.32.1-android1` is the accepted runtime for the completed M4 product line. The last completed Android1 milestone was **M4.6 — Project and tree lifecycle actions**, including the preceding M4.1–M4.5 work and its API-33 physical-device acceptance. M5 Mathlib beta is the first milestone using the release-ABI `lean-4.32.1-android2` candidate.

The two installed application identities are deliberately different:

- Android1/default: `org.lean4android.app`
- Android2 isolated candidate: `org.lean4android.app.android2candidate`

Gradle's ordinary debug output filename is shared. A later `assembleDebug` replaces `app/build/outputs/apk/debug/app-debug.apk`, regardless of which toolchain produced it. Do not treat that path as a permanent archive.

## Preconditions

Run from the repository root. Use JDK 17, the checked-in Gradle wrapper, the project-local Android SDK, and the project-local Gradle state. Before starting, inspect `git status --short` and preserve unrelated changes.

The accepted Android1 distribution must exist at:

```text
toolchain/output/lean-4.32.1-android1
```

Confirm its manifest is structurally valid:

```shell
jq empty toolchain/output/lean-4.32.1-android1/manifest.json
```

The default Gradle configuration selects Android1. Do not pass `leanToolchainId`, `leanPackagedSysrootBytes`, `leanRuntimeManifestSha256`, or `isolatedToolchainCandidate` overrides when rebuilding the accepted APK.

## Rebuild the APK from the preserved distribution

Choose a durable log outside directories Gradle recreates:

```shell
mkdir -p toolchain/output/apks
set -o pipefail
GRADLE_USER_HOME="$PWD/.gradle-user-home" \
  ./gradlew --no-build-cache --no-daemon --console=plain \
  testDebugUnitTest :app:assembleDebug \
  2>&1 | tee toolchain/output/lean-4.32.1-android1-apk-build.log
```

After a successful build, immediately preserve the shared output under an identity-specific name:

```shell
cp -p app/build/outputs/apk/debug/app-debug.apk \
  toolchain/output/apks/lean4android-m4.6-lean-4.32.1-android1-debug.apk
sha256sum \
  toolchain/output/apks/lean4android-m4.6-lean-4.32.1-android1-debug.apk \
  | tee toolchain/output/apks/lean4android-m4.6-lean-4.32.1-android1-debug.apk.sha256
```

The copied APK and checksum are generated recovery artifacts, not automatically version-controlled release assets.

## Required host checks

Before calling the rebuilt APK reproducible:

1. Check `app/build/outputs/apk/debug/output-metadata.json` reports application ID `org.lean4android.app` and does not contain the Android2 candidate suffix.
2. Use the project build-tools `aapt dump badging` on the preserved APK and confirm package `org.lean4android.app`, minimum API 29, and target API 36.
3. Confirm the embedded `toolchain-manifest.tsv` identifies `lean-4.32.1-android1` and matches the expected filtered-manifest digest.
4. Confirm the APK contains `Init.olean` and the expected eight `arm64-v8a` native entries, while excluded build-only sysroot facets remain absent.
5. Run `git diff --check`, inspect `git diff` and `git status --short`, and record the resulting APK size and SHA-256 in `IMPLEMENTATION_HISTORY.md`.

Refer to the host and APK inspection procedures in `MVP_AND_REBUILDING.md` for the canonical detailed commands and acceptance boundaries.

## Device validation

Rebuilding alone does not re-establish physical-device acceptance. If the APK will replace the accepted device installation, use the project ADB with its libusb backend and explicitly target owner user 0:

```shell
ADB_LIBUSB=1 .android-sdk/platform-tools/adb install --user 0 -r -t \
  toolchain/output/apks/lean4android-m4.6-lean-4.32.1-android1-debug.apk
```

An update install preserves app-private state. Installer, layout, locator, or migration changes also require a genuinely fresh-data test; a preserved healthy sysroot is not sufficient. Revalidate cold launch, runtime verification, offline Lean/Lake Run, LSP/editor readiness, M4.6 project lifecycle behavior, and exact package-scoped no-orphan cleanup. Record device and network mutations and restore the intended state.

## Full Android1 toolchain recovery

The APK-only procedure above reuses the accepted audited distribution. If that distribution is absent or untrusted, do not reconstruct it from Android2. Follow the complete pinned sequence in `MVP_AND_REBUILDING.md` and `toolchain/README.md` using the default Android1 paths:

1. Build or validate the pinned host bootstrap.
2. Build the pinned Android dependencies.
3. Build the Android target in `toolchain/work/build/android-arm64` with the canonical checked-in Lean patch and default producer stage.
4. Run `toolchain/scripts/assemble-distribution.sh` without Android2 override variables, producing `toolchain/output/lean-4.32.1-android1`.
5. Require the complete source-integrity, AArch64 ELF/archive, dynamic-dependency, manifest/hash, file-count, and residual-process audits before using that distribution in Gradle.
6. Then run the APK rebuild and validation procedure above.

Preserve any known-good Android1 distribution until its replacement passes every applicable host, APK, migration, offline, and physical-device check.
