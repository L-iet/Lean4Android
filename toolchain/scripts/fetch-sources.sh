#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

require_command git
mkdir -p "$WORK_DIR/src"

fetch_checkout() {
  local url="$1" revision="$2" destination="$3"
  if [[ ! -d "$destination/.git" ]]; then
    run_with_progress "clone $url" git clone --filter=blob:none --no-checkout "$url" "$destination"
  fi
  run_with_progress "fetch $revision into $destination" git -C "$destination" fetch --depth 1 origin "$revision"
  git -C "$destination" checkout --detach --force FETCH_HEAD
  git -C "$destination" reset --hard FETCH_HEAD
  git -C "$destination" clean -ffd
}

fetch_checkout https://github.com/leanprover/lean4.git "$LEAN_TAG" "$LEAN_SOURCE"
[[ "$(git -C "$LEAN_SOURCE" rev-parse HEAD)" == "$LEAN_COMMIT" ]] || {
  echo "Lean tag $LEAN_TAG did not resolve to pinned commit $LEAN_COMMIT" >&2
  exit 1
}
fetch_checkout https://github.com/libuv/libuv.git "v$LIBUV_VERSION" "$LIBUV_SOURCE"
[[ "$(git -C "$LIBUV_SOURCE" rev-parse HEAD)" == "$LIBUV_COMMIT" ]] || {
  echo "LibUV tag v$LIBUV_VERSION did not resolve to pinned commit $LIBUV_COMMIT" >&2
  exit 1
}
fetch_checkout https://github.com/openssl/openssl.git "openssl-$OPENSSL_VERSION" "$OPENSSL_SOURCE"
[[ "$(git -C "$OPENSSL_SOURCE" rev-parse HEAD)" == "$OPENSSL_COMMIT" ]] || {
  echo "OpenSSL tag openssl-$OPENSSL_VERSION did not resolve to pinned commit $OPENSSL_COMMIT" >&2
  exit 1
}

git -C "$LEAN_SOURCE" apply "$TOOLCHAIN_ROOT/patches/0001-cmake-treat-android-as-elf-platform.patch"
echo "Pinned sources are ready under $WORK_DIR/src"
