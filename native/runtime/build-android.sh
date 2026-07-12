#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    ANDROID_SDK_ROOT="$ANDROID_HOME"
  elif [[ -f "$PROJECT_DIR/local.properties" ]]; then
    ANDROID_SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_DIR/local.properties" | sed 's/\\:/:/g; s/\\ / /g')"
  else
    ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
  fi
fi

NDK_VERSION="29.0.14206865"
NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
TOOLCHAIN=""
for host_tag in darwin-arm64 darwin-x86_64 linux-x86_64 linux-arm64 windows-x86_64; do
  candidate="$NDK_ROOT/toolchains/llvm/prebuilt/$host_tag/bin"
  if [[ -x "$candidate/aarch64-linux-android26-clang" ]]; then
    TOOLCHAIN="$candidate"
    break
  fi
done
if [[ -z "$TOOLCHAIN" ]]; then
  echo "No compatible Android NDK toolchain found under $NDK_ROOT" >&2
  exit 2
fi
CLANG="$TOOLCHAIN/aarch64-linux-android26-clang"
AR="$TOOLCHAIN/llvm-ar"

if [[ ! -x "$CLANG" || ! -x "$AR" ]]; then
  echo "Missing Android NDK $NDK_VERSION toolchain under $NDK_ROOT" >&2
  exit 2
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$AR"
export CARGO_TARGET_DIR="$PROJECT_DIR/build/rust-target"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"

cargo build \
  --manifest-path "$SCRIPT_DIR/Cargo.toml" \
  --locked \
  --release \
  --target aarch64-linux-android

OUTPUT_DIR="$PROJECT_DIR/app/build/generated/rustJniLibs/arm64-v8a"
mkdir -p "$OUTPUT_DIR"
install -m 0644 \
  "$CARGO_TARGET_DIR/aarch64-linux-android/release/libelysium_runtime.so" \
  "$OUTPUT_DIR/libelysium_runtime.so"
