# Mathlib data-pack producer

M5 pins Mathlib independently from the executable Lean toolchain. The Android app never runs these network-enabled producer steps and never performs Git or package resolution.

The current compatible source is Mathlib `v4.32.1` at commit `520045ab14e26149ee970e2e617ca04b09bde5d6`, whose checked-in `lean-toolchain` selects `leanprover/lean4:v4.32.1`. Exact dependency commits remain authoritative in Mathlib's `lake-manifest.json`; the fetcher checks out those commits without updating the lockfile.

From the repository root in a network-enabled producer environment:

```shell
python3 mathlib/scripts/fetch-sources.py
mathlib/scripts/fetch-host-toolchain.sh
mathlib/scripts/fetch-producer-cmake.sh
python3 mathlib/scripts/inventory.py \
  --producer-root toolchain/work/mathlib-producer \
  --output toolchain/output/mathlib-4.32.1-unfiltered-inventory.json
```

The first inventory is source-only. It records upstream symlinks without following them; those links are evidence to audit and are not eligible pack entries. Phase A is not complete until the pinned cache/build artifacts and dependency licenses have been materialized and the same inventory command has recorded the complete unfiltered tree. Nothing under `toolchain/work/` or `toolchain/output/` is eligible for Android delivery merely because it was inventoried.

The producer scripts support durable combined logging through `LEAN4ANDROID_LOG_FILE` and default to 30-second progress intervals, configurable with `LEAN4ANDROID_PROGRESS_INTERVAL_SECONDS`. Inventory hashing reports percentage by processed bytes and files; Git, curl, extraction, and verification steps preserve their native output and receive named stage heartbeats. For example:

```shell
LEAN4ANDROID_LOG_FILE="$PWD/toolchain/output/mathlib-inventory.log" \
  python3 mathlib/scripts/inventory.py \
    --producer-root toolchain/work/mathlib-producer \
    --output toolchain/output/mathlib-4.32.1-unfiltered-inventory.json
```

Official cache artifacts are provenance/input evidence only: they are not compatible with Android2's target Core/Std artifact hashes. Fetch a separate clean pinned tree and build it against the audited Android2 Core/Std tree with the release stage1 producer:

```shell
LEAN4ANDROID_LOG_FILE="$PWD/toolchain/output/mathlib-android2-fetch.log" \
  python3 mathlib/scripts/fetch-sources.py \
    --work-dir toolchain/work/mathlib-android2-producer

LEAN4ANDROID_JOBS=4 \
LEAN4ANDROID_MATHLIB_TARGET=Mathlib.Data.Nat.Prime.Basic \
LEAN4ANDROID_LOG_FILE="$PWD/toolchain/output/mathlib-android2-basic-build.log" \
  mathlib/scripts/build-android2-artifacts.sh
```

The targeted build is a compatibility gate. Inspect its header/hash and prove it imports under Android2 on the physical tablet before starting the complete build:

```shell
LEAN4ANDROID_JOBS=4 \
LEAN4ANDROID_LOG_FILE="$PWD/toolchain/output/mathlib-android2-full-build.log" \
  mathlib/scripts/build-android2-artifacts.sh
tail -f toolchain/output/mathlib-android2-full-build.log
```

The wrapper rejects non-pinned tracked sources, uses only the already materialized dependency closure, passes `--no-cache` without updating dependencies, and points the stage1 producer at Android2's `lib/lean`. It preserves Lake's native job output and emits 30-second module-count heartbeats; a full build also reports an approximate percentage against the pinned 8,654-job baseline.

The same command is the crash-recovery command. A per-producer lock rejects concurrent writers, while `build-state.txt` records running/completed/failed state. Before every invocation, the recovery audit scans only generated `.lake/build` trees without following links, removes orphaned `.tmp`/`.part` files, verifies finalized olean magic/version/commit, detects empty or all-zero generated facets and malformed hash sidecars, and invalidates only the affected module family. Lake then runs with `--rehash`, reuses healthy completed modules, regenerates invalidated/in-flight work, and updates anything whose dependency state changed. Do not delete the producer tree after a crash; rerun the same logged command and inspect any recovery messages.
