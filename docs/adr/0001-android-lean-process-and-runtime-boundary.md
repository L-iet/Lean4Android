# ADR 0001: Android Lean process and runtime boundary

- Status: accepted
- Date: 2026-08-13
- Applies to: M1 onward

## Context

Android applications targeting API 29 or later cannot execute code copied into their writable app home. Lean and Lake also expect a conventional colocated distribution, while Android installs native libraries in a read-only, randomized APK directory. Lean 4.32 requires standard-library server, private, IR, and interface facets in addition to ordinary `.olean` files. Android's Bionic allocator tags heap pointers on supported devices, but Lean's runtime stores pointer metadata in ways that are incompatible with those tags.

The API-33 reference tablet has successfully run packaged Lean and Lake executables, direct valid and invalid checks, `lake lean`, `lake build`, an LSP diagnostics lifecycle, graceful shutdown, and forced transport cleanup. APK replacement also proved that executable compatibility links can be refreshed while retaining the writable sysroot.

## Decision

Use supervised child processes as the supported execution model. Package all executable code as ABI-specific native APK artifacts and execute it only from `ApplicationInfo.nativeLibraryDir`. Do not copy, download, or import executable code into writable storage.

Install data into an immutable, versioned sysroot under `noBackupFilesDir`. On activation and every APK update, atomically refresh writable compatibility links for Lean and Lake using the current native-library path. Bind activated data to the complete packaged runtime-manifest digest and provide a separate user-triggered full integrity audit; routine checks remain fast.

All launches go through the typed command factory and process boundary. They use absolute paths, argument arrays rather than a shell, a cleared/minimal environment, app-owned temporary and timezone data, concurrent stream handling, timeouts, and explicit cleanup. Direct Lean, Lake, and `lake serve` remain distinct command capabilities.

Retain these narrow upstream adaptations as a reviewed canonical patch:

- Android uses Lean's ELF/PIC/dynamic-loader build behavior.
- Generated C uses the pinned NDK API/ABI compiler driver.
- libc++ and libc++abi are linked statically for the packaged runtime.
- Lean honors `LEAN_SYSROOT` before executable-relative discovery.
- Lean disables Bionic heap pointer tagging before initializing its allocator.

The MVP supports checking Lean source, Lean-library builds, and Lean server workflows. It does not promise a shell, Git/network dependency resolution, arbitrary package build scripts, downloaded native plugins, or compiling/running native Lean executables. A future native-build capability requires a separate decision and threat model.

## Alternatives considered

### Embed Lean behind JNI

JNI could avoid child-process installation and supervision constraints, but it would require a broad lifecycle, cancellation, stream/protocol, crash-isolation, and memory-ownership integration. The validated child-process path provides stronger failure isolation and already supports the required workloads, so JNI would add risk without solving an observed defect.

### Package a small native launcher

A launcher could normalize paths and signals before starting Lean. The current typed environment, compatibility links, and process supervisor already do this without another native component. Reconsider a launcher only if API/device coverage exposes process-tree or executable-discovery behavior that cannot be fixed at the Kotlin boundary.

### Place a conventional distribution in writable storage

This would simplify Lean's desktop path assumptions but violates Android's executable-code policy for the supported target SDK. Writable storage is data-only.

## Consequences

- The project keeps process crashes isolated from the Android UI process and preserves Lean's normal CLI/LSP behavior.
- APK native paths are mutable installation metadata, never toolchain identity.
- The canonical Lean patch and device conformance suite are release-critical inputs.
- The full runtime currently consumes about 2.18 GB writable storage, so M1.6 delivery work remains a product gate.
- API 29 and current-Android coverage may still force reconsideration. If packaged child execution or cleanup proves unreliable, reopen this ADR and compare a launcher and JNI using the failing evidence.
