#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command jq
require_command python3
readonly READELF="$NDK_TOOLCHAIN/bin/llvm-readelf"
require_file "$READELF"
require_file "$ANDROID_BUILD/bin/lean"
require_file "$ANDROID_BUILD/bin/lake"

readonly DIST="$OUTPUT_DIR/$TOOLCHAIN_ID"
readonly NATIVE="$DIST/native/arm64-v8a"
readonly SYSROOT="$DIST/sysroot"
rm -rf "$DIST"
mkdir -p "$NATIVE" "$SYSROOT/lib"

install -m 0755 "$ANDROID_BUILD/bin/lean" "$NATIVE/liblean_exe.so"
install -m 0755 "$ANDROID_BUILD/bin/lake" "$NATIVE/liblake_exe.so"
find "$ANDROID_BUILD/lib/lean" -maxdepth 1 -type f -name '*.so' -exec install -m 0644 -t "$NATIVE" {} +
cp -a "$ANDROID_BUILD/lib/lean" "$SYSROOT/lib/"
cp -a "$ANDROID_BUILD/include" "$SYSROOT/"
cp -a "$ANDROID_BUILD/share" "$SYSROOT/"
mkdir -p "$SYSROOT/src"
cp -a "$LEAN_SOURCE/src" "$SYSROOT/src/lean"
install -m 0644 "$LEAN_SOURCE/LICENSE" "$SYSROOT/LICENSE"

while IFS= read -r -d '' elf; do
  "$READELF" -h "$elf" | grep -q 'Machine:.*AArch64' || {
    echo "Not an AArch64 ELF: $elf" >&2
    exit 1
  }
  while read -r needed; do
    case "$needed" in
      libc.so|libdl.so|liblog.so|libm.so|libz.so|libandroid.so) ;;
      lib*.so) [[ -f "$NATIVE/$needed" ]] || { echo "Unpackaged dependency $needed required by $elf" >&2; exit 1; } ;;
      *) echo "Unexpected dynamic dependency $needed required by $elf" >&2; exit 1 ;;
    esac
  done < <("$READELF" -d "$elf" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
done < <(find "$NATIVE" -type f -print0)

LEAN_COMMIT_VALUE="$LEAN_COMMIT" python3 "$TOOLCHAIN_ROOT/scripts/write-manifest.py" "$DIST" "$DIST/manifest.json"
jq empty "$DIST/manifest.json"
echo "Audited distribution assembled at $DIST"
