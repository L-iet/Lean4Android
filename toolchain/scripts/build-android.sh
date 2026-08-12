#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command cmake
require_file "$HOST_BUILD/stage1/bin/lean"
require_file "$ANDROID_DEPS/lib/pkgconfig/libuv.pc"
require_file "$ANDROID_DEPS/lib/libssl.a"
require_file "$NDK_ROOT/build/cmake/android.toolchain.cmake"

export PKG_CONFIG_LIBDIR="$ANDROID_DEPS/lib/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR=/

cmake -S "$LEAN_SOURCE/src" -B "$ANDROID_BUILD" -G "Unix Makefiles" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$MINIMUM_API" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$ANDROID_DEPS" \
  -DOPENSSL_ROOT_DIR="$ANDROID_DEPS" \
  -DOPENSSL_USE_STATIC_LIBS=TRUE \
  -DSTAGE=1 \
  -DPREV_STAGE="$HOST_BUILD/stage1" \
  -DPREV_STAGE_CMAKE_EXECUTABLE_SUFFIX= \
  -DLEAN_PLATFORM_TARGET=aarch64-linux-android \
  -DLEAN_CXX_STDLIB=-lc++_static \
  -DUSE_GMP=OFF \
  -DUSE_MIMALLOC=OFF \
  -DUSE_LAKE=OFF \
  -DLLVM=OFF \
  -DINSTALL_CADICAL=OFF \
  -DINSTALL_LEANTAR=OFF
cmake --build "$ANDROID_BUILD" --parallel "$JOBS"
