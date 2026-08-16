#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly TOOLCHAIN_ROOT="$REPOSITORY_ROOT/toolchain"
readonly VERSIONS_FILE="$TOOLCHAIN_ROOT/versions.toml"
readonly WORK_DIR="${LEAN4ANDROID_WORK_DIR:-$TOOLCHAIN_ROOT/work}"
readonly OUTPUT_DIR="${LEAN4ANDROID_OUTPUT_DIR:-$TOOLCHAIN_ROOT/output}"
readonly JOBS="${LEAN4ANDROID_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
readonly CMAKE_COMMAND="${LEAN4ANDROID_CMAKE_COMMAND:-cmake}"
readonly HOST_TARGET="${LEAN4ANDROID_HOST_TARGET:-stage0}"
readonly HOST_PRODUCER_STAGE="${LEAN4ANDROID_HOST_PRODUCER_STAGE:-stage0}"
readonly ANDROID_BUILD_VARIANT="${LEAN4ANDROID_ANDROID_BUILD_VARIANT:-android-arm64}"
readonly PROGRESS_INTERVAL_SECONDS="${LEAN4ANDROID_PROGRESS_INTERVAL_SECONDS:-30}"
readonly LOG_FILE="${LEAN4ANDROID_LOG_FILE:-}"

[[ "$HOST_TARGET" =~ ^stage[0-9]+$ ]] || {
  echo "Invalid Lean host target: $HOST_TARGET" >&2
  exit 1
}
[[ "$HOST_PRODUCER_STAGE" =~ ^stage[0-9]+$ ]] || {
  echo "Invalid Lean host producer stage: $HOST_PRODUCER_STAGE" >&2
  exit 1
}
[[ "$ANDROID_BUILD_VARIANT" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "Invalid Android build variant: $ANDROID_BUILD_VARIANT" >&2
  exit 1
}
[[ "$PROGRESS_INTERVAL_SECONDS" =~ ^[1-9][0-9]*$ ]] || {
  echo "Invalid progress interval: $PROGRESS_INTERVAL_SECONDS" >&2
  exit 1
}

if [[ -n "$LOG_FILE" && "${LEAN4ANDROID_LOG_ACTIVE:-0}" != 1 ]]; then
  mkdir -p "$(dirname "$LOG_FILE")"
  exec > >(tee -a "$LOG_FILE") 2>&1
  export LEAN4ANDROID_LOG_ACTIVE=1
  echo "[$(date -Is)] Logging combined stdout/stderr to $LOG_FILE"
fi

run_with_progress() {
  local label="$1"
  shift
  local started_at=$SECONDS
  echo "[$(date -Is)] Starting: $label"
  (
    while true; do
      sleep "$PROGRESS_INTERVAL_SECONDS"
      echo "[$(date -Is)] Still running: $label ($((SECONDS - started_at))s elapsed)"
    done
  ) &
  local reporter_pid=$!
  local status
  if "$@"; then
    status=0
  else
    status=$?
  fi
  kill "$reporter_pid" 2>/dev/null || true
  wait "$reporter_pid" 2>/dev/null || true
  if ((status == 0)); then
    echo "[$(date -Is)] Completed: $label ($((SECONDS - started_at))s)"
  else
    echo "[$(date -Is)] Failed: $label (exit $status after $((SECONDS - started_at))s)" >&2
  fi
  return "$status"
}

toml_string() {
  local key="$1"
  sed -n "s/^${key} = \"\(.*\)\"$/\1/p" "$VERSIONS_FILE"
}

toml_integer() {
  local key="$1"
  sed -n "s/^${key} = \([0-9][0-9]*\)$/\1/p" "$VERSIONS_FILE"
}

readonly LEAN_VERSION="$(toml_string lean_version)"
readonly LEAN_TAG="$(toml_string lean_tag)"
readonly LEAN_COMMIT="$(toml_string lean_commit)"
readonly NDK_VERSION="$(toml_string ndk_version)"
readonly LIBUV_VERSION="$(toml_string libuv_version)"
readonly LIBUV_COMMIT="$(toml_string libuv_commit)"
readonly OPENSSL_VERSION="$(toml_string openssl_version)"
readonly OPENSSL_COMMIT="$(toml_string openssl_commit)"
readonly MINIMUM_API="$(toml_integer minimum_api)"
readonly TOOLCHAIN_ID="${LEAN4ANDROID_TOOLCHAIN_ID:-$(toml_string toolchain_id)}"
[[ "$TOOLCHAIN_ID" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "Invalid toolchain ID: $TOOLCHAIN_ID" >&2
  exit 1
}
readonly SDK_ROOT="${ANDROID_SDK_ROOT:-$REPOSITORY_ROOT/.android-sdk}"
readonly NDK_ROOT="${ANDROID_NDK_ROOT:-$SDK_ROOT/ndk/$NDK_VERSION}"
readonly NDK_HOST_TAG="linux-x86_64"
readonly NDK_TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG"
readonly LEAN_SOURCE="$WORK_DIR/src/lean4"
readonly LIBUV_SOURCE="$WORK_DIR/src/libuv"
readonly OPENSSL_SOURCE="$WORK_DIR/src/openssl"
readonly HOST_BUILD="$WORK_DIR/build/host"
readonly HOST_PRODUCER="$HOST_BUILD/$HOST_PRODUCER_STAGE"
readonly ANDROID_DEPS="$WORK_DIR/prefix/android-arm64"
readonly ANDROID_BUILD="$WORK_DIR/build/$ANDROID_BUILD_VARIANT"

require_command() {
  command -v "$1" >/dev/null || { echo "Required command is missing: $1" >&2; exit 1; }
}

require_file() {
  [[ -f "$1" ]] || { echo "Required file is missing: $1" >&2; exit 1; }
}

require_pinned_checkout() {
  local directory="$1" expected="$2"
  [[ "$(git -C "$directory" rev-parse HEAD)" == "$expected" ]] || {
    echo "Unexpected commit in $directory" >&2
    exit 1
  }
  [[ -z "$(git -C "$directory" status --short)" ]] || {
    echo "Source checkout is not clean: $directory" >&2
    exit 1
  }
}

require_patched_lean_checkout() {
  local expected_patch="$TOOLCHAIN_ROOT/patches/0001-cmake-treat-android-as-elf-platform.patch"
  [[ "$(git -C "$LEAN_SOURCE" rev-parse HEAD)" == "$LEAN_COMMIT" ]] || {
    echo "Unexpected commit in $LEAN_SOURCE" >&2
    exit 1
  }
  [[ -z "$(git -C "$LEAN_SOURCE" ls-files --others --exclude-standard)" ]] || {
    echo "Lean source checkout contains untracked files" >&2
    exit 1
  }
  cmp -s <(git -C "$LEAN_SOURCE" diff --no-ext-diff) "$expected_patch" || {
    echo "Lean source changes do not exactly match the Android patch" >&2
    exit 1
  }
}
