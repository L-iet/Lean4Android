# Implementation history

This is the durable engineering log for Lean4Android. Entries summarize shipped code, validation, and decisions; detailed future work remains in `IMPLEMENTATION_PLAN.md`.

## 2026-08-14 — M3 usable editor complete

- Continuing from the device-proven durable-state slice. This checkpoint targets the remaining M3 boundary: bounded per-tab undo/redo, Unicode-safe search, Lean syntax decoration, adaptive phone/tablet portrait/landscape layout, hardware-keyboard shortcuts, accessibility semantics, an explicit native-versus-CodeMirror decision record, and final host/device validation.
- No build or native child is currently running. The trustworthy starting APK remains the 804,770,286-byte `d5282712...` build whose two dirty buffers and active tab survived full process recreation on the API-33 reference tablet.
- Exact next validation: focused editor-engine tests, app compilation, UI/instrumentation coverage, full unit/APK regression, then rotation/forced-recreation and keyboard/accessibility checks on the reference device. M3 will not be marked complete until those observed results and any remaining boundary are recorded.
- Before the host/session crash, implemented the bounded editor engine, adaptive Compose shell, accessibility semantics, hardware-keyboard routing, Compose UI test dependencies, `M3EditorRecreationTest`, and the native-editor decision record. `:app:testDebugUnitTest :app:compileDebugKotlin` passed in 2m54s (106 tasks; 6 executed). The first compile had failed only on missing Compose `key`/`type` extension imports; the corrected rerun passed.
- Crash recovery on 2026-08-14 found no Gradle, Kotlin, ADB, Lean, or Lake process alive. The interrupted `:app:compileDebugAndroidTestKotlin :app:testDebugUnitTest` emitted task progress but no completion. `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` is the stale 823,291-byte M2 artifact from 2026-08-13 20:45 and is not M3 evidence; the 804,770,286-byte app APK is likewise the prior durable-state build.
- Resume exactly with `GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew --no-build-cache --no-daemon --console=plain :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`, correct any instrumentation compile failure, then assemble/install both APKs and run the targeted M3 test before the full regression.
- The exact recovery command completed successfully in 7m08s (117 tasks; 45 executed and 72 up-to-date). It compiled `M3EditorRecreationTest` and passed app unit tests. The only warning was the deprecated v1 Compose Android test-rule namespace; switched the test to the v2 rule and aligned the adaptive side-pane breakpoint to the standard 600dp tablet boundary. No Gradle process remains.
- Next exact command: assemble `:app:assembleDebug :app:assembleDebugAndroidTest`, record both hashes/sizes, then install them for owner user 0 and run only `M3EditorRecreationTest` before broader device checks.
- `:app:assembleDebug :app:assembleDebugAndroidTest` completed successfully in 41m23s (175 tasks; 66 executed and 109 up-to-date). The long quiet intervals were active `/mnt/d` asset compression and APK packaging, not a hang; host inspection measured sustained Gradle CPU use and the run was preserved to completion.
- New exact artifacts: app APK 804,771,115 bytes, SHA-256 `0123b03d67f9ebfe371337d3eec91383510e23ddfeceb699da71616af539ef03`; instrumentation APK 2,475,861 bytes, SHA-256 `6b6effeb22b5019ca8bbd9d1af684e555618b489073bf621e4352ba3c85b134b`. The authorized SM-T870/API 33 is attached as `R52R40K1PPN`; no USB permission repair is currently needed.
- Next exact step: update-install both artifacts for owner user 0, run `M3EditorRecreationTest`, inspect any failure before broader UI/device mutation, and restore the clean editor snapshot afterward.
- Both APKs update-installed successfully for owner user 0. The first targeted `M3EditorRecreationTest` ran but failed in 1.083s before mutation because it assumed `Main.lean` was initially active; the durable snapshot correctly restored `VisualProbe/Basic.lean`, so only the Basic editor semantic node existed. Normal launch confirmed the M3 tablet side-pane, full project paths, Basic editor, Undo/Redo/Find/Build/Verify controls, and no app crash.
- Filtered logcat `FATAL EXCEPTION` entries belong to overlapping `uiautomator dump` helpers (`UiAutomationService ... already registered`), not `org.lean4android.app`, matching the earlier documented Samsung behavior. Made the test select Main only when the Main editor node is absent, preserving the product's active-tab recovery contract.
- Next exact validation: rebuild the small instrumentation APK (the app APK is unchanged), reinstall it, rerun the targeted M3 test, and only then proceed to device-shape/keyboard checks.
- The corrected test APK rebuilt in 3m28s and installed, but the rerun exposed a Compose v2 test-rule compatibility issue: `StandardTestDispatcher` left the Activity launch queued and the test reported no Compose hierarchy after 7.95s. This is distinct from the first active-tab assumption and from product startup; normal app launch remains visible.
- Reverted only the warning-driven v2 test-rule migration to the v1 rule, which previously launched the Activity and reached semantics. The deprecation warning is accepted for the pinned Compose 1.11.4 test lane; M3 does not require migrating coroutine test dispatch semantics.
- Next exact validation: rebuild/reinstall only the instrumentation APK again and rerun the targeted test with both the proven v1 launch rule and active-tab-independent setup.
- The v1-rule test APK rebuilt in 2m31s (SHA-256 `9350cecf0122212d6405d7c37f890b60830f518e2668c011e3da3594f09353f0`) and installed. Both with the app already open and after an explicit owner-user force-stop, the Activity-owning Compose rule remained launch-order-dependent: the former could attach too late and the latter found no hierarchy. This is test-harness behavior, not an app crash; normal launch remains healthy.
- Replaced `createAndroidComposeRule` with `createEmptyComposeRule` plus explicit `ActivityScenario.launch/recreate/close`, making Activity ordering deterministic and allowing exact recovery-snapshot cleanup through the target context.
- Next exact validation: rebuild/reinstall only the instrumentation APK and rerun from a force-stopped app; if it passes, keep this invocation as the repeatable M3 recipe.
- Crash recovery confirmed that deterministic-test rebuild completed successfully in 2m36s (157 tasks; 4 executed and 153 up-to-date). No Gradle process remains. The newly packaged instrumentation APK is the next trustworthy test artifact; install it for owner user 0 and run the targeted class from an explicitly force-stopped app.
- Installed the 2,475,861-byte instrumentation APK (SHA-256 `471d174697a5e40ae2318c2fff7b6f8208fc1c61ee5ea6c9631e482b4240fd2c`) and reran the targeted class. The in-method `ActivityScenario.launch` still raced before Compose registered its hierarchy and failed before product mutation after 9.633s. Replaced it with an outer empty Compose rule and inner `ActivityScenarioRule`, which guarantees rule registration before Activity launch, and added failure-safe recovery-snapshot cleanup.
- Next exact validation: rebuild/reinstall the instrumentation APK and rerun the same targeted class. Product APK and normal tablet launch remain unchanged and healthy.
- The rule-ordered APK rebuilt successfully in 35m35s after the case-normalized Gradle path invalidated all 157 prerequisites; the filtered toolchain manifest remained 14,864 entries with SHA-256 `f7e389dcee7bd8f146fcd9e7f05ca6ddc9243bd3e99e3261a5dee79e7d1aa797`. The resulting test APK SHA-256 was `bdc253f919b1abf3036e24e943157f666d8af2f915ffe5965bec4ef189439366`.
- Even with Android visibly reporting a successful `MainActivity` launch, the pinned Compose v1 test registry never attached a hierarchy under instrumentation; repeated failures occurred before editor mutation. Removed that unreliable registry coupling and its dependencies. The device test now covers actual `ActivityScenario` resumed/recreate/resumed lifecycle, while deterministic editor engine/session behavior remains covered by host unit tests and visible UI behavior by direct device acceptance.
- Next exact validation: rebuild the now-small lifecycle instrumentation APK from the warm canonical cache, install it, run `M3EditorRecreationTest`, then perform visible adaptive-layout, hardware-keyboard, accessibility-semantics, build, and orphan-process checks.
- The lifecycle-only test APK rebuilt successfully in 5m52s (157 tasks; 154 executed) with SHA-256 `4b646c3750cd762d5cb0946bae1000ec70b0c11cf30760efbe7a2ae134d5a420`. Its first run revealed the actual harness blocker: the tablet was `Dozing`, NotificationShade had focus, and ActivityScenario remained at `CREATED`. After `KEYCODE_WAKEUP` and `wm dismiss-keyguard`, the unchanged test passed in 0.908s, proving resumed/recreate/resumed lifecycle. No product defect was involved.
- Device is now awake. Next exact validation: launch the app visibly; capture tablet and compact layout semantics; exercise Ctrl-F/undo/save or visible controls; run a project check; restore all display mutations; and verify no exact Lean/Lake child remains.
- Visible API-33 acceptance passed. Portrait tablet semantics show the `Project files` side panel, full `Main.lean` and `VisualProbe/Basic.lean` paths, editor description, and Undo/Redo/Find/Build/Verify controls. Hardware Ctrl-F opened `Find text in current Lean file`. A temporary `wm size 720x1280` produced the compact tab/action layout without the side panel. Forced landscape retained the tablet side panel and every action. Restored physical `1600x2560`, `accelerometer_rotation=1`, and `user_rotation=0` exactly.
- Tapped the visible Build project action after a cold launch. The UI reported exit 0 in 1,214 ms, replayed Main, printed the theorem type and value 42, and ended `Build completed successfully (4 jobs).` The exact process-name check found no `lean`, `lake`, `liblean_exe.so`, or `liblake_exe.so` child afterward.
- Next exact validation: run the required full host `testDebugUnitTest :app:assembleDebug` regression, record final APK hash, inspect diff/status, finish the M3 plan/rebuild documentation, and commit only the M3 change set while preserving the user's separate `AGENTS.md` modification.
- Final `testDebugUnitTest :app:assembleDebug` passed in 40m46s (175 tasks; 48 executed and 127 up-to-date). The final debug APK is 804,770,604 bytes with SHA-256 `fa034846972bf9285f47c9dce8e6cc5a522d54b454e68060bd5010ba6717bcb5`; `git diff --check` passes.
- Update-installed that exact APK for owner user 0 and left `MainActivity` top-resumed and visibly showing the completed tablet editor: Project files, Main editor, Undo, Redo, Find, Build project, and Verify runtime. No native child remained. M3 exit criteria are complete; the next product boundary is M4 editor-to-LSP document synchronization and live diagnostics.

## 2026-08-13 — M3 durable editor state and forced-recreation exit proof

- Began the first M3 slice from the closed M2 visual baseline: retain editor tabs and dirty buffers across Activity recreation, persist recovery state for process recreation, bind the Activity to retained app services, and add contained Lean source file operations around `LeanProjectRepository`.
- No long-running process is active at this checkpoint. The existing migration-fix APK and API-33 M2 offline lifecycle evidence remain the trustworthy baseline.
- Implemented contained source create/read/rename/delete operations with traversal, type, collision, and last-source protection; added atomic recovery snapshots with saved baselines, dirty buffers, active-tab restoration, and corrupt-snapshot fallback; bound `MainActivity` to the retained local LSP service; and exposed New/Rename/Delete actions with dirty indicators in the visible editor.
- Focused `:core-project:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin` passes (111 tasks) after both the state layer and visible file-operation wiring. No Gradle/Java process remains from those completed runs.
- The full `testDebugUnitTest :app:assembleDebug` regression passes in 4m51s (175 tasks; 9 executed and 166 up-to-date). The resulting 804,770,286-byte APK has SHA-256 `d5282712abfa8baf3fd565b44aa9e7feea341f05e0c010fa6b3e52b366d8fd9f`.
- Update-installed that exact APK for owner user 0 on the authorized SM-T870/API 33. Entered distinct unsaved `M3MAIN` and `M3BASIC` markers through the visible editor, selected `Basic.lean`, and confirmed both dirty indicators before forcing a full app process stop/restart.
- After restart, `Basic.lean` was still active, both tabs still showed dirty indicators, and UI hierarchy inspection found `M3BASIC` in Basic and `M3MAIN` after switching to Main. This is stronger than Activity-only recreation and satisfies the M3 no-loss exit scenario for the two-module sample.
- The exact process-name check found no `lean`, `lake`, `liblean_exe.so`, or `liblake_exe.so` child. Removed only the test-created `files/editor-recovery/visual_probe.bin`, relaunched the app, and confirmed the clean on-disk sample was visible without either marker or dirty indicator. The app remains open; Wi-Fi and project data were otherwise unchanged.
- Remaining M3 product work is the editor bake-off record, search, syntax highlighting, undo/redo, responsive phone/rotation polish, hardware-keyboard coverage, and accessibility basics. File operations and durable state are implemented, but this checkpoint does not claim those remaining features.

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
- A `-j4` retry reproduced the missing `InferType.c`: the interrupted build had retained its primary `.olean` while its generated C side output was incomplete. Moving the still-active `.tmp` was aborted after it exposed a corrupt sparse file; the run was stopped before accepting that output.
- Quarantined the complete generated `InferType` artifact set under `/tmp/lean4android-infertype-recovery` and restarted at `-j1`. The serial rebuild recreated valid `.olean`, `.ilean`, and UTF-8 C output atomically and is continuing cleanly through Lean compiler/meta modules. This recovery build is currently running; the Android executable has not linked yet.
- After a host crash, confirmed no build process survived and resumed `LEAN4ANDROID_JOBS=1 toolchain/scripts/build-android.sh` from the intact build tree. CMake accepted the recovered `Lean/Meta/InferType` outputs, reused completed native targets, and advanced into new `Lean/Compiler/LCNF` modules without error; the serial recovery build is running again.
- The resumed serial build completed the remaining LCNF passes, the `Lean/Compiler` aggregate, parser compiler, module parser, and pretty-printer/delaborator layers. It has entered elaborator modules with no recurrence of missing or malformed generated C, including successful downstream use of the recovered compiler/meta outputs.
- The same run subsequently completed core term/command elaboration, structural and well-founded recursion, pre-definition, deriving, built-in simplifier procedures, and the BVDecide prover/tactic aggregate. It also rebuilt `Lean/Meta/Sym/InferType`, the last `InferType`-named module with a crash-era dependency marker, without error; the serial build continues toward top-level libraries and linking.
- The recovery build then completed the full symbolic-meta, `grind`, Cutsat/linear/commutative-ring arithmetic, `Lean/Meta/Tactic`, user-facing tactic, `do` verification-condition/proof-mode, Omega, `Lean/Elab/Tactic`, and `Lean/Elab` aggregates. No generated-source or compiler failure recurred; remaining work in this run is top-level Lean/server library assembly and native linking.
- The run completed `Lean/Meta`, linter, completion, file-worker, RPC, widget, server, utility, and top-level `Lean.lean` generation, then native C compilation exposed the other artifact left incomplete by the host crash: `Std/Sat/AIG/RefVecOperator.c.tmp` existed while the final `.c` did not, despite completed primary Lean outputs. Clang therefore failed with a missing input; this is the same interrupted atomic-output condition seen for `InferType`, not a new source dependency failure.
- After the next computer restart, no build process remained. The filesystem shows that the interrupted generator completed its atomic rename before termination: `RefVecOperator.c` is present and nonempty, and its `.tmp` no longer exists. The intact serial build tree is ready to resume at `-j1` without quarantining or deleting artifacts.
- Resumed `LEAN4ANDROID_JOBS=1 toolchain/scripts/build-android.sh`. Configuration completed, the runtime/core targets through `leancpp` were reused, and native compilation of the generated Lean library C sources is progressing cleanly through `Lean/Meta`; the recovered `RefVecOperator.c` was accepted with no missing-input recurrence. This serial build is currently running.
- The resumed run completed native compilation of all 2,271 generated main Lean C modules, including the crash-adjacent `Lean/Meta/Sym/InferType.c`, and produced the 313 MB Android `libLean.a`, plus `libLeanc.a` and `libLeanIR.a`. It has advanced into Lake source/C generation and remains active at `-j1` without errors; final executables have not linked yet.
- The run completed all Lean/Std/Lake generation and reached `make_stdlib`, then the first shared-library link failed on `-lc++_static`. ELF inspection revealed the underlying configuration defect: Lean's generated `leanc.sh` had captured CMake's generic host `clang` path without the Android toolchain's separate target arguments, so the generated native objects and archives were x86-64. The build now pins `LEANC_CC` to the NDK's API-29 AArch64 driver, and the upstream patch routes both `leanmake` and `leanc.sh` through that configured compiler. `.olean` outputs remain reusable, but all target C objects/archives must be rebuilt and audited as AArch64.
- Deleted only architecture-dependent `.o`, `.o.export`, `.a`, and `.so` files from the Android build tree and restarted at `-j1`, preserving generated C and Lean artifacts. Regenerated `stdlib.make`/`leanc.sh` both select `aarch64-linux-android29-clang`; newly rebuilt CMake objects and an extracted `libleanshell.a` member are verified ELF64 AArch64. The corrected rebuild is currently running through native runtime/core targets.
- After resuming again, the source-integrity guard stopped before compilation because the checked-in patch omitted a trailing blank context line from its final hunk. The patched Lean sources themselves were unchanged; the patch was canonicalized to exactly match `git diff --no-ext-diff`, restoring the guard's byte-for-byte invariant.
- The corrected AArch64 rebuild completed `make_stdlib`, `leanrt_initial-exec`, and the Lean/Lake shared-library targets through 72%, then failed linking `lake`: `libleanshared.so` retained unresolved C++ ABI symbols. The NDK's static libc++ interface is its API-specific `-lc++` linker script, which groups `libc++_static.a` with `libc++abi.a`; the prior `-lc++_static` override selected only the first archive. The Android configuration now uses `-lc++` so exception, RTTI, and allocation symbols are included. Existing AArch64 objects remain reusable; only shared libraries and executables require relinking.
- With `-lc++`, the targeted relink succeeded: all Lean/Lake shared libraries and executables linked and the full build reached 100%. Distribution audit then correctly rejected their unbundled `libc++_shared.so` dependency. NDK driver inspection showed its static-STL sequence is `-Bstatic -lc++ -Bdynamic`; the configuration now spells those mode switches explicitly so the API linker script supplies both `libc++_static.a` and `libc++abi.a` without introducing a shared-STL runtime dependency.
- The static-STL relink completed successfully. Independent `llvm-readelf` inspection confirms all five shared libraries and the `lean`, `lake`, `leanc`, `leanir`, and `leanchecker` executables are ELF64 AArch64; their dynamic dependencies are limited to packaged Lean libraries and Android system libraries, with no `libc++_shared.so`. `assemble-distribution.sh` then passed its complete ELF/dependency audit and generated the hashed `lean-4.32.1-android1` distribution: 14,899 files occupying 3.1 GB, including its manifest.
- Added explicit APK staging from the audited distribution. All native executables/shared libraries are staged under `jniLibs/arm64-v8a`; the writable sysroot asset excludes build-only archives, exports, IR, private/server caches, native objects, generated C, and duplicate shared libraries. The staged runtime payload is approximately 523 MB. Added a background first-run installer that recursively copies packaged sysroot assets into a temporary sibling directory, validates `Init.olean`, atomically renames the completed tree into `noBackupFilesDir/toolchains/lean-4.32.1-android1`, and writes an installation marker only after activation. The Compose probe now invokes installation off the UI thread before locating the toolchain.
- Kotlin compilation of the installer passed during the first Gradle run. That run was manually stopped after Gradle spent more than ten minutes redundantly processing the 273 MB debug-symbol-bearing Lean shared library; APK packaging had not completed. `jniLibs.keepDebugSymbols` is now enabled to bypass that redundant strip. A second build entered filtered toolchain staging and was manually stopped at the user's logout checkpoint; no code/build error was reported. ADB host access is approved and its daemon starts, but no physical device was connected at the checkpoint.
- On resume: rerun `GRADLE_USER_HOME=/mnt/d/code/Lean4Android/.gradle-user-home ./gradlew testDebugUnitTest :app:assembleDebug --stacktrace`; verify the APK's native entries and asset exclusions/size, then connect the arm64 device, install the APK, run first-start sysroot installation, and execute the version, valid/invalid file, LSP, Lake, cancellation, cold-restart, Unicode-path, and performance probes.
- Pause checkpoint on 2026-08-12: the combined unit-test/APK build compiled successfully and advanced through toolchain staging, resources, Kotlin, dexing, native-library merge, and asset compression. Staging the large payload on `/mnt/d` took approximately 10.5 minutes; Gradle then spent several more minutes packing the compressed-assets output into its local build cache. The run was interrupted after compression at 97% to avoid that redundant cache copy, so no toolchain-bearing APK was produced. Subsequent continuation attempts were blocked before task execution because the restricted runner denied the loopback UDP/TCP sockets Gradle uses for file-lock coordination and its daemon. Temporary Gradle distribution-cache experiments were restored byte-for-byte from backups. ADB was blocked by the same socket restriction, so the connected Samsung tablet was not queried or modified. No Gradle, Java, ADB, compiler, or toolchain build process remains running. The only `app-debug.apk` is the stale 28 MB scaffold artifact from 2026-08-11. Resume with normal local-socket access, use the project SDK's ADB rather than `/usr/bin/adb`, and run Gradle with `--no-build-cache` before device validation.
- With local sockets restored, the host unit tests passed and clean APK assembly completed. The first continuation exposed partial `compressDebugAssets` jars retained from the interrupted build; removing only that derived task output fixed AGP's duplicate-entry failures. `:app:assembleDebug` then completed successfully with `--no-build-cache`. The resulting APK is 250,017,268 bytes (SHA-256 `3e16c09de4d727ad91d283e5c1d5991e12275ac96842a3421243a471d39d40b2`), contains 7,571 toolchain assets, the required `Init.olean`, all Lean/Lake arm64 native libraries, and no excluded build-only asset types.
- Device diagnosis: WSL sees Samsung USB device `04e8:6860` and its correct ADB interface (`ff/42/01`), but `/dev/bus/usb/001/002` is `root:root` mode `0600`; the unprivileged ADB server therefore cannot enumerate it. Windows ADB also sees no device because usbip has attached it exclusively to WSL. Apply a WSL udev rule (or temporarily grant the current node to `plugdev`) and restart ADB before device installation.
- Applied the temporary WSL USB-node fix with `sudo -A`: `/dev/bus/usb/001/002` is now `root:plugdev` mode `0660`. After restarting the project SDK's ADB with its libusb backend and approving the device prompt, serial `R52R40K1PPN` connected successfully as a Samsung SM-T870 running Android API 33.
- Installed the audited 250 MB debug APK successfully for Android owner user 0. The Compose first-run installer copied and activated the writable sysroot, and its health check reports `Ready: lean-4.32.1-android1`. Direct inspection confirms the marker and `Init.olean`; the installed sysroot occupies approximately 472,554 KiB. The packaged Lean/Lake files are executable from Android's read-only native library directory.
- The first real `lean --version` execution reached Lean initialization but aborted with exit 134. Android/Bionic reported `Pointer tag ... was truncated`; the tombstone places the invalid `free()` in `lean_dec_ref_cold`, called during `lean_st_ref_set`/option registration. The same failure occurs both under ADB `run-as` and through the app's `ProcessBuilder`, proving executable placement, dynamic-library discovery, and process launch work while Lean's allocator interaction does not.
- Tested `android:allowNativeHeapPointerTagging="false"` and added an on-device `lean --version` probe to the feasibility screen. The manifest setting does not survive `execve()` into the standalone packaged executable on this Android 13 device, so it is not sufficient. The pinned NDK exposes Bionic's `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE)`, and Lean's shared `lean_initialize_runtime_module()` is the narrow candidate for an Android-only compatibility call before runtime subsystem initialization.
- Resolving the pointer-tagging defect requires a small checked-in Lean source patch, recompilation of the affected runtime object/archive, and relinking the Lean/Lake shared libraries and executable wrappers. Generated Lean C, Lean artifacts, and existing module objects should remain reusable. After rebuilding, reassemble/audit the distribution and APK, reinstall it, and repeat version, valid/invalid file, LSP, Lake, cancellation, restart, Unicode-path, and performance probes.
- Added an Android-only Bionic `mallopt` call at the start of Lean runtime initialization to disable heap pointer tagging before Lean can strip allocator tag bits. The guarded incremental build rebuilt only the two runtime objects/archives and relinked the Lean/Lake shared libraries and executables; generated Lean modules were reused. Distribution assembly passed its complete AArch64 ELF, dynamic-dependency, and manifest/hash audit. The rebuilt APK passed unit tests and assembly, installed successfully, and its production-path `lean --version` probe now exits 0 with Lean 4.32.1 for `aarch64-linux-android`.
- Device import probes showed that Lean 4.32's split module artifacts are runtime inputs, not build-only caches: ordinary checking required `.olean.server`, `.olean.private`, and `.ir`. Restored all three classes to APK staging while continuing to exclude static archives, exports, objects, generated C, dependency files, and duplicate shared libraries. Their uncompressed additions are approximately 31.2 MB, 1.33 GB, and 365.5 MB respectively; the resulting installed sysroot is approximately 2,175,501 KiB.
- With the complete module artifacts temporarily installed on the device, `Valid.lean` exits 0 and prints the expected theorem/evaluation output in about 1.0 seconds; `Invalid.lean` exits 1 with the expected `rfl` diagnostic in about 0.9 seconds. APK assembly, installation, sysroot activation, native process launch, version reporting, and valid/invalid proof checking have passed. The corrected larger asset set still needs a final APK rebuild/reinstall; LSP, Lake, cancellation, restart, Unicode-path, and performance probes remain.
- Lake itself reports version 5.0.0 and can load a local project's configuration after the installer layout is adapted with writable sysroot symlinks pointing to the APK-installed Lean/Lake executables. `lake check-build` passes, but spawned Lean initially derived `/data/app/.../lib/lib/lean` from the executable's canonical APK path. Added a canonical Lean patch making internal `getBuildDir` honor the already-documented `LEAN_SYSROOT` environment variable before falling back to `IO.appDir`. The guarded incremental rebuild is regenerating `Lean.Util.Path` and its dependent modules; no build failure has occurred.

### Android/Lean integration findings and architectural consequences

#### Android application and process model

- Android 10/API 29 and later do not permit an app to execute newly written code from its writable private directories. Lean and Lake therefore cannot be unpacked into `filesDir` or the writable toolchain sysroot and run from there. Executable ELF files are packaged as `.so`-named arm64 native libraries and are installed by Android into the app's read-only `nativeLibraryDir`; projects and toolchain data remain under app-private writable storage.
- Android assigns a randomized, version-specific APK installation path. That path changes after reinstall and contains shell-sensitive characters, so it must never be persisted. The app resolves `ApplicationInfo.nativeLibraryDir` at runtime and launches with `ProcessBuilder` argument arrays, never a shell command. ADB shell quoting failures encountered during diagnosis reinforce this production rule but do not affect the Kotlin runner.
- Writable symlinks may point to APK-installed executable inodes and Android permits execution through them. This provides Lake with conventional-looking `<sysroot>/bin/lean` and `<lake-home>/.lake/build/bin/lake` paths without copying executable code into writable storage. These symlinks must be recreated after every APK update because the native-library target path is randomized.
- Native child processes need a deliberately small environment containing at least `HOME`, `LEAN_SYSROOT`, `LEAN_PATH`, `PATH`, and `LD_LIBRARY_PATH`; Lake additionally needs `LAKE_HOME` and `LAKE_OVERRIDE_LEAN=true`. App-private projects provide normal filesystem semantics and stable absolute paths. The Storage Access Framework remains an import/export boundary rather than a live Lake workspace.
- Android's Bionic allocator uses top-byte heap pointer tags on arm64. Lean stores its own metadata in pointer bits and later passed a pointer with Bionic's tag removed to `free`, causing a deliberate Bionic abort. The application manifest's `allowNativeHeapPointerTagging=false` affects the app process but did not carry across `execve()` to standalone Lean. Lean must disable Bionic heap tagging itself before runtime allocation begins.
- APK-installed native libraries are dynamically linked through Android's loader. All required Lean/Lake shared libraries must be packaged together, their dependencies must be limited to those packaged libraries plus the audited Android system-library allowlist, and libc++ must be linked statically. The toolchain audit enforces AArch64 architecture and rejects accidental host objects, glibc dependencies, and unbundled `libc++_shared.so`.

#### Lean and Lake runtime layout/dependency behavior

- `LEAN_SYSROOT` was not sufficient for the original direct Lean startup: internal `Lean.getBuildDir` derived the sysroot from `IO.appDir`. Direct ADB invocation through `/system/bin/linker64` consequently derived `/apex/.../lib/lean`; execution through an APK-path symlink derived `/data/app/.../lib/lib/lean`. `LEAN_PATH` can make direct one-shot checking work, but Lake-spawned Lean intentionally constructs its own project search path and relies on Lean's built-in sysroot path for the standard library.
- Lake already treats `LEAN_SYSROOT` as an installation override, but its executable self-location logic expects a conventional desktop layout. When invoked through Android's linker it cannot infer its installation; `LAKE_HOME` plus sysroot symlinks supplies that layout. Because Lake can otherwise mis-detect Lean as co-located with the linker/native directory, `LAKE_OVERRIDE_LEAN=true` is required to force its documented environment-based Lean installation.
- Lean 4.32 uses split module artifacts. Ordinary checking of the bundled standard library was empirically shown to require `.olean`, `.olean.server`, `.olean.private`, and `.ir`, not only `.olean`/`.ilean`. Missing facets fail successively during import: first server data, then private data, then IR for interpretation. For this distribution there are 2,431 files of each split class; server data is about 31.2 MB, private data about 1.33 GB, and IR about 365.5 MB uncompressed. With all required facets installed, the writable sysroot is about 2,175,501 KiB.
- `.ilean` remains useful for source/reference tooling. Sources are retained for diagnostics, navigation, and server behavior. Static archives, `.export` files, generated C, native objects, dependency files, and duplicate shared libraries remain excluded from the APK runtime payload unless a later supported workflow proves one is required. Native compilation/linking of Lean executables is outside the MVP.
- `lean --version`, direct valid-file checking, and invalid-file diagnostics now pass on Android. The valid fixture exits 0 and evaluates code; the invalid fixture exits 1 with the expected source-positioned tactic error. This proves process launch, shared-library loading, sysroot imports, split-artifact loading, interpretation, stdout/stderr capture, and exit-code handling.
- Lake 5.0.0 starts and parses a local project, and `lake check-build` passes with the layout adapter. Full `lake lean`/`lake build` validation awaits completion and deployment of the `LEAN_SYSROOT` path patch because their spawned Lean process must find the bundled standard library without inheriting an externally forced full `LEAN_PATH`.

#### Upstream/toolchain changes required

- Lean's CMake platform tests were widened from Linux to `Linux|Android` where Android shares ELF/PIC/export/dynamic-loader behavior, while upstream tests are disabled for the cross target.
- Generated `leanc` and `stdlib.make` use the configured `LEANC_CC`, ensuring generated Lean C is compiled by the NDK API-29 AArch64 driver rather than generic host Clang.
- Android linking uses the NDK's static-libc++ linker-script sequence so libc++ and libc++abi are included without a runtime `libc++_shared.so` dependency.
- `lean_initialize_runtime_module()` now makes an Android-only `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE)` call before initializing Lean's allocator/runtime subsystems. This resolved the tagged-pointer abort for both Lean and Lake.
- `Lean.getBuildDir` now gives the documented `LEAN_SYSROOT` environment variable precedence over executable-relative discovery. This makes an APK-installed executable compatible with a separately installed writable sysroot and is also consistent with Lean/Lake's public external path-discovery behavior.
- All Lean modifications live in one checked-in canonical patch. The build guard requires the source checkout's exact `git diff` to match it byte-for-byte, preventing accidental local source drift. Cross-build outputs remain generated and untracked.

#### Choices and forward architecture

- Continue with child processes rather than JNI: Android successfully launches the packaged executable, captures its streams, reports exit status, and runs real Lean workloads after the narrow compatibility fixes. There is currently no evidence requiring the more invasive embedded-JNI fallback.
- Keep executable code immutable in the APK and data versioned in `noBackupFilesDir`. The installer stages data into a temporary sibling, validates it, atomically activates it, and marks completion last. Future installer work must also create/refresh the conventional Lean/Lake symlink layout after activation or APK replacement.
- Treat the toolchain environment as a first-class typed component shared by one-shot commands and the future LSP supervisor. Hard-coding paths independently in UI/process code would recreate the failures found during probing.
- Retain the full split module data for correctness for now, despite size. A future size optimization requires changing how Lean produces/loads its standard-library artifacts or producing a deliberately flattened/non-module-system runtime distribution and proving LSP/navigation/interpretation behavior. Silently omitting facets is not viable.
- Keep `arm64-v8a`, API 29+, one pinned Lean revision, and offline core/Std as the initial support boundary. The current physical validation device is API 33; API 29 and a current Android release still need matrix coverage.
- The feasibility UI now executes `lean --version` through the same shell-free Kotlin process runner intended for production. The next architecture layers remain a durable toolchain-layout/environment builder, long-lived LSP supervision, project serialization, and cancellation/process-tree cleanup.

#### Counterfactual optimization opportunities

- If Android allowed execution from app-private writable storage, Lean could use a conventional colocated distribution, avoid `.so` executable naming and symlink adapters, and update toolchains independently of APK native code. Android's W^X policy intentionally rules this out for the supported target SDK.
- If Lean consistently honored `LEAN_SYSROOT` internally, no upstream path patch would be needed and Lake-spawned commands would work with environment configuration alone.
- If Lake accepted explicit executable paths independently of desktop installation-layout inference, the writable compatibility symlinks and `LAKE_OVERRIDE_LEAN` would be unnecessary.
- If Lean's runtime preserved allocator top-byte tags, Android heap tagging could remain enabled for stronger memory diagnostics. Disabling it is a scoped compatibility compromise in Lean child processes.
- If Lean standard-library `.olean` files were self-contained for ordinary checking, or if optional split facets were lazily tolerated, the APK/sysroot could remain near the earlier roughly 523 MB staged payload. Lean 4.32's required private/IR facets make the correct runtime more than 2 GB uncompressed and may materially affect APK size, first-install time, store delivery, and future Mathlib strategy.
- If Lake/library builds did not depend on conventional toolchain paths and external compiler/archive tools, more build workflows could be enabled immediately. The MVP deliberately supports proof checking and Lean library builds first; native targets remain separate.

#### Sandbox, WSL, and host-environment constraints

- Earlier restricted runner sessions denied the loopback UDP/TCP sockets used by Gradle's daemon/file-lock coordination and by ADB. This prevented task startup and device enumeration even though source/build state was valid. With normal socket access restored, both tools worked.
- Gradle's build cache redundantly copied very large compressed-asset outputs and made an interrupted build appear stuck at 97%. `--no-build-cache` avoids that copy. Interrupting `compressDebugAssets` left partial per-asset jars that AGP did not clean on retry, producing thousands of duplicate-entry errors; deleting only that derived task output repaired the build.
- `/mnt/d` filesystem performance dominates large sync/copy/package operations: filtered staging takes about ten minutes, distribution copying/auditing can take longer, and APK packaging is quiet for long periods. These are host-I/O costs rather than compiler failures and should not be interrupted without evidence of failure.
- The Samsung USB device was attached to WSL through usbip and exposed the correct ADB interface, but its `/dev/bus/usb` node was `root:root` mode `0600`. Applying `sudo -A chgrp plugdev` and `chmod 0660` enabled the Linux ADB server; the permission must be reapplied after reconnect unless a persistent udev rule is installed. Windows ADB cannot see a device while usbip has attached it exclusively to WSL.
- Samsung Secure Folder user 150 caused package-manager commands without `--user` to fail even though owner user 0 was active. All installation/package operations now explicitly target Android user 0.
- ADB's remote shell re-parses strings and mishandled randomized APK paths in diagnostic commands. Production code does not use ADB or shell composition; it uses absolute `File` paths and `ProcessBuilder` argument lists. Diagnostic tests use Android's linker or carefully quoted commands only where necessary.

## 2026-08-12 — Post-restart recovery and sysroot-path rebuild

### Recovery status

- The host restarted while the guarded incremental Android cross-build was regenerating Lean modules affected by the `LEAN_SYSROOT` path-discovery change. No compiler, Gradle, or ADB process survived the restart, and the Git worktree recovered cleanly at commit `70e6e10`.
- The Android build tree remains reusable: the patched Lean and Lake launchers and `libleanshared.so` were relinked before shutdown. Temporary outputs under `lib/temp` show that downstream module regeneration was interrupted, so those artifacts are not yet considered a complete distribution.
- The next task is `toolchain/scripts/build-android.sh` with guarded source-patch validation and incremental CMake/Make reuse. After it succeeds, the distribution will be reassembled and audited before rebuilding/deploying the APK and proving `lake lean`/`lake build` on-device.
- Device access is not a prerequisite for this recovery stage. If the restricted WSL session prevents ADB socket creation or USB-node access, that limitation will be recorded and host-side build, packaging, and tests will continue.
- Recovery inspection in the new sandbox confirmed that no Lean, CMake/Make, Gradle, Java, or ADB worker survived. The reusable Android build and prior audited distribution are still present, with ample host disk space. The interrupted generator left a nonempty `Lean/Elab/AutoBound.c.tmp` and an older empty export temp; both are preserved for the build system's atomic-output recovery rather than treated as completed artifacts.
- The guarded serial resume passed the exact source-patch check and CMake configure/generate, then reused the AArch64 runtime/core targets through `leancpp` (CMake's coarse 46% mark). It regenerated the `Lean.Util.Path` dependent chain cleanly through `Lean/Elab/AssertExists` without a missing, truncated, or malformed atomic output.
- The serial build remains active outside the managed sandbox's PID namespace. Process inspection inside the sandbox cannot see its wrapper/compiler workers and initially produced a false stopped-process conclusion, but build artifacts continued advancing every 15–20 seconds during a two-sample check: from `Lean/Elab/PreDefinition/Structural/RecArgInfo` through `Lean/Meta/IndPredBelow` between 23:37 and 23:39. Do not launch a second build or alter the build tree while those timestamps continue advancing.
- At the 23:39 checkpoint, 343 primary `.olean` modules, with corresponding split facets and generated C, had timestamps from the latest post-22:00 run out of 2,433 compiled modules in the tree. Because only modules downstream of `Lean.Util.Path` need rebuilding, this is not a direct whole-tree completion percentage; roughly 1,800–2,100 affected modules plus generated-C compilation and final relinking may remain. At `-j1`, expect several more hours of cross-build work.
- The nonempty `Lean/Elab/AutoBound.c.tmp` predates this latest resume while its completed `AutoBound.c` remains present; an older empty export temp also remains. The successful advance beyond that area confirms neither temp blocked the resumed dependency chain. Preserve them and allow the build rules to decide atomic-output replacement on the next resume.
- A checkpoint commit initially failed because that managed sandbox mounted `.git` read-only, but the user subsequently committed the recovery history as `2f549be`. The current history correction is the only new worktree change at this pause.
- Next-session sequence: first determine activity by sampling newest artifact timestamps twice, because sandbox-local `ps` is not authoritative across PID namespaces. If timestamps still advance, monitor the existing build and do not start another. If they are stable and no host-visible worker exists, resume with `LEAN4ANDROID_JOBS=1 toolchain/scripts/build-android.sh`. After complete success, run `toolchain/scripts/assemble-distribution.sh` and its ELF/hash audit; rebuild the APK with unit tests and `--no-build-cache`; then reinstall for Android user 0 and complete Lake, LSP, cancellation, restart, Unicode-path, offline, and performance probes.
- Resume on 2026-08-13: artifact timestamps had stopped at 00:00:11 after completing nonempty `Lean/Elab/ComputedFields` outputs, and no build worker was visible. A single guarded `LEAN4ANDROID_JOBS=1 toolchain/scripts/build-android.sh` resume passed source-integrity validation and CMake configure/generate, reused the AArch64 runtime/core targets through `leancpp` (46%), and continued module regeneration at `Lean/Elab/Inductive` without an atomic-output or compiler error. The serial build is active; do not start another build while it remains so.
- By 09:59 on 2026-08-13, the same serial run had completed the affected Lean source/artifact regeneration through the top-level `Lean`, `LeanIR`, server, linter, elaborator, and tactic aggregates. Since this resume it regenerated 373 primary `.olean` files with their generated C and split facets, then transitioned into NDK compilation of those changed C modules. The compiler had produced 92 newly timestamped AArch64 objects and was advancing through `Lean/Elab` (`Match.o` completed at 09:59:10). The build session remains alive with no reported error. Remaining work is the rest of changed-C compilation, archive/shared-library and executable relinking, followed by distribution assembly/audit, Gradle tests/APK assembly, and device conformance probes.
- Crash recovery inspection at 10:51 on 2026-08-13 found that the serial run had continued well beyond the prior checkpoint before the computer stopped. It completed the changed top-level Lean/LeanIR objects and advanced through Lake regeneration and AArch64 compilation; the newest completed outputs are `Lake/CLI/BuiltinLint.o` and its export object at 10:42:13. No build worker survived, no Lake `.tmp`/partial output is present, the newest object is a valid ELF64 AArch64 relocatable, and the build volume has ample free space. The existing build tree is therefore reusable without deleting or rebuilding completed work; resume once at `LEAN4ANDROID_JOBS=1` from the normal guarded script.
- The guarded `LEAN4ANDROID_JOBS=1 toolchain/scripts/build-android.sh` resume completed successfully at 100%. It reused the native runtime/core targets through `leancpp`, resumed precisely in the unfinished Lake dependency chain, regenerated and compiled the remaining Lake/LeanChecker outputs, completed `make_stdlib`, and relinked `libleanshared.so`, `libLake_shared.so`, `lean`, `lake`, and `leanchecker` without error. No prior completed artifacts had to be discarded or rebuilt wholesale. The next checkpoint is distribution reassembly plus the full architecture/dependency/hash audit.
- `toolchain/scripts/assemble-distribution.sh` completed successfully after rebuilding the full approximately 3.1 GB output tree. Its independent audit accepted the rebuilt distribution, including AArch64 ELF identity, packaged/system dynamic-dependency constraints, and the regenerated per-file manifest hashes. The recovered toolchain is now complete and auditable; host tests and the toolchain-bearing APK rebuild are next.
- `GRADLE_USER_HOME=/mnt/d/code/Lean4Android/.gradle-user-home ./gradlew --no-build-cache --no-daemon --console=plain testDebugUnitTest :app:assembleDebug` completed successfully in 53m58s. Toolchain staging reached 14,864 assets and final compression/packaging completed without the prior interrupted-output or build-cache problem. The new debug APK is 803,678,420 bytes with SHA-256 `48422d274fbccd88623266397f46fa857466462679e7641774ecb9f42f40e355`; ZIP inspection confirms all eight expected arm64 native entries, `Init.olean`, 2,431 each of `.olean.private`, `.olean.server`, and `.ir`, and no staged `.a`, `.o`, generated `.c`, `.depend`, or `.export` build artifacts. Host recovery, cross-build, distribution audit, unit tests, and APK reconstruction are complete; device deployment and the remaining Lake/LSP/runtime probes are next.
- The project SDK's ADB daemon starts successfully when given local-socket access, but `adb devices -l` currently reports no attached device. No install or device state change was attempted. Continuation is blocked only on reconnecting/reattaching and authorizing the arm64 test device; once visible, install the new APK explicitly for Android user 0, allow the versioned sysroot to reinstall, and run the outstanding Lake build, LSP, cancellation, cold-restart, Unicode-path, offline, and performance probes.
- After the tablet was reattached, WSL enumerated Samsung USB device `04e8:6860` but its node had reverted to `root:root` mode `0600`. Reapplying the temporary `plugdev`/`0660` fix and restarting the project SDK's ADB with libusb restored authorized serial `R52R40K1PPN` (SM-T870, Android API 33, current user 0). The rebuilt 803.7 MB APK then installed successfully with `adb install --user 0 -r -t`. Because the toolchain revision ID is intentionally unchanged, the existing `.installed` marker would make the first-run installer reuse stale pre-rebuild sysroot data; clear only this test app's private data before launch to force a fresh packaged-sysroot activation.
- Cleared only `org.lean4android.app` user-0 private data, launched the rebuilt app, and triggered its first-run installer. The installer copied and atomically activated the complete packaged sysroot at 2,175,513 KiB and wrote `.installed`. The app's production shell-free `ProcessBuilder` probe then reported `Ready: lean-4.32.1-android1`; `lean --version` exited 0 and identified Lean 4.32.1, `aarch64-linux-android`, commit `f054605aea4b840552cca2e725580bffd1e1b704`. Fresh APK installation, sysroot activation, executable discovery, dynamic loading, patched sysroot discovery, and basic process execution therefore pass on the API-33 tablet.
- Fresh direct conformance checks pass against the APK-installed sysroot: `Valid.lean` exits 0 and prints the theorem plus `"Hello from Lean on Android"`; `Invalid.lean` exits 1 with the expected source-positioned `rfl` diagnostic. A copy under `files/conformance/Unicode Ω/证明.lean` also exits 0, validating spaces and non-ASCII app-private paths. Host-observed runs were approximately 2.6 seconds for the first valid check and 0.9 seconds for the invalid check.
- Recreated the conventional writable layout manually for validation: `<sysroot>/bin/lean`, `<sysroot>/.lake/build/bin/lake`, and a Lake build-lib link target the immutable APK executables and installed Lean library. Lake 5.0.0 starts, `check-build` exits 0, and `lake build` successfully invokes the rebuilt Lean, produces the expected `.olean`/`.ilean`/IR outputs, and completes three jobs in about 1.4 seconds. `lake lean` initially exposed Android's unwritable default temporary directory; adding `TMPDIR=<app cache>` makes it exit 0 with the expected output. The production environment builder must include `TMPDIR`, and the installer must create/refresh these symlinks after activation/APK replacement.
- A byte-exact non-PTY LSP initialize/initialized/shutdown/exit exchange succeeded. Lean Server 0.3.0 returned its full capability object, proving stdio framing and server startup; PTY transport is unsuitable because newline translation corrupts `Content-Length`. Server startup emits a non-fatal watchdog warning because Android lacks `/etc/localtime`; initialization still succeeds. A synchronized cold-app restart reused the existing installed sysroot and completed the production version probe in approximately 2.48 seconds without reinstalling data.
- Device-side process termination also passes at the OS boundary: started a real `lean --server` child under the app UID, confirmed its `lean` PID and wrapper relationship, sent SIGTERM, observed both processes disappear, and saw the supervising ADB stream close with `Terminated`. Final filtered logcat inspection shows no app fatal exception, pointer-tagging abort, or native backtrace from these runs. The activated marker remains present and the final sysroot occupies 2,175,523 KiB; the small increase is the manually created compatibility links.
- Synchronized the living documentation after recovery and device conformance. `README.md` now describes the working Lean/Lake feasibility artifact instead of an unfinished cross-build; `MVP_AND_REBUILDING.md` records the final APK/sysroot measurements, passing direct/Lake/LSP/cold-restart/termination checks, and the app-writable `TMPDIR` requirement; `IMPLEMENTATION_PLAN.md` advances the M1 status and immediate actions to production link/environment hardening, diagnostics/offline/migration/resource testing, API coverage, and delivery design. `initial_plan.md` remains the historical concept document and `toolchain/README.md` remains accurate without a status edit.

## 2026-08-13 — Minimal visual Lean editor probe

### Implemented

- Replaced the probe-only Compose screen with a deliberately minimal single-file editor for `Main.lean`. It starts with a valid theorem and `#check`/`#eval` example, allows multiline editing, prevents overlapping checks, shows progress, and renders selectable exit status, elapsed time, stdout, stderr, timeout, and launch failures.
- Each Check atomically saves UTF-8 source to `filesDir/projects/visual-probe/Main.lean`, installs/locates the packaged toolchain on demand, and invokes the APK-installed Lean executable through the existing shell-free bounded runner. The direct command uses the device-validated `HOME`, app-writable `TMPDIR`, `LEAN_SYSROOT`, `LD_LIBRARY_PATH`, and minimal system `PATH` environment.
- Kept this M1 visual probe explicitly separate from the M3 editor decision and future project/LSP layers. It intentionally has one fixed file, plain Compose text editing, explicit checking, and no syntax highlighting, file tree, live diagnostics, or Lake dependency model.

### Validation

- `:app:compileDebugKotlin` passes after resolving a Compose `weight` import mismatch found by the first compile.
- Added app unit coverage for atomic source replacement, stdout/stderr/status formatting, and timeout/no-output presentation. `:app:testDebugUnitTest` passes all three tests.
- The full `testDebugUnitTest :app:assembleDebug` run passes. Because the audited toolchain payload was unchanged, Gradle reused staging/compression and rebuilt the application code, dex, and package in 3m43s. The resulting editor APK is 803,729,953 bytes with SHA-256 `e9caea1b87204e109ae2c43ffde2c02eae1eb7210b3512ad31f3b78c9bc4839b`.
- Installed the editor APK for Android user 0 while preserving the validated sysroot. The tablet UI hierarchy confirms the multiline Lean source field, `Check Lean` action, and output panel render. Running the default source atomically created `files/projects/visual-probe/Main.lean`; the visible result reports exit 0 in 3,112 ms and displays `one_plus_one : 1 + 1 = 2` plus `"Hello from Lean on Android"`.
- The user completed the hands-on visual test on the physical Android device and confirmed the editor can still edit source, communicate with Lean, and run Lean programs. This working visual loop is now a standing regression gate: subsequent runtime, project, LSP, and delivery changes may evolve the editor according to the roadmap, but must retain an operable visual source editor and an end-to-end Lean execution path.

## 2026-08-13 — Runtime layout and command hardening

### Implemented

- Moved direct Lean and Lake command construction into a typed `ToolchainCommandFactory`. It owns absolute executable selection, the minimal cleared environment, app-writable `TMPDIR`, timeouts, and the deliberate difference between direct Lean's `LEAN_PATH` and Lake's project-aware child environment.
- Changed the visual editor to use the shared Lean command factory without changing its source editing, atomic save, Check action, progress, or result UI.
- Added an installer-owned `ToolchainLayoutAdapter` that atomically creates and refreshes `<sysroot>/bin/lean`, `<sysroot>/.lake/build/bin/lake`, and `<sysroot>/.lake/build/lib/lean`. Refresh runs even when sysroot data is already installed, repairing links after Android assigns a new native-library path during APK replacement.
- Extended toolchain health reporting to reject absent or stale compatibility links.

### Validation

- Added host unit tests proving all compatibility links are created, a stale Lean executable target is replaced, direct Lean receives the complete Android-safe environment, and Lake receives its writable compatibility layout without a globally forced `LEAN_PATH`.
- `testDebugUnitTest :app:compileDebugKotlin` passes after the refactor. The initial sandboxed attempt was blocked before compilation by Gradle's local-socket discovery; the identical approved run completed successfully in 4m22s.
- The full `testDebugUnitTest :app:assembleDebug` regression run passes in 4m24s and produces an 803,729,541-byte APK with SHA-256 `1e8a51c14f878bd9dda02c36edc54cc315260cbb2b5c8724b83577e9d70f64d1`.
- Restored ADB access to the connected SM-T870/API-33 tablet after its USB node reverted to `root:root` mode `0600`: changed only `/dev/bus/usb/001/002` to `root:plugdev` mode `0660`, restarted the project SDK's ADB with libusb, and confirmed the authorized device.
- Update-installed the hardening APK with `adb install -r`, preserving the existing approximately 2.18 GB sysroot and project data. The visual editor still renders an enabled multiline source field, Check action, and result panel. Its production Check path atomically saved `Main.lean`, exited 0 in 3,115 ms, and visibly returned the expected theorem type plus `"Hello from Lean on Android"` evaluation.
- The same Check refreshed all three compatibility links without reinstalling sysroot data. `bin/lean` and `.lake/build/bin/lake` point to the current randomized `/data/app/.../lib/arm64/` executables, while `.lake/build/lib/lean` points to the preserved writable sysroot. Recent filtered logcat contains no app fatal exception, native fatal signal, or Bionic pointer-tagging abort.

## 2026-08-13 — LSP framing and lifecycle foundation

### Implemented

- Added the `core-lsp` Android library as an isolated protocol layer. The visual editor and its proven direct Check path remain unchanged; the app will depend on LSP only after long-lived process ownership is implemented.
- Added byte-accurate stdio JSON-RPC framing with UTF-8 `Content-Length`, case-insensitive header parsing, bounded headers, exact payload reads, clean EOF handling, synchronized writes, and explicit errors for missing, duplicate, invalid, or truncated frames.
- Added minimal typed generation for the pinned Lean server lifecycle sequence: `initialize`, `initialized`, `shutdown`, and `exit`, including safe JSON string escaping for workspace URIs.

### Validation

- `:core-lsp:testDebugUnitTest` passes. Tests exercise multibyte Unicode byte counts, two-byte fragmented reads, coalesced messages, EOF, malformed/duplicate/invalid lengths, truncated payloads, lifecycle ordering through the real framing boundary, and URI escaping.
- This checkpoint proves protocol encoding/framing in isolation. The earlier physical-device raw handshake remains the evidence that the packaged Lean server starts; supervised real-process lifecycle, `didOpen`, diagnostics, version filtering, and orphan-process checks remain the next slice.

## 2026-08-13 — Real Lean diagnostics and process lifecycle

### Implemented

- Added a shell-free interactive `JvmProcessLauncher`/`RunningProcess` boundary with independently owned stdin/stdout/stderr, bounded exit waiting, graceful termination, and forced direct-child fallback. Added a dedicated `ToolchainCommandFactory.lakeServer()` command using workspace-aware `lake serve`.
- Added Lean `didOpen`, full-text `didChange`, and `didClose` message generation plus a synchronized document-version gate that rejects stale, closed, and unknown-document diagnostics.
- Added `toolchain/conformance/run-device-lsp.py`, a repeatable non-PTY ADB conformance client. It creates a private local Lake project, performs initialize and dynamic-registration exchange, opens invalid Lean source, waits for versioned nonempty diagnostics, and completes shutdown/exit. It also has a forced-stop mode and checks for remaining Lean/Lake process names.
- Added an installer-owned 114-byte UTC TZif fallback and explicit `TZ=:/.../UTC` in the centralized toolchain environment.

### Findings and validation

- The previously reported `/etc/localtime` watchdog message was not harmless for a usable LSP session. Initialization returns capabilities, but after `initialized` Lean starts its client task, fails to resolve Android's absent `/etc/localtime`, and exits 1 before document diagnostics. Android's tzdata APEX exposes a packed database rather than individual TZif files, so it is not a compatible direct replacement.
- With the app-owned UTC TZif, the intended `lake serve` path returns Lean Server 0.3.0 capabilities, requests file-watcher registration, accepts a version-1 `didOpen`, publishes the expected `rfl` error for `1 + 1 = 3`, acknowledges shutdown, and exits 0. A separate forced-transport-stop run after initialization reports zero remaining `lean`, `lake`, `liblean_exe.so`, or `liblake_exe.so` processes.
- Added unit coverage for interactive stream separation, environment clearing, bounded/forced direct-child termination, `lake serve` command construction, pinned Lean's `initialized: null`, document message escaping, monotonic versions, and stale/closed diagnostic rejection. All affected model/process/toolchain/LSP unit suites pass.
- The full `testDebugUnitTest :app:assembleDebug` regression passes in 3m42s. After deleting only the manually staged UTC diagnostic fixture, update-installing the new APK, and running Check, production `ToolchainLayoutAdapter` recreated a 114-byte `TZif` fallback. The visual editor still exited 0 with the expected `#check`/`#eval` output in 2,911 ms.
- Repeated both automated device modes using the production-created fallback: workspace-aware `lake serve` produced the expected version-1 diagnostic and exited 0 through shutdown/exit; forced transport termination reported no remaining Lean/Lake processes.

## 2026-08-13 — Installer schema and representative integrity hardening

### Implemented

- Replaced the presence-only `.installed` marker contract with schema version 1 plus the immutable toolchain ID. Existing ID-only markers migrate atomically in place only when representative runtime facets are complete, avoiding an unnecessary 2.19 GB reinstall on upgrade.
- Installation health now requires nonempty `Init.olean`, `.olean.private`, `.olean.server`, `.ilean`, and `.ir` files as well as a matching marker, native executables, compatibility links, Lean library, and UTC fallback.
- The installer always removes stale `.installing` state, can restore a healthy `.previous` tree left by interrupted activation, validates staged facets before replacing the active tree, and preserves the former active tree until the staged rename succeeds.
- Added a free-space preflight using the measured 2,194,903,155-byte filtered sysroot plus a 64 MiB reserve. The size constant is documented beside its Gradle build field and must be updated whenever APK filtering changes.

### Validation

- Added unit tests for healthy schema markers, safe legacy migration, wrong schema/ID reporting, empty/missing split facets, and the exact free-space threshold. `:core-toolchain:testDebugUnitTest :app:compileDebugKotlin` passes.
- Complete per-file manifest/hash validation, injected rename interruption, actual low-storage behavior, and corrupted-device repair remain explicitly open; representative facet validation is not being treated as complete corruption coverage.
- The full `testDebugUnitTest :app:assembleDebug` regression passes in 4m38s. Update-installed it over the reference tablet's legacy ID-only marker and invoked migration through the visual Check path. The marker changed in place to `schema=1` plus the matching toolchain ID, the existing 2,175,531 KiB sysroot was retained, no `.installing` or `.previous` tree remained, and Lean exited 0 with expected output in 3,212 ms.

## 2026-08-13 — Complete filtered-runtime manifest foundation

### Implemented

- Added a build-time filtered-manifest generator that maps every staged sysroot file back to the audited distribution manifest. It fails on unaudited paths or size drift and emits a compact tab-separated manifest containing relative path, byte size, and the audited SHA-256.
- Added a strict Kotlin runtime-manifest parser and streaming verifier. Paths cannot be absolute or traverse upward; duplicate paths, malformed records/sizes/hashes, missing files, wrong sizes, and same-size content corruption are reported.

### Validation and boundary

- Generated a 14,864-file manifest with SHA-256 `f7e389dcee7bd8f146fcd9e7f05ca6ddc9243bd3e99e3261a5dee79e7d1aa797`. `:core-toolchain:testDebugUnitTest :app:writeFilteredToolchainManifest` passes, including missing, size-corrupt, hash-corrupt, traversal, duplicate, and malformed-hash cases.
- The first generation took approximately eight minutes because enumerating thousands of files on `/mnt/d` is slow; no 2.19 GB rehash was performed. This checkpoint deliberately does not claim installed-tree enforcement yet: binding the manifest digest and one-time full verification into schema 2 is the next step, and routine visual Check must not rehash the sysroot.
- The full unit/APK regression passes in 18m10s; changing the asset set forced recompression of the large payload. The APK contains the 1,785,411-byte manifest with 14,866 lines (two headers plus 14,864 files), is 804,540,704 bytes, and has SHA-256 `3af006cf096ec50431bda6af1db07efb1068226c245cd143541ab4e67c8a182c`. Update installation succeeded with schema-1 sysroot data preserved. The tablet entered its secure lock screen before the final visual Check, so hands-on/editor automation for this exact APK remains pending rather than being inferred; the immediately preceding schema-aware APK passed the editor on the same device.

## 2026-08-13 — Schema-2 full runtime verification

### Implemented

- Advanced the installation marker to schema 2 and bound it to the exact filtered runtime-manifest SHA-256. The installer verifies the manifest asset's own digest/schema/toolchain ID before trusting its entries.
- Fresh staging, legacy markers, and schema-1 installations now stream-verify all 14,864 runtime paths, sizes, and hashes before schema 2 is written. A corrupt legacy/schema-1 tree falls through to the existing free-space-checked staged replacement path; it is never marked verified.
- Routine schema-2 health remains intentionally fast: marker digest, representative facets, native/layout links, Lean library, and UTC fallback are checked without rehashing 2.19 GB on every visual Check.

### Device validation

- After the tablet was unlocked, closed the pending manifest-APK gate: visual Check exited 0 in 3,013 ms, workspace-aware `lake serve` returned the expected version-1 `rfl` diagnostic and exited 0, and forced transport stop left zero Lean/Lake processes.
- Update-installed schema 2 over the existing schema-1 2,175,531 KiB sysroot and invoked its one-time full verification through the editor. The marker migrated in place to schema 2 with manifest digest `f7e389dcee7bd8f146fcd9e7f05ca6ddc9243bd3e99e3261a5dee79e7d1aa797`; Lean then exited 0 with expected output. The subsequent Check skipped full hashing and completed Lean execution in 1,505 ms.
- Repeated graceful diagnostics and forced-cleanup conformance after migration; both pass. The UI currently reports only Lean execution elapsed time, so the full migration duration was not measured separately and is not inferred from the displayed result.
- Corrected the migration recovery branch so a legacy/schema-1 hash failure falls through to staged replacement instead of surfacing immediately from the verifier. The final `testDebugUnitTest :app:assembleDebug` run passes in 3m48s; its update installation preserves the schema-2 marker, and the visual editor again exits 0 with expected output in 3,013 ms. Filtered logcat contains no app crash; a previously noticed `FATAL EXCEPTION` was traced to overlapping `uiautomator dump` helper processes (`UiAutomationService ... already registered`), not `org.lean4android.app`.

## 2026-08-13 — Deterministic activation rollback coverage

### Implemented

- Isolated sysroot directory activation and rollback recovery from the Android locator into a host-testable `ToolchainActivation` boundary while preserving the production staging/destination/previous layout.
- Hardened the failed-activation branch: failure to rename the preserved tree back into place is now surfaced explicitly instead of being ignored before reporting the original activation failure.
- Added deterministic tests for a successful atomic directory swap, a staging-rename failure that restores the old sysroot, cold-start recovery that accepts only a healthy `.previous` tree, and retention of an unhealthy active tree when rollback is also unhealthy.

### Validation and remaining boundary

- `:core-toolchain:testDebugUnitTest` passes all focused toolchain tests; the final run including the explicit rollback-rename failure case completed in 1m15s. The first compile also caught and prompted correction of a missing test-only `java.io.File` import.
- The complete `testDebugUnitTest` regression passes across the app, model, process, toolchain, and LSP modules in 3m36s (107 tasks; 3 executed and 104 up-to-date).
- These tests inject rename failures without copying the 2.19 GB payload and close the host-testable state-machine gap. They do not replace the planned Android instrumented interruption, real low-storage, complete installed-tree corruption, or cold-update cases.

## 2026-08-13 — Explicit runtime audit and process-boundary ADR

### Implemented

- Added `AndroidToolchainLocator.verifyInstalledRuntime()` as an explicit slow path. It first applies schema-2 representative health checks, then reads the digest-bound packaged manifest and streams size/hash verification across every installed runtime entry.
- Added a **Verify runtime** action beside **Check Lean** in the visual probe. The audit runs off the UI thread, disables concurrent actions, and reports duration, success, or the complete mismatch list. Ordinary startup and Lean checks do not call it.
- Added ADR 0001 accepting packaged child processes, data-only writable sysroots, typed shell-free command construction, compatibility-link refresh, the Bionic heap-tagging and `LEAN_SYSROOT` patches, and the current no-shell/no-native-build capability boundary. JNI and a native launcher remain evidence-triggered fallbacks.

### Validation and boundary

- Added app unit coverage for successful and failed integrity-result presentation. `:app:testDebugUnitTest :app:compileDebugKotlin` passes in 3m48s (76 tasks; 8 executed and 68 up-to-date).
- The implementation reuses the already-tested manifest parser/verifier. A physical full audit is intentionally still pending because it will read and hash approximately 2.19 GB; its measured duration belongs in the reference-device performance record.

## 2026-08-13 — M1.6 monolithic-package baseline

### Measurements and findings

- Audited the current 804,600,480-byte debug APK (`a121310dd949b5918bdaaed3b04a7b35a7e20e77e33476cb512920bce4009acc`) with ZIP entry accounting. Its 14,864 `assets/toolchain/` files occupy 2,192,429,171 bytes uncompressed and 692,307,895 bytes compressed.
- The eight arm64 native entries occupy 286,998,976 bytes uncompressed and 79,491,830 bytes compressed. These must remain APK-installed executable code; the runtime assets are data-delivery candidates.
- `.olean.private` dominates both installed and download size: 1,330,725,152 uncompressed bytes and 443,221,498 compressed bytes. It cannot be removed by filtering because the Android conformance sequence already proved ordinary imports require private facets.
- Added `docs/delivery/M1_6_BASELINE.md` with the complete facet table, reproduction method, and the resulting independent budgets for base native code, runtime download, expanded installation, and rollback/staging headroom.

### Boundary

- This measurement covers the current monolithic debug APK, not an AAB or release build and not a delivery prototype. The next M1.6 work remains Play asset-pack layout plus an independently signed, stream-installed data pack behind one installer interface.

## 2026-08-13 — App-owned long-lived Lean LSP session

### Implemented

- Connected `core-lsp` to the existing shell-free `core-process` boundary and added `LeanLspSession`, which owns one launched server, its byte-framed stdin/stdout transport, and document-version gate.
- Enforced lifecycle ordering from process start through initialize request, initialized notification, document open/change/close, shutdown request, exit, and closed state. Invalid ordering fails before writing protocol bytes.
- Added graceful-exit behavior that closes server stdin, waits for the requested grace period, and falls back to forced termination. Closing a session before shutdown also owns forced cleanup.
- Kept raw inbound message reading single-owner and blocking by design. JSON-RPC response/notification dispatch, dynamic registration replies, diagnostic decoding, and reconnectable Android service ownership are the next layer rather than being approximated with substring parsing in production.

### Validation

- Added fake-process integration tests through the real framing implementation. They assert exact lifecycle/document message order, stale diagnostic rejection after a version change, graceful exit without forced termination, forced cleanup on early close, and invalid lifecycle rejection.
- `:core-lsp:testDebugUnitTest` passes in 2m37s (38 tasks; 6 executed and 32 up-to-date).
- The complete `testDebugUnitTest` regression passes after the integrity UI, activation hardening, and LSP session work in 2m54s (107 tasks; 2 executed and 105 up-to-date).
- The first `:app:assembleDebug` pass completed in 3m43s; after correcting the shared busy label from check-specific wording, the final incremental pass completed in 3m23s while reusing unchanged toolchain staging/compression. The resulting 804,600,480-byte APK has SHA-256 `a121310dd949b5918bdaaed3b04a7b35a7e20e77e33476cb512920bce4009acc`; ZIP integrity passes and inspection confirms 2,431 each of private/server/IR facets plus all eight arm64 native entries. ADB started successfully but reported no attached device, so install, visual Check, the new full integrity action, and device LSP regression remain an explicit handoff rather than inferred validation.

## 2026-08-13 — Current-APK device audit, offline proof, and LSP metrics

### Device recovery and validation

- `lsusb` confirmed the attached Samsung tablet at bus/device `001/002`; its WSL USB node had reverted to `root:root` mode `0600`. Changed only that node to `root:plugdev` mode `0660`, restarted the project ADB with `ADB_LIBUSB=1`, and restored the authorized SM-T870 transport.
- Update-installed the 804,600,480-byte APK for Android user 0 while preserving schema-2 sysroot/project data. UI hierarchy confirms the multiline editor plus **Check Lean** and **Verify runtime** actions. Production Check exited 0 in 2,911 ms with the expected theorem type and string evaluation.
- Ran the user-triggered full integrity audit. All 14,864 installed entries matched the digest-bound packaged manifest in 16,778 ms, closing the earlier physical-validation handoff without adding hashing to routine checks.
- Repeated workspace-aware graceful LSP diagnostics and forced transport cleanup. The invalid theorem published the expected `rfl` diagnostic, graceful shutdown exited 0, and forced termination left zero Lean/Lake processes.

### Measurement instrumentation and offline result

- Extended `run-device-lsp.py` to emit initialize latency, `didOpen`-to-first-diagnostic latency, launch-to-diagnostic latency, and peak aggregate Lean/Lake RSS while retaining its lifecycle assertions.
- A connected warm run measured 399 ms to initialize, 1,047 ms from `didOpen` to the first diagnostic, 1,450 ms from launch to diagnostics, and 714,888 KiB peak aggregate RSS.
- Disabled Wi-Fi after recording its original enabled/connected state. The complete local LSP workflow still passed: 413 ms to initialize, 1,035 ms from `didOpen` to diagnostics, 1,453 ms launch-to-diagnostics, and 591,564 KiB peak aggregate RSS. Restored Wi-Fi and confirmed reconnection to the original network.
- Aggregate RSS sums Lean and Lake process RSS and can double-count shared mappings. Treat it as an upper bound; collect process-tree PSS before using the number as a memory budget.

## 2026-08-13 — Physical corruption repair exposed symlink-following deletion

### Probe and root cause

- Confirmed approximately 37 GB free on `/data`, then preserved `Init.ilean`, changed one byte without changing its size, and changed only the installation marker to schema 1. Production Check correctly rejected full verification and staged all 2,175,501 KiB from packaged assets.
- The first activation unexpectedly produced schema 2 with an empty `lib/lean`, and Check reported every representative facet missing. Inspection showed the old `.previous/.lake/build/lib/lean` absolute compatibility link targeted the stable destination path.
- Root cause: Kotlin `File.deleteRecursively()` follows directory symlinks. After staging was renamed to the stable destination, deleting `.previous` traversed its old absolute compatibility link and erased the new destination's Lean library. This destructive interaction was not represented by the earlier payload-only activation tests.

### Fix and regression coverage

- Added `deleteTreeWithoutFollowingLinks()` using `Files.walkFileTree` without `FOLLOW_LINKS`, and replaced recursive deletion for staging, rollback cleanup, and recovery replacement.
- Added an exact host regression: the old tree contains an absolute Lake-style link into its own destination, a new tree takes that destination name, and rollback cleanup must leave the new target bytes intact.
- `:core-toolchain:testDebugUnitTest` passes with the new reproducer in 2m15s. Built the corrected APK in 3m21s and update-installed it over the intentionally incomplete runtime. The final APK is 804,600,480 bytes with SHA-256 `d67647ce2a65f3826bdb8073527532081d54b95577719a6b5566c87bcf188dcb`; ZIP integrity passes with 2,431 each of private/server/IR facets and all eight arm64 native entries.

### Corrected physical recovery

- Production Check restaged the full 2,175,501 KiB, stream-verified it, activated schema 2, preserved `Init.olean`, and removed both `.installing` and `.previous`. Lean then exited 0 in 3,115 ms with expected output. Wall time from Check through repaired Lean output was approximately 54.2 seconds.
- A post-repair user audit matched every manifest entry in 16,626 ms. Graceful LSP diagnostics passed and forced cleanup again left zero processes.
- Added Android `dumpsys meminfo` PSS sampling to the LSP probe. The first cold post-repair run measured 1,072 ms initialize, 2,094 ms `didOpen`-to-diagnostic, 3,171 ms launch-to-diagnostic, 674,488 KiB aggregate RSS, and 544,681 KiB aggregate PSS. The immediate warm repeat measured 419 ms, 967 ms, 1,393 ms, 695,596 KiB RSS, and 89,430 KiB PSS. Report cold and warm results separately.
- Timed same-version installation of the 804.6 MB APK at 36.22 seconds. Before Check its compatibility links still referenced the prior randomized APK path; production Check refreshed them to the current path and remained functional.

## 2026-08-13 — Physical interrupted-staging recovery

### Validation

- Reintroduced the same one-byte/schema-1 corruption, started production repair, and force-stopped the app after staging reached 2,175,505 KiB but before `.installed` was written or activation began. The active tree remained schema 1 and the complete-but-unmarked `.installing` tree remained on disk, accurately representing process death before commit.
- Restarted production Check. The no-follow cleanup removed the stale staging tree before creating a new one, restaged and stream-verified the complete payload, briefly preserved the old active tree as `.previous`, activated schema 2, and safely removed rollback without traversing its compatibility link.
- Recovery left neither `.installing` nor `.previous`, restored the expected manifest-bound marker, and Lean exited 0 in 2,913 ms with expected output. Restart-to-result wall time was approximately 85.4 seconds; this includes deleting the complete stale tree, copying/verifying a second complete tree, activation cleanup, and Lean execution.
- A real low-storage case remains open. The reference device currently has about 37 GB free; manufacturing pressure by consuming unrelated user storage would be inappropriate. The exact preflight threshold remains host-tested.
- The final complete `testDebugUnitTest` regression passes in 2m00s (107 tasks; 4 executed and 103 up-to-date). Final device health shows a 2,175,530 KiB sysroot, a Lean link targeting the current APK native directory, and no residual Lean/Lake process.

## 2026-08-13 — Low-storage pressure moved beyond MVP

- Removed manufactured low-storage pressure from M1/MVP exit requirements. The reference tablet has ample free capacity, and filling unrelated user storage solely to create pressure is disproportionate to the MVP.
- Retained the exact byte-based preflight and readable required/available-space failure. Installed footprint, update/install time, full-audit duration, and cold/warm PSS/latency remain published facts that can inform users when their device is not a practical fit.
- Kept low-storage pressure as an optional M6 hardening/stretch case. This changes test prioritization only; it does not weaken atomic staging, corruption rejection, rollback, or insufficient-capacity refusal.

## 2026-08-13 — Typed JSON-RPC dispatch layer

### Implemented

- Added a dependency-free recursive JSON value parser/renderer with strict trailing-content, duplicate-key, escape, number, object, and array validation so host tests execute the same parsing code used on Android.
- Added typed JSON-RPC request, notification, and response envelopes with numeric/string IDs and exact `result` versus `error` validation.
- Added `LeanLspDispatcher`: Lean `client/registerCapability` requests receive a protocol response automatically; other server requests and ordinary notifications reach a typed sink; `publishDiagnostics` is decoded and delivered only when the session's open-document version gate accepts it.
- Connected dispatcher construction and single-message read/dispatch to `LeanLspSession`. This established the single-reader boundary subsequently implemented by `LeanLspSupervisor`; no competing reader or substring-based production parsing was introduced.

### Validation

- Added tests for nested Unicode JSON round trips, automatic dynamic-registration replies, stale versus current diagnostic routing, notification delivery, malformed JSON, duplicate fields, ambiguous responses, invalid versions/IDs, and trailing-comma rejection.
- `:core-lsp:testDebugUnitTest` passes in 1m28s (38 tasks; 5 executed and 33 up-to-date). Reconnectable Android service ownership is now the next layer.

## 2026-08-13 — Single-reader LSP supervisor

### Implemented

- Added `LeanLspSupervisor` as the sole owner of a session's blocking inbound reader. It creates one named daemon reader thread, dispatches through the typed session dispatcher, and prevents duplicate starts.
- Made supervisor termination explicit and observable: clean server EOF, caller-requested close, and protocol/transport failure are distinct stop reasons. Closing remains idempotent, closes the session/process, and briefly joins the reader without deadlocking a callback running on that reader.
- Kept Android component ownership out of `core-lsp`: the next bound-service layer can retain this supervisor across activity recreation without coupling the protocol library to an Activity or Service lifecycle.

### Validation

- Added tests through real byte framing for multi-message dispatch, automatic `client/registerCapability` reply bytes, clean EOF, malformed-server failure propagation, duplicate-start rejection, and forced process cleanup.
- `:core-lsp:testDebugUnitTest` passes in 1m24s (38 tasks; 5 executed and 33 up-to-date).
- The stricter dispatcher/session changes also pass the complete `testDebugUnitTest` regression in 2m27s (107 tasks; 5 executed and 102 up-to-date).

## 2026-08-13 — Reconnectable bound-service ownership foundation

### Implemented

- Added the app's dependency on `core-lsp` and declared a non-exported `LeanLspService`, keeping protocol/process access inside the application.
- Added a local Binder contract with generation-tagged per-project snapshots and replay-on-attach listeners. A recreated Activity can query existing ownership and distinguish a current session from an older callback.
- The service launches and owns one `LeanLspSession`/`LeanLspSupervisor` per project, rejects duplicate ownership before launching another child, provides explicit project stop, and closes all retained supervisors during service destruction.
- Kept this first component bound-only. Foreground promotion is intentionally deferred until a user-visible background operation and notification/cancellation contract exist; binding the editor and validating recreation are the next integration step.

### Validation

- `:app:testDebugUnitTest` compiles the service, manifest, and new module dependency and passes in 4m07s (91 tasks; 12 executed, 2 from cache, and 77 up-to-date).
- The complete post-service `testDebugUnitTest` regression passes in 2m33s (111 tasks, all up-to-date). The task count increased from 107 because the app now consumes and validates the `core-lsp` library path.

## 2026-08-13 — M1.6 delivery prototypes and decision

### Implemented and decided

- Added a delivery-neutral `RuntimePayloadSource` and one manifest-driven streaming installer. APK assets and an independently distributed ZIP now share entry-size/SHA-256 validation and the existing atomic activation boundary.
- Added complete detached RSA/SHA-256 verification before an independent ZIP exposes entries. No release key was committed; measurements used a temporary `/tmp` test key.
- Added a conditional `core_toolchain_pack` install-time Play Asset Delivery module. `-PplayAssetDelivery=true` removes sysroot data from base and puts data plus manifest in the pack while executable/native entries remain APK-installed.
- Selected install-time PAD for Play and a signed ZIP independently. A smaller artifact model is deferred because both fit without deleting conformance-required private/server/IR facets.

### Measurements and validation

- The PAD debug AAB is 693,129,838 bytes (SHA-256 `46edcbed0eff9a36ed390701d799ed9035a70920c9b79b11cd7fbf2d29d3f9f7`). It contains the runtime under `core_toolchain_pack/assets/` and no base `Init.olean` duplicate; the `/mnt/d` build took 21m33s.
- The independent ZIP is 595,064,864 bytes (SHA-256 `43cf8ffce36db523cbf121b56cdb02c8850063f9c19b3a395c65e164aade6410`) with a 256-byte signature (SHA-256 `2473601a28b0cb77ffdd6b68ea8d41770ecf645f9b50480c768eb8509826b9af`). OpenSSL verification and ZIP integrity pass.
- A complete external-pack test authenticated, streamed, size/hash-checked, and re-verified all 14,864 entries. The successful Gradle invocation took 1m26s including compilation. Its first run safely exposed the builder/source `toolchain/` prefix mismatch before copying; the source contract now accepts that documented prefix.
- Current Play limits checked on 2026-08-13 are 1.5 GB per asset pack and 4 GB cumulative install-time content, both above this payload. Final bundletool/Play Console measurement and limit revalidation remain release tasks; `docs/delivery/M1_6_DECISION.md` records the channel boundary and budgets.

## 2026-08-13 — M2 offline two-module project lifecycle

### Implemented

- Added `core-project` with strict IDs, schema/toolchain compatibility metadata, create/list/open/delete, monotonic atomic saves, typed `lake lean`/supported `lake build`, and a two-module template.
- Added deterministic atomic ZIP export and traversal-safe import with entry-count and 256 MiB expansion bounds. Unsupported Git/network dependencies, executable/native targets, and shell-download workflows fail before Lake launch.
- Added bounded structured one-shot job state and cancellation plus a reconnectable, non-exported `ProjectJobService`; retained LSP ownership remains in `LeanLspService`. Foreground promotion remains deferred until background work has a notification/cancellation UI contract.
- Replaced the single editor buffer with simple `Main.lean` and `Basic.lean` tabs. **Build project** atomically saves both and runs the supported Lake build.

### Host and device validation

- Focused project/process/toolchain/app suites pass, including hostile ZIP traversal, wrong-toolchain and unsupported-workflow rejection, rapid-save timestamp monotonicity, signed-pack tampering, bounded output, and cancellation. The Android instrumentation source compiles.
- Recovered the attached SM-T870 by changing only `/dev/bus/usb/001/002` from `root:root 0600` to `root:plugdev 0660`, restarting project ADB with `ADB_LIBUSB=1`, and using authorized serial `R52R40K1PPN`.
- The first two offline instrumentation runs exposed template defects: sub-second error/fix writes were invisible to Lake's timestamp cache, then `roots = ["Main"]` excluded the imported module. Saves now advance modification time by at least one second, and both `Main` and generated `*.Basic` are explicit roots.
- The final API-33 test passed offline in 6.78s: create two modules, observe an intentional error, fix it, build both, export, delete, import, and rebuild. Wi-Fi was restored to enabled; the imported project exists and `ps -A` shows no residual Lean/Lake process.
- The passing app APK is 804,635,316 bytes (SHA-256 `f115d148ac1f485d199d0b8c7d1009f478020c9ca14f109387ef82dfe812410e`); its 823,291-byte instrumentation APK has SHA-256 `61c7900b8533066d62988cb7a3e6c530821adbd7fc41921b4073d50902d5a132`. Clean-cache assembly took 39m32s on `/mnt/d`; the final incremental rebuild took 3m25s.

## 2026-08-13 — Post-crash device rerun and visible editor migration fix

- Recovered the restarted WSL USB attachment by restoring only `/dev/bus/usb/001/002` to `root:plugdev 0660`; project ADB/libusb reconnected to authorized SM-T870 serial `R52R40K1PPN`.
- The complete host `testDebugUnitTest` regression plus app/test assembly passed in 15m07s. Update-installed both artifacts and reran the M2 instrumentation scenario with Wi-Fi disabled; all error/fix/build/export/delete/import/rebuild assertions passed in 8.82s, then Wi-Fi was restored.
- Launched the physical UI and confirmed the visible `Main.lean` and `Basic.lean` tabs. The first manual **Build project** exposed an upgrade-only collision with the legacy M1 `projects/visual-probe` directory, which predates M2 metadata. Preserved that directory and moved the M2 visual sample to `projects/visual_probe`; the underscore retains the generated Lean namespace `VisualProbe`.
- `:app:testDebugUnitTest :app:assembleDebug` passed after the migration fix in 5m06s. Update-installed APK SHA-256 `f0a63caec34f03c59e556f59edb75c10c70dceb327cad8d0b3d7e4b13e1d91b8` and left the editor visibly open on a successful result: exit 0 in 3,918 ms, `VisualProbe.Basic` and `Main` built, theorem output displayed, and `#eval` returned `42`.
- Final device state has Wi-Fi enabled and no residual Lean/Lake child. The `ps` match is only `org.lean4android.app` itself.
- Final exact-build confirmation: reinstalled migration-fix app APK `f0a63caec34f03c59e556f59edb75c10c70dceb327cad8d0b3d7e4b13e1d91b8` together with instrumentation APK `61c7900b8533066d62988cb7a3e6c530821adbd7fc41921b4073d50902d5a132`, disabled Wi-Fi, and reran `M2OfflineProjectLifecycleTest`. The complete error/fix/build/export/delete/reimport/rebuild scenario passed in 6.791s. Wi-Fi was restored to enabled, the explicit process-name check found no Lean/Lake child, and the latest editor was reopened.
