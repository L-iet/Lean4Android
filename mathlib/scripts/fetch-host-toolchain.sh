#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$repository_root/toolchain/scripts/common.sh"
versions_file="$repository_root/mathlib/versions.toml"
work_dir="${LEAN4ANDROID_MATHLIB_WORK_DIR:-$repository_root/toolchain/work/mathlib-producer}"

toml_string() {
  sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$versions_file"
}

archive_name="$(toml_string host_toolchain_archive)"
expected_sha256="$(toml_string host_toolchain_sha256)"
lean_version="$(toml_string lean_version)"
download_dir="$work_dir/downloads"
archive="$download_dir/$archive_name"
toolchain_dir="$work_dir/host-toolchain/lean-$lean_version-linux"
url="https://github.com/leanprover/lean4/releases/download/v$lean_version/$archive_name"

mkdir -p "$download_dir" "$work_dir/host-toolchain"
if [[ ! -f "$archive" ]]; then
  run_with_progress "download host Lean producer" curl -L --fail --show-error -o "$archive" "$url"
fi
run_with_progress "verify host Lean producer archive" bash -c 'echo "$1  $2" | sha256sum --check --status' _ "$expected_sha256" "$archive" || {
  echo "Host Lean archive hash mismatch: $archive" >&2
  exit 1
}
if [[ ! -d "$toolchain_dir" ]]; then
  run_with_progress "extract host Lean producer" tar --zstd -xf "$archive" -C "$work_dir/host-toolchain"
fi
actual_version="$($toolchain_dir/bin/lean --version)"
[[ "$actual_version" == "Lean (version $lean_version,"* ]] || {
  echo "Unexpected host Lean version: $actual_version" >&2
  exit 1
}
echo "$actual_version"
echo "Verified host producer toolchain: $toolchain_dir"
