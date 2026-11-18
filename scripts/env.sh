#!/usr/bin/env bash
# Sourced by build scripts to use the isolated toolchain under .toolchain/

STANDBY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN_DIR="$STANDBY_ROOT/.toolchain"

export JAVA_HOME="$TOOLCHAIN_DIR/jdk-21"
export ANDROID_HOME="$TOOLCHAIN_DIR/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export ANDROID_USER_HOME="$TOOLCHAIN_DIR/android-home"
export RUSTUP_HOME="$TOOLCHAIN_DIR/rustup"
export CARGO_HOME="$TOOLCHAIN_DIR/cargo"
export GRADLE_USER_HOME="$TOOLCHAIN_DIR/gradle-home"

export PATH="$JAVA_HOME/bin:$CARGO_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$TOOLCHAIN_DIR/gradle-8.13/bin:$PATH"
