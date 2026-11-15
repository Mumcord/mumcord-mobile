#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Builds the Rust engine for Android and generates its Kotlin bindings.
#
# The video codec libraries of each ABI come from scripts/build-codecs.sh, which builds them
# once and keeps them in .codecs/ (or MUMCORD_CODECS_DIR).
#
# Outputs (git-ignored, picked up by the Gradle module android/engine):
#   android/engine/src/main/jniLibs/<abi>/libmumcord_mobile.so   arm64-v8a (phones), x86_64 (emulator)
#   android/engine/src/main/kotlin/app/mumcord/mobile/bridge/    generated Kotlin bindings
#
# Needs the toolchain of scripts/setup-android.sh and its environment:
#   eval "$(scripts/setup-android.sh --env)"
#
# Gradle runs this script by itself before it compiles (task :engine:buildRustBridge); running
# it by hand is only needed to build the native part alone.
set -euo pipefail

# Lowest Android version the app supports (minSdk in android/app/build.gradle.kts).
MIN_API="31"
ABIS=(arm64-v8a x86_64)
CRATE="mumcord-mobile-bridge"
KOTLIN_PACKAGE_DIR="app/mumcord/mobile/bridge"

die() {
  printf 'build-android: %s\n' "$*" >&2
  exit 1
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

[[ -d "${ANDROID_NDK_HOME:-}" ]] \
  || die 'ANDROID_NDK_HOME is not set or does not exist; run: eval "$(scripts/setup-android.sh --env)"'
cargo ndk --version >/dev/null 2>&1 || die "cargo-ndk is missing; run scripts/setup-android.sh"

TARGET_DIR="${CARGO_TARGET_DIR:-${REPO_ROOT}/target}"
case "$(uname -s)" in
  Darwin) ndk_host="darwin-x86_64" ;;
  *) ndk_host="linux-x86_64" ;;
esac
NDK_SYSROOT="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${ndk_host}/sysroot"
JNI_LIBS_DIR="${REPO_ROOT}/android/engine/src/main/jniLibs"
KOTLIN_DIR="${REPO_ROOT}/android/engine/src/main/kotlin"

# Start clean: the engine's helper libraries carry a build hash in their name, so an old one
# would otherwise stay behind and be packaged too.
rm -rf "${JNI_LIBS_DIR}"
# Release profile also for debug apps: the audio path is far too slow when unoptimised.
for abi in "${ABIS[@]}"; do
  case "${abi}" in
    arm64-v8a) target="aarch64-linux-android" ;;
    x86_64) target="x86_64-linux-android" ;;
  esac
  codecs="$("${REPO_ROOT}/scripts/build-codecs.sh" "${target}")"
  # The engine generates the codec bindings with libclang. The NDK's headers refuse a target
  # without API level, which is what bindgen passes by itself, and cargo-ndk only adds the
  # sysroot; the spelling with dashes takes precedence over cargo-ndk's.
  env "BINDGEN_EXTRA_CLANG_ARGS_${target}=--target=${target}${MIN_API} --sysroot=${NDK_SYSROOT}" \
    LIBVPX_STATIC_DIR="${codecs}/libvpx" DAV1D_STATIC_DIR="${codecs}/dav1d" \
    cargo ndk --target "${abi}" --platform "${MIN_API}" --output-dir "${JNI_LIBS_DIR}" \
    build --release --locked --package "${CRATE}"
done

# The binding generator reads the interface out of a library built for this machine.
case "$(uname -s)" in
  Darwin) host_library="libmumcord_mobile.dylib" ;;
  *) host_library="libmumcord_mobile.so" ;;
esac
cargo build --locked --package "${CRATE}"
# Remove old bindings first so a file that is no longer generated cannot linger.
rm -rf "${KOTLIN_DIR:?}/${KOTLIN_PACKAGE_DIR}"
cargo run --locked --package "${CRATE}" --features bindgen --bin uniffi-bindgen -- \
  generate --library "${TARGET_DIR}/debug/${host_library}" --language kotlin --no-format \
  --out-dir "${KOTLIN_DIR}"
