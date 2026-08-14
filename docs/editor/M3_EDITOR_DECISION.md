# M3 editor decision: native Jetpack Compose

Status: accepted for the initial beta on 2026-08-14.

## Decision

Use a native Jetpack Compose text editor for the pinned-toolchain beta. Do not embed CodeMirror 6 or expose a WebView bridge in the M3/M4 editor.

This is a scoped decision, not a claim that a plain Compose text field is a general-purpose IDE component. Reopen it if large-file measurements, IME/device coverage, or the M4 decorations/completion UI show that the native surface cannot meet the supported boundary.

## Bake-off evidence

The native prototype is the production M2/M3 editor. It has been exercised on the Samsung SM-T870/API 33 with real Unicode Lean files, soft-keyboard input, tab switching, offline Lake builds, update migration, portrait layout, and full process recreation. Its Kotlin-owned state now provides bounded undo/redo, search, syntax decoration, atomic dirty-buffer recovery, contained file operations, hardware-keyboard commands, and explicit accessibility semantics without introducing another process or trust boundary.

The CodeMirror candidate was evaluated as a bundled, navigation-disabled WebView whose only acceptable bridge would carry typed document edits and commands while Kotlin retained filesystem, toolchain, process, and LSP ownership. It offers stronger mature incremental rendering and decoration APIs, but it also adds a JavaScript dependency/build pipeline, WebView version variability, a second focus/IME/accessibility surface, bridge protocol/versioning work, and a new security boundary. Those costs are immediate; the main CodeMirror advantages become decisive only with substantially larger files or richer M4 decorations than the current two-module beta requires.

| Requirement | Native Compose prototype | Bundled CodeMirror 6 candidate |
|---|---|---|
| Kotlin/project integration | Direct typed state and repository calls | Requires a versioned message bridge |
| Offline/security boundary | No WebView, navigation, or JavaScript bridge | Safe only with bundled assets, disabled navigation/file access, and a narrow bridge |
| IME and selection | Android-native text input; device-proven for the reference tablet | Mature editor model, but WebView/IME behavior adds a second device-dependent layer |
| Hardware keyboard | Native key preview handles Find, Save/Build, Undo, and Redo | Strong built-in keymap, with commands forwarded through the bridge |
| Accessibility | Compose semantics expose files, dirty state, editor identity, controls, and live output | Browser accessibility is capable but must be reconciled with surrounding native UI |
| Search/highlighting | Sufficient bounded implementation for beta-sized Lean sources | More mature incremental search and decoration machinery |
| Diagnostics/completion growth | Adequate for M4 if measured file sizes remain responsive | Stronger long-term editor-extension ecosystem |
| Delivery/maintenance | Uses existing pinned Android/Compose stack | Adds JS packages, bundled web assets, WebView testing, and bridge compatibility |

## Guardrails and reopening criteria

- Project files remain the source of truth; recovery snapshots are bounded and app-private.
- Syntax decoration preserves source offsets and never changes the stored text.
- Production filesystem/process/LSP APIs remain typed Kotlin boundaries.
- Keep editor operations responsive on the supported phone/tablet layouts and test Unicode, IME, hardware keyboard, TalkBack semantics, and recreation.
- Reopen the decision if a representative supported file shows sustained input latency, decoration cost, or memory use that cannot be corrected without replacing the native text surface, or if M4 protocol features require editor primitives Compose cannot supply safely.
