#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command cmake
require_command make
require_patched_lean_checkout

cmake -S "$LEAN_SOURCE" -B "$HOST_BUILD" -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE=Release \
  -DUSE_GMP=OFF \
  -DUSE_MIMALLOC=OFF \
  -DUSE_LAKE=OFF \
  -DCADICAL=/bin/false \
  -DLEANTAR=/bin/false \
  -DINSTALL_CADICAL=OFF \
  -DINSTALL_LEANTAR=OFF
cmake --build "$HOST_BUILD" --target stage0 --parallel "$JOBS"
"$HOST_BUILD/stage0/bin/lean" --version
