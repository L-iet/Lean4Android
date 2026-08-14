# M1.6 core-toolchain delivery baseline

Date: 2026-08-13

This is the first packaging baseline for the delivery spike. It measures the existing monolithic debug APK; it does not claim that this APK is a viable release channel.

## Artifact measured

- Path: `app/build/outputs/apk/debug/app-debug.apk`
- SHA-256: `d67647ce2a65f3826bdb8073527532081d54b95577719a6b5566c87bcf188dcb`
- APK bytes: 804,600,480
- ZIP payload: 2,510,298,408 bytes uncompressed; 801,549,599 bytes compressed
- Entries: 14,998

The filtered staged toolchain has an apparent size of 2,194,903,155 bytes. APK entries under `assets/toolchain/` account for 14,864 files, 2,192,429,171 uncompressed bytes, and 692,307,895 compressed bytes. The small difference from the staged-directory total includes filesystem/directory accounting and generated manifest placement.

The eight packaged arm64 native entries account for 286,998,976 uncompressed bytes and 79,491,830 compressed bytes. Executable/native code must remain in the base install; data assets are the candidate for alternative delivery.

## Runtime-data composition

| Category | Files | Uncompressed bytes | APK-compressed bytes |
|---|---:|---:|---:|
| `.olean.private` | 2,431 | 1,330,725,152 | 443,221,498 |
| `.ir` | 2,431 | 365,496,712 | 100,044,617 |
| `.olean` | 2,433 | 349,966,376 | 116,337,661 |
| `.ilean` | 2,433 | 84,985,906 | 15,326,955 |
| `.olean.server` | 2,431 | 31,189,736 | 8,919,937 |
| retained sources | 2,698 | 29,918,627 | 8,423,717 |
| other runtime data | 7 | 146,662 | 33,510 |

`.olean.private` is about 60.7% of uncompressed runtime assets and 64.0% of their compressed APK bytes. It is therefore the largest delivery target, but prior conformance proved that omitting private facets breaks normal imports; it cannot simply be filtered out.

## Initial implications

- Moving all runtime data out of the monolithic APK would remove roughly 692 MB of ZIP-compressed assets, but the base still carries roughly 79.5 MB compressed native code plus app/DEX/resources/ZIP overhead.
- ZIP compression reduces the 2.19 GB runtime data to about 692 MB, while installation expands it again. Delivery feasibility must budget download size, approximately 2.19 GB final data, and staging/rollback headroom independently.
- The next prototype should compare a Play asset-pack layout and a stream-installed independent data pack using exactly the same manifest entries. It must not move executable ELF files into either data channel.
- A smaller artifact experiment must preserve direct Lean, interpretation/IR, Lake, LSP diagnostics, and navigation before its size result is meaningful.

## Reproduction

The values above come from `stat`, `sha256sum`, `du -sb`, and `unzip -lv`, grouping `assets/toolchain/` by retained extension. Re-measure after any toolchain filter, Lean revision, build-mode, or compression change.
