#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

ROOT="$REPOSITORY_ROOT"
GENERATED="$ROOT/app/build/generated/toolchain/assets"
OUTPUT=${1:-"$ROOT/toolchain/output/lean-4.32.1-android1-runtime.zip"}
PRIVATE_KEY=${2:-}

if [[ -z "$PRIVATE_KEY" || ! -f "$PRIVATE_KEY" ]]; then
  echo "usage: $0 OUTPUT.zip RSA_PRIVATE_KEY.pem" >&2
  exit 2
fi

run_with_progress "stage filtered toolchain and write manifest" env \
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-user-home}" \
  "$ROOT/gradlew" --no-build-cache --no-daemon --console=plain :app:writeFilteredToolchainManifest
mkdir -p "$(dirname "$OUTPUT")"
(
  cd "$GENERATED"
  run_with_progress "compress signed runtime pack" zip -q -r -9 "$OUTPUT" toolchain toolchain-manifest.tsv
)
run_with_progress "sign runtime pack" openssl dgst -sha256 -sign "$PRIVATE_KEY" -out "$OUTPUT.sig" "$OUTPUT"
run_with_progress "hash runtime pack and signature" sha256sum "$OUTPUT" "$OUTPUT.sig"
stat --format='%n %s bytes' "$OUTPUT" "$OUTPUT.sig"
