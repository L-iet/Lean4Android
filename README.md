# Lean4Android

Lean4Android is an Android-native, offline-capable Lean 4 editor and proof
environment. It packages a pinned Lean 4.32.1/Lake 5.0.0 AArch64 runtime inside
the application, manages contained Lake projects, and provides a Jetpack Compose
editor with tabs, diagnostics, goals, hover, completion, definition/references,
build/run output, explicit stdin modes, and project import/export.

M0–M4 and the Android1 M5.0–M5.3 product work are physically accepted on the
API-33 reference tablet. Android1 is the foreground M6 hardening lane. Android2
is a frozen, isolated Mathlib candidate; do not build it as a normal newcomer
workflow. See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the roadmap and
[IMPLEMENTATION_HISTORY.md](IMPLEMENTATION_HISTORY.md) for observed evidence.

## Fastest path to a debug APK

The supported command-line build host is x86-64 Linux. Windows users should use
[WSL 2 with Ubuntu](https://learn.microsoft.com/windows/wsl/install). The current
cross-build scripts hard-code the Linux x86-64 Android NDK host tools, so native
Windows and macOS are not currently documented full-toolchain build hosts.

### 1. Install host tools

On Ubuntu or Ubuntu under WSL 2:

```shell
sudo apt update
sudo apt install -y \
  git openjdk-17-jdk python3 jq cmake ninja-build build-essential pkg-config perl \
  unzip zip curl ca-certificates
```

Official installation references: [JDK 17](https://docs.oracle.com/en/java/javase/17/install/),
[Python 3](https://www.python.org/downloads/),
[Git](https://git-scm.com/downloads), and
[Ubuntu package management](https://ubuntu.com/server/docs/how-to/software/package-management/).

### 2. Install the pinned Android SDK packages

Install [Android Studio](https://developer.android.com/studio/install) or the
[Android command-line tools](https://developer.android.com/tools/sdkmanager), then
place/use the SDK at `.android-sdk` in the repository. Install the exact packages:

```shell
.android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root="$PWD/.android-sdk" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;35.0.0" \
  "ndk;28.2.13676358" \
  "cmake;3.22.1"

.android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root="$PWD/.android-sdk" --licenses
```

The NDK is needed only when rebuilding Lean and its native dependencies. It is
not needed merely to edit Kotlin/Compose code when an audited Android1
distribution is already available.

### 3. Clone and configure

```shell
git clone <repository-url> Lean4Android
cd Lean4Android
make configure-sdk
```

Replace `<repository-url>` with this repository's clone URL. Run `make help` to
see all supported convenience targets.

### 4. Supply or build the audited Lean distribution

The APK cannot be assembled from Kotlin sources alone. Gradle expects:

```text
toolchain/output/lean-4.32.1-android1/manifest.json
toolchain/output/lean-4.32.1-android1/native/
toolchain/output/lean-4.32.1-android1/sysroot/
```

These large generated files are intentionally ignored by Git. If a trusted
maintainer or CI artifact provides this exact audited directory, copy/extract it
at the path above and skip the native cross-build. Verify its provenance and
manifest; there is currently no public download URL declared by this repository.

If no trusted prebuilt distribution is available, build it from pinned sources:

```shell
make doctor-toolchain
make toolchain JOBS=4
```

This fetches pinned Lean, LibUV, and OpenSSL sources, builds the host producer,
cross-builds AArch64 dependencies and Lean, then audits the distribution. It is
the slow, storage-intensive path. Read
[MVP_AND_REBUILDING.md](MVP_AND_REBUILDING.md) before starting it.

### 5. Build and test the APK

```shell
make doctor
make apk
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The first Gradle run downloads dependencies. A warm Kotlin/APK regression has
typically taken about 4–6 minutes on the recorded WSL/reference workstation;
large cold or interrupted staging runs have taken 30–50 minutes. The current APK
is roughly 806 MB and expands a roughly 2.2 GB writable sysroot, so allow generous
host and device space.

### 6. Install on an AArch64 Android 10+ device

Enable USB debugging, authorize the computer, and use the repository's ADB:

```shell
ADB_LIBUSB=1 .android-sdk/platform-tools/adb devices -l
ADB_LIBUSB=1 .android-sdk/platform-tools/adb install --user 0 -r -t \
  app/build/outputs/apk/debug/app-debug.apk
```

The product boundary is `arm64-v8a`, API 29 or newer. The `--user 0` convention
is required for the reference Samsung because it also has a Secure Folder user.
See [MVP_AND_REBUILDING.md](MVP_AND_REBUILDING.md) for device validation and USB
recovery details.

## Common developer commands

```shell
make help               # describe targets
make test               # all host unit tests
make ui                 # app UI unit tests + Kotlin compilation
make apk                # tests + Android1 debug APK
make all JOBS=4         # full native toolchain + APK
```

The Makefile is a small front end to the checked-in scripts. The scripts remain
the source of truth and emit durable logs/heartbeats for long operations.

## Documentation map

- [MVP_AND_REBUILDING.md](MVP_AND_REBUILDING.md): complete beginner setup,
  full/incremental builds, artifact reuse, deployment, recovery, and timing.
- [ARCHITECTURE.md](ARCHITECTURE.md): application modules, runtime/process,
  storage, LSP, UI, packaging, and data-flow architecture.
- [ANDROID1_REBUILD.md](ANDROID1_REBUILD.md): focused recovery of the production
  Android1 APK from a preserved audited distribution.
- [docs/editor/CURRENT_UI_OVERVIEW.md](docs/editor/CURRENT_UI_OVERVIEW.md): current
  visible UI and where its code lives.
- [docs/editor/UI_STYLE_ARCHITECTURE.md](docs/editor/UI_STYLE_ARCHITECTURE.md):
  Compose design-system boundary and migration rules.
- [toolchain/README.md](toolchain/README.md): native toolchain implementation notes.
- [mathlib/README.md](mathlib/README.md): isolated Android2 Mathlib producer lane;
  not part of the normal Android1 build.

## Demo

![Lean4Android Editor view](Screen_Recording_20260912_153952(1).gif)
