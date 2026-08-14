# M1.6 core-toolchain delivery decision

Date: 2026-08-13

## Decision

Keep the eight arm64 ELF files in the base application's native-library directory and deliver the immutable, data-only sysroot separately. The preferred Google Play channel is one install-time Play Asset Delivery pack named `core_toolchain_pack`. The independent channel is a ZIP plus detached RSA/SHA-256 signature. Both feed the same manifest-driven streaming installer.

The current measured runtime is 692,307,895 compressed bytes and 2,192,429,171 expanded bytes. It fits the current 1.5 GB compressed limit for one Play asset pack and the 4 GB cumulative install-time limit, while the native base payload is about 79.5 MB compressed. The app remains far above the 200 MB large-download warning threshold.

## Prototypes

- `toolchain/scripts/prototype-play-delivery.sh` stages the audited filtered payload, builds an AAB with an install-time asset pack, rejects accidental duplication in the base module, and reports AAB size/hash.
- `toolchain/scripts/build-signed-runtime-pack.sh` creates the independent ZIP and detached signature without storing a private key in the repository.
- `RuntimePayloadSource` isolates delivery from activation. `ApkAssetRuntimePayloadSource` covers the monolithic/install-time-asset view and `SignedZipRuntimePayloadSource` verifies the complete independent pack before exposing entries.
- `RuntimePayloadInstaller` writes directly into the existing sibling staging directory and validates every entry's declared byte count and SHA-256 during the copy. Peak installer-controlled temporary space is therefore one expanded staging tree (about 2.19 GB) plus stream buffers; rollback may temporarily retain the old installed tree, for about 4.39 GB total sysroot data during an update.

## Release boundary

Install-time PAD was selected because the normal Android `AssetManager` path remains available and the editor cannot function without the sysroot. Fast-follow/on-demand would require a download-state UI and Play-specific service dependency but would not reduce expanded storage. Independent packs must be signed by an offline release key; the checked-in code intentionally contains no release public key or private key yet.

The Play limits must be rechecked at release time and final size must be measured with bundletool/Play Console, whose compressed-download calculation can differ from ZIP accounting. The prototype is not a published Play artifact.

## Prototype results

- Debug PAD AAB: 693,129,838 bytes, SHA-256 `46edcbed0eff9a36ed390701d799ed9035a70920c9b79b11cd7fbf2d29d3f9f7`. Structural audit found the manifest and runtime under `core_toolchain_pack/assets/`, with no base-module `Init.olean` duplicate. The `/mnt/d` build took 21m33s.
- Independent ZIP: 595,064,864 bytes, SHA-256 `43cf8ffce36db523cbf121b56cdb02c8850063f9c19b3a395c65e164aade6410`; detached 256-byte signature SHA-256 `2473601a28b0cb77ffdd6b68ea8d41770ecf645f9b50480c768eb8509826b9af`. OpenSSL signature verification and ZIP integrity pass.
- Complete independent-source exercise: production code authenticated, streamed, size/hash-checked, and independently re-verified all 14,864 entries in a temporary directory. The successful Gradle invocation, including compilation and the second verification, took 1m26s. A first attempt safely failed before copying and exposed the pack's `toolchain/` path-prefix contract, which is now explicit.
- Existing reference-device activation remains full-size production evidence for the shared streaming/activation path: about 2.19 GB installed, approximately 85.4 seconds for restart cleanup plus complete restage/verification/activation and Lean execution, and 16.778 seconds for a later explicit audit.
