#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GENERATED="$ROOT/app/build/generated/toolchain/assets"
OUTPUT=${1:-"$ROOT/toolchain/output/lean-4.32.1-android1-runtime.zip"}
PRIVATE_KEY=${2:-}

if [[ -z "$PRIVATE_KEY" || ! -f "$PRIVATE_KEY" ]]; then
  echo "usage: $0 OUTPUT.zip RSA_PRIVATE_KEY.pem" >&2
  exit 2
fi

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-user-home}" \
  "$ROOT/gradlew" --no-build-cache --no-daemon --console=plain :app:writeFilteredToolchainManifest
mkdir -p "$(dirname "$OUTPUT")"
(
  cd "$GENERATED"
  zip -q -r -9 "$OUTPUT" toolchain toolchain-manifest.tsv
)
openssl dgst -sha256 -sign "$PRIVATE_KEY" -out "$OUTPUT.sig" "$OUTPUT"
sha256sum "$OUTPUT" "$OUTPUT.sig"
stat --format='%n %s bytes' "$OUTPUT" "$OUTPUT.sig"
