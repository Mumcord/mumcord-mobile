#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Builds everything the iOS app needs from the Rust bridge and generates the Xcode project.
# Runs on macOS only (needs Xcode, XcodeGen, cmake, meson, ninja and the Rust toolchain of this
# repository). The video codec libraries come from scripts/build-codecs.sh (kept in .codecs/).
#
#   scripts/build-ios.sh          bridge for device and simulator, Swift bindings,
#                                 MumcordBridge.xcframework, design tokens, Mumcord.xcodeproj
#   scripts/build-ios.sh --test   the same, then build the app once and run every test group
#                                 in the simulator, one after the other
#
# CI splits the work over several jobs with these steps:
#
#   scripts/build-ios.sh --build-for-testing
#                                 the same as without a flag, then build the app and its tests
#                                 into ios/build/Mumcord.xctestproducts (relocatable)
#   scripts/build-ios.sh --test-without-building <group>
#                                 run one test group from the test products; needs only Xcode
#   scripts/build-ios.sh --list-groups
#                                 print the names of the test groups as a JSON array
#   scripts/build-ios.sh --check-groups
#                                 fail if a test class belongs to no group; runs on any OS
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
TEST_PRODUCTS="${BUILD_DIR}/Mumcord.xctestproducts"
# The folder is named after the C module, so that its `module.modulemap` cannot clash with the
# module map of another xcframework.
HEADERS_DIR="${BUILD_DIR}/headers"
FFI_MODULE="MumcordBridgeFFI"

log() { printf '==> %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# The test groups: one place that says which tests run in which CI job. Every UI test class
# (ios/Tests/UI) must be listed in exactly one group, or --check-groups fails. The unit tests are
# one bundle (MumcordTests) and belong to one group as a whole. Balanced by the measured run
# times, so the groups finish at about the same time.
TEST_GROUPS=(app-screens core voice-chat-screens chat-flow messages output-settings identities)
group_tests() {
  case "$1" in
    app-screens)
      printf '%s\n' MumcordUITests/AppScreenshotUITests MumcordUITests/DesignReferenceUITests \
        MumcordUITests/LaunchUITests MumcordUITests/PublicServersFlowUITests \
        MumcordUITests/PublicServersScreenshotUITests \
        MumcordUITests/ProfileThemesScreenshotUITests ;;
    core)
      printf '%s\n' MumcordTests MumcordUITests/AppFlowUITests MumcordUITests/VoiceFlowUITests ;;
    voice-chat-screens)
      printf '%s\n' MumcordUITests/VoiceScreenshotUITests MumcordUITests/ChatScreenshotUITests \
        MumcordUITests/CameraShareFlowUITests MumcordUITests/CameraShareScreenshotUITests ;;
    chat-flow)
      printf '%s\n' MumcordUITests/ChatFlowUITests MumcordUITests/SessionEndedUITests \
        MumcordUITests/ModerationFlowUITests MumcordUITests/ModerationScreenshotUITests \
        MumcordUITests/ActivityFlowUITests ;;
    messages)
      printf '%s\n' MumcordUITests/MessagesFlowUITests MumcordUITests/MessagesScreenshotUITests \
        MumcordUITests/StreamsFlowUITests MumcordUITests/StreamsScreenshotUITests ;;
    output-settings)
      printf '%s\n' MumcordUITests/OutputFlowUITests MumcordUITests/PhoneScreenshotUITests \
        MumcordUITests/SettingsFlowUITests MumcordUITests/MoreScreenshotUITests ;;
    identities)
      printf '%s\n' MumcordUITests/IdentitiesFlowUITests MumcordUITests/IdentitiesScreenshotUITests \
        MumcordUITests/ProfileThemesFlowUITests MumcordUITests/ServerListMoreFlowUITests \
        MumcordUITests/ServerListMoreScreenshotUITests ;;
    *) die "unknown test group '$1' (groups: ${TEST_GROUPS[*]})" ;;
  esac
}

check_groups() {
  local grouped listed status=0 name
  grouped="$(for group in "${TEST_GROUPS[@]}"; do group_tests "${group}"; done | sort)"
  listed="$(grep -rhoE '^(final )?class [A-Za-z0-9_]+: XCTestCase' "${IOS_DIR}/Tests/UI" |
    sed -E 's/.*class ([A-Za-z0-9_]+):.*/MumcordUITests\/\1/' | sort)"
  [[ -n "${listed}" ]] || die "found no UI test class in ios/Tests/UI"
  while IFS= read -r name; do
    [[ -z "${name}" ]] || { printf 'error: %s is in no test group; add it to group_tests in scripts/build-ios.sh\n' "${name}" >&2; status=1; }
  done < <(comm -23 <(printf '%s\n' "${listed}") <(printf '%s\n' "${grouped}"))
  while IFS= read -r name; do
    [[ -z "${name}" ]] || { printf 'error: %s is in a test group but no such class exists\n' "${name}" >&2; status=1; }
  done < <(comm -13 <(printf '%s\n' "${listed}") <(printf '%s\n' "${grouped}") | grep -v '^MumcordTests$' || true)
  [[ "$(printf '%s\n' "${grouped}" | sort | uniq -d)" == "" ]] || { printf 'error: a test is in more than one group\n' >&2; status=1; }
  [[ "$(printf '%s\n' "${grouped}" | grep -c '^MumcordTests$')" == 1 ]] || { printf 'error: MumcordTests must be in exactly one group\n' >&2; status=1; }
  return "${status}"
}

list_groups() {
  local json="" group
  for group in "${TEST_GROUPS[@]}"; do json+="${json:+,}\"${group}\""; done
  printf '[%s]\n' "${json}"
}

# Boots the simulator and allows the microphone. The audio tests open the microphone side of the
# audio unit; allowed up front, the simulator does not put the system's permission question
# over the tests. Never a reason to fail.
prepare_simulator() {
  local available
  available="$(xcrun simctl list devices available)"
  if ! grep -qF "    ${SIMULATOR_NAME} (" <<<"${available}"; then
    printf '%s\n' "${available}" >&2
    die "simulator '${SIMULATOR_NAME}' is not installed; pick one from the list above with IOS_SIMULATOR=\"<name>\""
  fi
  xcrun simctl boot "${SIMULATOR_NAME}" 2>/dev/null || true
  # A cold simulator on a fresh machine takes minutes; wait here, where it shows in the log.
  xcrun simctl bootstatus "${SIMULATOR_NAME}" -b >/dev/null
  xcrun simctl privacy booted grant microphone app.mumcord.mobile ||
    log "warning: could not allow the microphone in the simulator"
}

# Starts the app once and ends it again, so the first UI test does not pay for the first start
# of the app in a cold simulator (which took up to a minute in CI and made that test run out
# of time). The app runs against its fake engine and touches nothing. Never a reason to fail.
warm_up_app() {
  local app
  app="$(find "${TEST_PRODUCTS}" -type d -name Mumcord.app -path '*iphonesimulator*' -print -quit 2>/dev/null)"
  if [[ -z "${app}" ]]; then
    log "warning: found no app in the test products to warm up"
    return 0
  fi
  log "Warming up the app in the simulator"
  if xcrun simctl install booted "${app}" &&
    xcrun simctl launch booted app.mumcord.mobile -mumcordFakeEngine plain -mumcordSetupDone YES >/dev/null; then
    # Long enough for the first screen of a cold start to be drawn.
    sleep 15
    xcrun simctl terminate booted app.mumcord.mobile || true
  else
    log "warning: could not warm up the app in the simulator"
  fi
}

test_group() {
  local group="$1" bundle="${BUILD_DIR}/TestResults-$1.xcresult" selection=() test
  while IFS= read -r test; do selection+=("-only-testing:${test}"); done < <(group_tests "${group}")
  [[ -d "${TEST_PRODUCTS}" ]] || die "no test products at ${TEST_PRODUCTS}; run with --build-for-testing first"
  log "Running the test group '${group}' in the simulator '${SIMULATOR_NAME}'"
  # xcodebuild refuses to overwrite an existing result bundle.
  rm -rf "${bundle}"
  xcodebuild test-without-building \
    -testProductsPath "${TEST_PRODUCTS}" \
    -destination "platform=iOS Simulator,name=${SIMULATOR_NAME}" \
    -resultBundlePath "${bundle}" \
    "${selection[@]}"
}

mode=build
group=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --test) mode=test ;;
    --build-for-testing) mode=build-for-testing ;;
    --test-without-building)
      mode=test-without-building
      [[ $# -ge 2 ]] || die "--test-without-building needs a group (${TEST_GROUPS[*]})"
      group="$2"
      shift ;;
    --list-groups) mode=list-groups ;;
    --check-groups) mode=check-groups ;;
    *) die "unknown argument '$1' (usage: scripts/build-ios.sh [--test | --build-for-testing | --test-without-building <group> | --list-groups | --check-groups])" ;;
  esac
  shift
done

case "${mode}" in
  list-groups) list_groups; exit 0 ;;
  check-groups) check_groups; exit $? ;;
esac

[[ "$(uname -s)" == "Darwin" ]] || die "the iOS build needs macOS with Xcode; this is $(uname -s)"
if [[ "${mode}" == test-without-building ]]; then
  group_tests "${group}" >/dev/null
  command -v xcodebuild >/dev/null || die "'xcodebuild' not found (Xcode)"
  prepare_simulator
  warm_up_app
  test_group "${group}"
  log "Done"
  exit 0
fi

check_groups
for tool in cargo xcodebuild xcrun xcodegen cmake meson ninja python3; do
  command -v "${tool}" >/dev/null || die "'${tool}' not found (Xcode, 'brew install xcodegen cmake meson ninja', rustup; python3 ships with the Xcode command line tools)"
done

cd "${REPO_ROOT}"

for target in "${DEVICE_TARGET}" "${SIMULATOR_TARGET}"; do
  codecs="$("${REPO_ROOT}/scripts/build-codecs.sh" "${target}")"
  case "${target}" in
    "${DEVICE_TARGET}") sdk="iphoneos" ;;
    *) sdk="iphonesimulator" ;;
  esac
  log "Building the bridge for ${target}"
  # The engine generates the codec bindings with libclang, which needs the target's SDK for the
  # C library headers.
  env "BINDGEN_EXTRA_CLANG_ARGS_${target//-/_}=-isysroot $(xcrun --sdk "${sdk}" --show-sdk-path)" \
    LIBVPX_STATIC_DIR="${codecs}/libvpx" DAV1D_STATIC_DIR="${codecs}/dav1d" \
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

if [[ "${mode}" == build-for-testing || "${mode}" == test ]]; then
  log "Building the app and its tests"
  # A test products bundle carries everything the tests need, so another machine can run them
  # without building.
  rm -rf "${TEST_PRODUCTS}"
  xcodebuild build-for-testing \
    -project "${IOS_DIR}/Mumcord.xcodeproj" \
    -scheme Mumcord \
    -destination "platform=iOS Simulator,name=${SIMULATOR_NAME}" \
    -derivedDataPath "${BUILD_DIR}/DerivedData" \
    -testProductsPath "${TEST_PRODUCTS}"
fi

if [[ "${mode}" == test ]]; then
  prepare_simulator
  warm_up_app
  failed=()
  for group in "${TEST_GROUPS[@]}"; do
    test_group "${group}" || failed+=("${group}")
  done
  [[ ${#failed[@]} -eq 0 ]] || die "test groups failed: ${failed[*]}"
fi

log "Done"
