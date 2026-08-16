#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command "$CMAKE_COMMAND"
require_command make
require_patched_lean_checkout

run_with_progress "configure host Lean build" "$CMAKE_COMMAND" -S "$LEAN_SOURCE" -B "$HOST_BUILD" -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE=Release \
  -DUSE_GMP=OFF \
  -DUSE_MIMALLOC=OFF \
  -DUSE_LAKE=OFF \
  -DCADICAL=/bin/false \
  -DLEANTAR=/bin/false \
  -DINSTALL_CADICAL=OFF \
  -DINSTALL_LEANTAR=OFF
run_with_progress "build host Lean $HOST_TARGET with $JOBS jobs" \
  "$CMAKE_COMMAND" --build "$HOST_BUILD" --target "$HOST_TARGET" --parallel "$JOBS"
"$HOST_BUILD/$HOST_TARGET/bin/lean" --version
