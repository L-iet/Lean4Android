#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command cmake
require_command make
require_clean_checkout "$LEAN_SOURCE" "$LEAN_COMMIT"

cmake -S "$LEAN_SOURCE" -B "$HOST_BUILD" -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE=Release \
  -DUSE_GMP=OFF \
  -DUSE_MIMALLOC=OFF \
  -DUSE_LAKE=OFF \
  -DCADICAL=/bin/false \
  -DLEANTAR=/bin/false \
  -DINSTALL_CADICAL=OFF \
  -DINSTALL_LEANTAR=OFF
cmake --build "$HOST_BUILD" --target stage1 --parallel "$JOBS"
"$HOST_BUILD/stage1/bin/lean" --version
