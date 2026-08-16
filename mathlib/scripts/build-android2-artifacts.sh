#!/usr/bin/env bash
set -euo pipefail

readonly MATHLIB_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../toolchain/scripts/common.sh
source "$MATHLIB_SCRIPT_DIR/../../toolchain/scripts/common.sh"

readonly PRODUCER_ROOT="${LEAN4ANDROID_MATHLIB_PRODUCER_ROOT:-$REPOSITORY_ROOT/toolchain/work/mathlib-android2-producer}"
readonly MATHLIB_ROOT="$PRODUCER_ROOT/mathlib"
readonly RELEASE_HOST="$REPOSITORY_ROOT/toolchain/work/build/host/stage1"
readonly ANDROID2_BUILD="$REPOSITORY_ROOT/toolchain/work/build/android-arm64-release"
readonly PRODUCER_SYSROOT="$REPOSITORY_ROOT/toolchain/work/mathlib-android2-host-sysroot"
readonly BUILD_TARGET="${LEAN4ANDROID_MATHLIB_TARGET:-Mathlib}"
readonly EXPECTED_FULL_JOBS=8654
readonly BUILD_STATE="$PRODUCER_ROOT/build-state.txt"
readonly BUILD_LOCK="$PRODUCER_ROOT/build.lock"

require_command git
require_command python3
require_command flock
require_file "$RELEASE_HOST/bin/lean"
require_file "$RELEASE_HOST/bin/lake"
require_file "$ANDROID2_BUILD/lib/lean/Init.olean"
require_file "$MATHLIB_ROOT/lake-manifest.json"

exec 9>"$BUILD_LOCK"
flock -n 9 || {
  echo "Another Android2 Mathlib build holds $BUILD_LOCK" >&2
  exit 1
}

previous_state="$(sed -n '1p' "$BUILD_STATE" 2>/dev/null || true)"
if [[ "$previous_state" == running* ]]; then
  echo "Detected interrupted prior build: $previous_state"
fi
run_with_progress "audit and repair interrupted Mathlib outputs" \
  python3 "$MATHLIB_SCRIPT_DIR/recover-android2-build.py" --mathlib-root "$MATHLIB_ROOT"

readonly MATHLIB_COMMIT="$(sed -n 's/^mathlib_commit = "\(.*\)"$/\1/p' "$REPOSITORY_ROOT/mathlib/versions.toml")"
[[ "$(git -C "$MATHLIB_ROOT" rev-parse HEAD)" == "$MATHLIB_COMMIT" ]] || {
  echo "Unexpected Mathlib commit in $MATHLIB_ROOT" >&2
  exit 1
}
[[ -z "$(git -C "$MATHLIB_ROOT" status --short --untracked-files=no)" ]] || {
  echo "Mathlib tracked source is dirty: $MATHLIB_ROOT" >&2
  exit 1
}

python3 - "$MATHLIB_ROOT" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
for package in json.loads((root / "lake-manifest.json").read_text())["packages"]:
    directory = root / ".lake/packages" / package["name"]
    actual = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "HEAD"], text=True).strip()
    if actual != package["rev"]:
        raise SystemExit(f"unexpected {package['name']} commit: {actual}, expected {package['rev']}")
    dirty = subprocess.check_output(
        ["git", "-C", str(directory), "status", "--short", "--untracked-files=no"], text=True
    ).strip()
    if dirty:
        raise SystemExit(f"tracked dependency source is dirty: {directory}")
PY

release_version="$($RELEASE_HOST/bin/lean --version)"
[[ "$release_version" == *"version 4.32.1"* && "$release_version" == *"f054605aea4b840552cca2e725580bffd1e1b704"* ]] || {
  echo "Unexpected release producer: $release_version" >&2
  exit 1
}

mkdir -p "$PRODUCER_SYSROOT/bin" "$PRODUCER_SYSROOT/lib"
for executable in lean lake leanc leanir leanchecker; do
  source_path="$RELEASE_HOST/bin/$executable"
  [[ -e "$source_path" ]] || continue
  destination="$PRODUCER_SYSROOT/bin/$executable"
  if [[ -e "$destination" || -L "$destination" ]]; then
    [[ -L "$destination" && "$(readlink -f "$destination")" == "$(readlink -f "$source_path")" ]] || {
      echo "Unexpected producer adapter entry: $destination" >&2
      exit 1
    }
  else
    ln -s "$source_path" "$destination"
  fi
done
android_library="$ANDROID2_BUILD/lib/lean"
adapter_library="$PRODUCER_SYSROOT/lib/lean"
if [[ -e "$adapter_library" || -L "$adapter_library" ]]; then
  [[ -L "$adapter_library" && "$(readlink -f "$adapter_library")" == "$(readlink -f "$android_library")" ]] || {
    echo "Unexpected producer adapter library: $adapter_library" >&2
    exit 1
  }
else
  ln -s "$android_library" "$adapter_library"
fi

export PATH="$RELEASE_HOST/bin:$PATH"
export LEAN_SYSROOT="$PRODUCER_SYSROOT"
export LEAN_PATH="$adapter_library"
export LAKE_OVERRIDE_LEAN=true
export LEAN_NUM_THREADS="$JOBS"

echo "Mathlib source: $MATHLIB_ROOT"
echo "Build target: $BUILD_TARGET"
echo "Release producer: $RELEASE_HOST/bin/lean"
echo "Android2 Core/Std: $ANDROID2_BUILD/lib/lean"
echo "Host-executable/Android2-library adapter: $PRODUCER_SYSROOT"
echo "Jobs: $JOBS"
echo "Network/cache policy: pinned local dependencies, --no-cache, no update"

printf 'running target=%s pid=%s started=%s\n' "$BUILD_TARGET" "$$" "$(date -Is)" >"$BUILD_STATE"
build_finished=0
record_exit() {
  local status=$?
  if ((build_finished == 0)); then
    printf 'interrupted target=%s prior_pid=%s recorded=%s exit=%s\n' "$BUILD_TARGET" "$$" "$(date -Is)" "$status" >"$BUILD_STATE"
  fi
}
trap record_exit EXIT HUP INT TERM

started_at=$SECONDS
"$RELEASE_HOST/bin/lake" --no-cache --rehash -d "$MATHLIB_ROOT" build "$BUILD_TARGET" &
build_pid=$!
while kill -0 "$build_pid" 2>/dev/null; do
  sleep "$PROGRESS_INTERVAL_SECONDS"
  completed="$(find "$MATHLIB_ROOT/.lake" -path '*/build/lib/lean/*.olean' -type f 2>/dev/null | wc -l)"
  if [[ "$BUILD_TARGET" == "Mathlib" ]]; then
    percent=$((completed * 100 / EXPECTED_FULL_JOBS))
    ((percent > 99)) && percent=99
    echo "[$(date -Is)] Progress Android2 Mathlib artifacts: approximately ${percent}% ($completed/$EXPECTED_FULL_JOBS oleans present, $((SECONDS - started_at))s elapsed)"
  else
    echo "[$(date -Is)] Progress Android2 Mathlib target $BUILD_TARGET: $completed oleans present ($((SECONDS - started_at))s elapsed)"
  fi
done

if wait "$build_pid"; then
  status=0
else
  status=$?
fi
if ((status != 0)); then
  build_finished=1
  printf 'failed target=%s recorded=%s exit=%s\n' "$BUILD_TARGET" "$(date -Is)" "$status" >"$BUILD_STATE"
  echo "Android2 Mathlib artifact build failed with exit $status after $((SECONDS - started_at))s" >&2
  exit "$status"
fi

completed="$(find "$MATHLIB_ROOT/.lake" -path '*/build/lib/lean/*.olean' -type f 2>/dev/null | wc -l)"
build_finished=1
printf 'complete target=%s recorded=%s oleans=%s\n' "$BUILD_TARGET" "$(date -Is)" "$completed" >"$BUILD_STATE"
echo "[$(date -Is)] Progress Android2 Mathlib artifacts: 100% for requested target ($completed oleans present)"
echo "Completed Android2 Mathlib target $BUILD_TARGET in $((SECONDS - started_at))s"
