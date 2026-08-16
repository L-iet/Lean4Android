#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$repository_root/toolchain/scripts/common.sh"
versions_file="$repository_root/mathlib/versions.toml"
work_dir="${LEAN4ANDROID_MATHLIB_WORK_DIR:-$repository_root/toolchain/work/mathlib-producer}"

toml_string() {
  sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$versions_file"
}

version="$(toml_string producer_cmake_version)"
expected_sha256="$(toml_string producer_cmake_sha256)"
archive_name="cmake-$version-linux-x86_64.tar.gz"
download_dir="$work_dir/downloads"
archive="$download_dir/$archive_name"
cmake_dir="$work_dir/producer-tools/cmake-$version-linux-x86_64"
url="https://github.com/Kitware/CMake/releases/download/v$version/$archive_name"

mkdir -p "$download_dir" "$work_dir/producer-tools"
if [[ ! -f "$archive" ]]; then
  run_with_progress "download producer CMake" curl -L --fail --show-error -o "$archive" "$url"
fi
run_with_progress "verify producer CMake archive" bash -c 'echo "$1  $2" | sha256sum --check --status' _ "$expected_sha256" "$archive" || {
  echo "Producer CMake archive hash mismatch: $archive" >&2
  exit 1
}
if [[ ! -d "$cmake_dir" ]]; then
  run_with_progress "extract producer CMake" tar -xzf "$archive" -C "$work_dir/producer-tools"
fi
"$cmake_dir/bin/cmake" --version
