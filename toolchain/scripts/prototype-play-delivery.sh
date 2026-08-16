#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

ROOT="$REPOSITORY_ROOT"
ASSETS="$ROOT/core_toolchain_pack/src/main/assets"
GENERATED="$ROOT/app/build/generated/toolchain/assets"

run_with_progress "stage filtered toolchain and write manifest" env \
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-user-home}" \
  "$ROOT/gradlew" --no-build-cache --no-daemon --console=plain :app:writeFilteredToolchainManifest

test -d "$GENERATED/toolchain"
test -f "$GENERATED/toolchain-manifest.tsv"
ln -s "$GENERATED/toolchain" "$ASSETS/toolchain"
ln -s "$GENERATED/toolchain-manifest.tsv" "$ASSETS/toolchain-manifest.tsv"
cleanup() {
  unlink "$ASSETS/toolchain" 2>/dev/null || true
  unlink "$ASSETS/toolchain-manifest.tsv" 2>/dev/null || true
}
trap cleanup EXIT

run_with_progress "build Play Asset Delivery bundle" env \
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-user-home}" \
  "$ROOT/gradlew" --no-build-cache --no-daemon --console=plain -PplayAssetDelivery=true :app:bundleDebug

AAB="$ROOT/app/build/outputs/bundle/debug/app-debug.aab"
test -f "$AAB"
ENTRY_LIST=$(mktemp)
trap 'rm -f "$ENTRY_LIST"; cleanup' EXIT
run_with_progress "inventory Play bundle entries" unzip -Z1 "$AAB" > "$ENTRY_LIST"
grep -q '^core_toolchain_pack/assets/toolchain/lib/lean/Init.olean$' "$ENTRY_LIST"
grep -q '^core_toolchain_pack/assets/toolchain-manifest.tsv$' "$ENTRY_LIST"
if grep -q '^base/assets/toolchain/lib/lean/Init.olean$' "$ENTRY_LIST"; then
  echo "runtime payload was duplicated into the base module" >&2
  exit 1
fi
run_with_progress "hash Play bundle" sha256sum "$AAB"
stat --format='%s bytes' "$AAB"
