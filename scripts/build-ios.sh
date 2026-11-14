#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Builds everything the iOS app needs from the Rust bridge and generates the Xcode project.
# Runs on macOS only (needs Xcode, XcodeGen, cmake and the Rust toolchain of this repository).
#
#   scripts/build-ios.sh          bridge for device and simulator, Swift bindings,
#                                 MumcordBridge.xcframework, design tokens, Mumcord.xcodeproj
#   scripts/build-ios.sh --test   the same, then build the app and run its unit and UI tests
#                                 in the simulator
#
# The simulator is chosen by name: IOS_SIMULATOR="iPhone 17" scripts/build-ios.sh --test
set -euo pipefail

BRIDGE_PACKAGE="mumcord-mobile-bridge"
RUST_LIBRARY="libmumcord_mobile.a"
DEVICE_TARGET="aarch64-apple-ios"
SIMULATOR_TARGET="aarch64-apple-ios-sim"
# Same value as the deployment target in ios/project.yml, so the C parts of the engine are
# compiled for the iOS version the app is linked for.
export IPHONEOS_DEPLOYMENT_TARGET="17.0"
SIMULATOR_NAME="${IOS_SIMULATOR:-iPhone 17}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IOS_DIR="${REPO_ROOT}/ios"
CARGO_TARGET="${CARGO_TARGET_DIR:-${REPO_ROOT}/target}"
# Git-ignored outputs.
GENERATED_DIR="${IOS_DIR}/Bridge/Generated"
XCFRAMEWORK="${IOS_DIR}/Bridge/MumcordBridge.xcframework"
BUILD_DIR="${IOS_DIR}/build"
BINDINGS_DIR="${BUILD_DIR}/bindings"
RESULT_BUNDLE="${BUILD_DIR}/TestResults.xcresult"
# The folder is named after the C module, so that its `module.modulemap` cannot clash with the
# module map of another xcframework.
HEADERS_DIR="${BUILD_DIR}/headers"
FFI_MODULE="MumcordBridgeFFI"

log() { printf '==> %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

run_tests=false
for argument in "$@"; do
  case "${argument}" in
    --test) run_tests=true ;;
    *) die "unknown argument '${argument}' (usage: scripts/build-ios.sh [--test])" ;;
  esac
done

[[ "$(uname -s)" == "Darwin" ]] || die "the iOS build needs macOS with Xcode; this is $(uname -s)"
for tool in cargo xcodebuild xcrun xcodegen cmake python3; do
  command -v "${tool}" >/dev/null || die "'${tool}' not found (Xcode, 'brew install xcodegen cmake', rustup; python3 ships with the Xcode command line tools)"
done

cd "${REPO_ROOT}"

for target in "${DEVICE_TARGET}" "${SIMULATOR_TARGET}"; do
  log "Building the bridge for ${target}"
  cargo build --package "${BRIDGE_PACKAGE}" --lib --release --target "${target}"
done
device_library="${CARGO_TARGET}/${DEVICE_TARGET}/release/${RUST_LIBRARY}"
simulator_library="${CARGO_TARGET}/${SIMULATOR_TARGET}/release/${RUST_LIBRARY}"

log "Generating the Swift bindings"
rm -rf "${BINDINGS_DIR}" "${HEADERS_DIR}" "${GENERATED_DIR}" "${XCFRAMEWORK}"
mkdir -p "${BINDINGS_DIR}" "${HEADERS_DIR}/${FFI_MODULE}" "${GENERATED_DIR}"
cargo run --package "${BRIDGE_PACKAGE}" --features bindgen --bin uniffi-bindgen -- \
  generate --library "${simulator_library}" --language swift --out-dir "${BINDINGS_DIR}"
cp "${BINDINGS_DIR}/MumcordBridge.swift" "${GENERATED_DIR}/"
cp "${BINDINGS_DIR}/${FFI_MODULE}.h" "${HEADERS_DIR}/${FFI_MODULE}/"
cp "${BINDINGS_DIR}/${FFI_MODULE}.modulemap" "${HEADERS_DIR}/${FFI_MODULE}/module.modulemap"

log "Assembling $(basename "${XCFRAMEWORK}")"
xcodebuild -create-xcframework \
  -library "${device_library}" -headers "${HEADERS_DIR}" \
  -library "${simulator_library}" -headers "${HEADERS_DIR}" \
  -output "${XCFRAMEWORK}"

# The generated files must exist before XcodeGen runs: it lists the files of the source folders.
log "Generating the design tokens, icons and fonts"
python3 "${IOS_DIR}/Tools/generate_design.py"

log "Generating the Xcode project"
xcodegen generate --spec "${IOS_DIR}/project.yml" --project "${IOS_DIR}"

if [[ "${run_tests}" == true ]]; then
  available="$(xcrun simctl list devices available)"
  if ! grep -qF "    ${SIMULATOR_NAME} (" <<<"${available}"; then
    printf '%s\n' "${available}" >&2
    die "simulator '${SIMULATOR_NAME}' is not installed; pick one from the list above with IOS_SIMULATOR=\"<name>\""
  fi

  log "Building and testing the app in the simulator '${SIMULATOR_NAME}'"
  # xcodebuild refuses to overwrite an existing result bundle.
  rm -rf "${RESULT_BUNDLE}"
  xcodebuild test \
    -project "${IOS_DIR}/Mumcord.xcodeproj" \
    -scheme Mumcord \
    -destination "platform=iOS Simulator,name=${SIMULATOR_NAME}" \
    -derivedDataPath "${BUILD_DIR}/DerivedData" \
    -resultBundlePath "${RESULT_BUNDLE}"
fi

log "Done"
