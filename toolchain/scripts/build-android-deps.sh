#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command cmake
require_command make
require_command perl
require_file "$NDK_ROOT/build/cmake/android.toolchain.cmake"
require_pinned_checkout "$LIBUV_SOURCE" "$LIBUV_COMMIT"
require_pinned_checkout "$OPENSSL_SOURCE" "$OPENSSL_COMMIT"
mkdir -p "$ANDROID_DEPS"
export ANDROID_NDK_ROOT="$NDK_ROOT"
export PATH="$NDK_TOOLCHAIN/bin:$PATH"

cmake -S "$LIBUV_SOURCE" -B "$WORK_DIR/build/libuv-android-arm64" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$MINIMUM_API" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$ANDROID_DEPS" \
  -DBUILD_TESTING=OFF \
  -DLIBUV_BUILD_TESTS=OFF \
  -DLIBUV_BUILD_BENCH=OFF \
  -DLIBUV_BUILD_SHARED=OFF
cmake --build "$WORK_DIR/build/libuv-android-arm64" --parallel "$JOBS"
cmake --install "$WORK_DIR/build/libuv-android-arm64"
ln -sfn libuv-static.pc "$ANDROID_DEPS/lib/pkgconfig/libuv.pc"

readonly OPENSSL_BUILD="$WORK_DIR/build/openssl-android-arm64"
mkdir -p "$OPENSSL_BUILD"
pushd "$OPENSSL_BUILD" >/dev/null
"$OPENSSL_SOURCE/Configure" android-arm64 \
  --prefix="$ANDROID_DEPS" \
  --openssldir="$ANDROID_DEPS/ssl" \
  no-shared no-tests no-apps no-docs no-legacy
make -j"$JOBS"
make install_sw
popd >/dev/null

echo "Android dependencies installed in $ANDROID_DEPS"
