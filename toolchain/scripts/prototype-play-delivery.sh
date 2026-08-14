#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ASSETS="$ROOT/core_toolchain_pack/src/main/assets"
GENERATED="$ROOT/app/build/generated/toolchain/assets"

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

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-user-home}" \
  "$ROOT/gradlew" --no-build-cache --no-daemon --console=plain -PplayAssetDelivery=true :app:bundleDebug

AAB="$ROOT/app/build/outputs/bundle/debug/app-debug.aab"
test -f "$AAB"
ENTRY_LIST=$(mktemp)
trap 'rm -f "$ENTRY_LIST"; cleanup' EXIT
unzip -Z1 "$AAB" > "$ENTRY_LIST"
grep -q '^core_toolchain_pack/assets/toolchain/lib/lean/Init.olean$' "$ENTRY_LIST"
grep -q '^core_toolchain_pack/assets/toolchain-manifest.tsv$' "$ENTRY_LIST"
if grep -q '^base/assets/toolchain/lib/lean/Init.olean$' "$ENTRY_LIST"; then
  echo "runtime payload was duplicated into the base module" >&2
  exit 1
fi
sha256sum "$AAB"
stat --format='%s bytes' "$AAB"
