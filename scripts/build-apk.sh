#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/env.sh"

BUILD_TYPE="${1:-debug}"
RUST_DIR="$ROOT_DIR/rust"
ANDROID_DIR="$ROOT_DIR/android"
JNI_DIR="$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a"
UNIFFI_OUT="$ANDROID_DIR/app/src/main/java"

mkdir -p "$JNI_DIR" "$UNIFFI_OUT" "$ROOT_DIR/dist"

echo "==> Building Rust standby-core for aarch64-linux-android (16KB page-aligned, release-optimized)..."
CARGO_FLAGS=(--release)

pushd "$RUST_DIR" >/dev/null
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
  cargo ndk --target arm64-v8a --platform 31 -o "$ANDROID_DIR/app/src/main/jniLibs" \
  build -p standby-core "${CARGO_FLAGS[@]}"

SO_PATH="$JNI_DIR/libstandby_core.so"
if [ ! -f "$SO_PATH" ]; then
  echo "ERROR: Expected $SO_PATH not found" >&2
  exit 1
fi

echo "==> Generating UniFFI Kotlin bindings..."
cargo run -p standby-core --bin uniffi-bindgen -- generate \
  --library "$SO_PATH" \
  --language kotlin \
  --out-dir "$UNIFFI_OUT"
popd >/dev/null

echo "==> Building Android APK ($BUILD_TYPE)..."
pushd "$ANDROID_DIR" >/dev/null
export STANDBY_SKIP_GRADLE_RUST=1
if [ "$BUILD_TYPE" = "release" ]; then
  ./gradlew assembleRelease --no-daemon
  cp -f "$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk" "$ROOT_DIR/dist/standby-release.apk" 2>/dev/null || \
    cp -f "$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" "$ROOT_DIR/dist/standby-release.apk"
  cp -f "$ROOT_DIR/dist/standby-release.apk" "$ROOT_DIR/dist/standby-debug.apk"
else
  ./gradlew assembleDebug --no-daemon
  cp -f "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" "$ROOT_DIR/dist/standby-debug.apk"
fi
popd >/dev/null

echo "==> Build complete! Output in $ROOT_DIR/dist/"
ls -lh "$ROOT_DIR/dist/"
