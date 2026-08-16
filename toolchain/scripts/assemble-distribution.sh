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

copy_with_progress() {
  local label="$1"
  local source="$2"
  local destination="$3"
  shift 3
  local total_files total_bytes
  total_files="$(find "$source" -type f | wc -l)"
  total_bytes="$(du -sb "$source" | cut -f1)"

  echo "Starting $label -> $destination ($total_files files, $total_bytes bytes)"
  "$@" &
  local copy_pid=$!
  while kill -0 "$copy_pid" 2>/dev/null; do
    local elapsed
    for ((elapsed = 0; elapsed < PROGRESS_INTERVAL_SECONDS; elapsed++)); do
      kill -0 "$copy_pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$copy_pid" 2>/dev/null; then
      local file_count copied_bytes size percent
      file_count="$(find "$destination" -type f 2>/dev/null | wc -l)"
      copied_bytes="$(du -sb "$destination" 2>/dev/null | cut -f1)"
      size="$(du -sh "$destination" 2>/dev/null | cut -f1)"
      if ((total_bytes > 0)); then
        percent=$((copied_bytes * 100 / total_bytes))
        ((percent > 100)) && percent=100
      else
        percent=100
      fi
      echo "Progress $label: ${percent}% (${file_count}/${total_files} files, ${size:-0} copied)"
    fi
  done
  wait "$copy_pid"
  echo "Completed $label"
}

echo "Recreating isolated distribution at $DIST"
rm -rf "$DIST"
mkdir -p "$NATIVE" "$SYSROOT/lib"

echo "Installing Android native executables and shared libraries"
install -m 0755 "$ANDROID_BUILD/bin/lean" "$NATIVE/liblean_exe.so"
install -m 0755 "$ANDROID_BUILD/bin/lake" "$NATIVE/liblake_exe.so"
find "$ANDROID_BUILD/lib/lean" -maxdepth 1 -type f -name '*.so' -exec install -m 0644 -t "$NATIVE" {} +
copy_with_progress "Lean library" "$ANDROID_BUILD/lib/lean" "$SYSROOT/lib/lean" cp -a "$ANDROID_BUILD/lib/lean" "$SYSROOT/lib/"
copy_with_progress "headers" "$ANDROID_BUILD/include" "$SYSROOT/include" cp -a "$ANDROID_BUILD/include" "$SYSROOT/"
copy_with_progress "shared data" "$ANDROID_BUILD/share" "$SYSROOT/share" cp -a "$ANDROID_BUILD/share" "$SYSROOT/"
mkdir -p "$SYSROOT/src"
copy_with_progress "Lean sources" "$LEAN_SOURCE/src" "$SYSROOT/src/lean" cp -a "$LEAN_SOURCE/src" "$SYSROOT/src/lean"
install -m 0644 "$LEAN_SOURCE/LICENSE" "$SYSROOT/LICENSE"

audit_native_files() {
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
}
run_with_progress "audit packaged Android ELF files and dependencies" audit_native_files

LEAN_COMMIT_VALUE="$LEAN_COMMIT" TOOLCHAIN_ID_VALUE="$TOOLCHAIN_ID" \
  run_with_progress "hash distribution and write manifest" \
  python3 "$TOOLCHAIN_ROOT/scripts/write-manifest.py" "$DIST" "$DIST/manifest.json"
jq empty "$DIST/manifest.json"
echo "Audited distribution assembled at $DIST"
