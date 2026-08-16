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
