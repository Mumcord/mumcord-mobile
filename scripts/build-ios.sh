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
# The simulator: IOS_SIMULATOR names one (by name or UDID) and then must exist, e.g.
#   IOS_SIMULATOR="iPhone 17" scripts/build-ios.sh --test
# Without it the script takes "iPhone 17" when it is installed, else the newest available iPhone
# simulator, else it creates one for the newest iOS runtime. GitHub's macOS runners do not always
# have the same simulators. The test group `tablet` runs on an iPad simulator instead, chosen the
# same way: IOS_IPAD_SIMULATOR, else "iPad Air 11-inch (M3)" (the size of the tablet mockups),
# else the newest available iPad simulator, else a new one.
set -euo pipefail

BRIDGE_PACKAGE="mumcord-mobile-bridge"
RUST_LIBRARY="libmumcord_mobile.a"
DEVICE_TARGET="aarch64-apple-ios"
SIMULATOR_TARGET="aarch64-apple-ios-sim"
# Same value as the deployment target in ios/project.yml, so the C parts of the engine are
# compiled for the iOS version the app is linked for.
export IPHONEOS_DEPLOYMENT_TARGET="17.0"
DEFAULT_SIMULATOR="iPhone 17"
DEFAULT_IPAD_SIMULATOR="iPad Air 11-inch (M3)"
# Set by choose_simulator.
SIMULATOR_ID=""
SIMULATOR_NAME=""
SIMULATOR_FAMILY=""

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
TEST_GROUPS=(app-screens core voice-chat-screens chat-flow messages output-settings identities tablet)
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
        MumcordUITests/SettingsFlowUITests MumcordUITests/MoreScreenshotUITests \
        MumcordUITests/AccessibilityUITests MumcordUITests/LargeTextScreenshotUITests ;;
    identities)
      printf '%s\n' MumcordUITests/IdentitiesFlowUITests MumcordUITests/IdentitiesScreenshotUITests \
        MumcordUITests/ProfileThemesFlowUITests MumcordUITests/ServerListMoreFlowUITests \
        MumcordUITests/ServerListMoreScreenshotUITests MumcordUITests/LanguageFlowUITests \
        MumcordUITests/LanguageScreenshotUITests ;;
    tablet)
      printf '%s\n' MumcordUITests/TabletFlowUITests MumcordUITests/TabletScreenshotUITests \
        MumcordUITests/TabletAccessibilityUITests ;;
    *) die "unknown test group '$1' (groups: ${TEST_GROUPS[*]})" ;;
  esac
}

# The device family a test group runs on: the two-column layout needs an iPad.
group_family() {
  case "$1" in
    tablet) echo iPad ;;
    *) echo iPhone ;;
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

# Prints what to do for the simulator of a device family (iPhone or iPad), from
# `xcrun simctl list --json` on standard input: "use<TAB><udid><TAB><name>" for an installed one,
# or "create<TAB><device type><TAB><runtime><TAB><name>" when none of the family is installed.
# Newest means the newest iOS runtime first, then the newest model (its model identifier, such as
# "iPhone18,3").
SIMULATOR_CHOICE='
import json, re, sys

wanted, default, family = sys.argv[1], sys.argv[2], sys.argv[3]
listing = json.load(sys.stdin)

def version(text):
    return tuple(int(part) for part in re.findall(r"\d+", text or ""))

types = {t["identifier"]: t for t in listing["devicetypes"]}
def model(type_id):
    return version(types.get(type_id, {}).get("modelIdentifier"))

runtimes = {r["identifier"]: r for r in listing["runtimes"]
            if r.get("platform") == "iOS" and r.get("isAvailable")}
devices = [(runtimes[runtime], device) for runtime, found in listing["devices"].items()
           if runtime in runtimes for device in found if device.get("isAvailable")]

def use(device):
    print("use", device["udid"], device["name"], sep="\t")
    sys.exit(0)

if wanted:
    for _, device in devices:
        if wanted in (device["name"], device["udid"]):
            use(device)
    sys.exit("simulator \"%s\" is not installed" % wanted)
for _, device in devices:
    if device["name"] == default:
        use(device)
matching = [(runtime, device) for runtime, device in devices
            if types.get(device["deviceTypeIdentifier"], {}).get("productFamily") == family]
if matching:
    use(max(matching, key=lambda pair: (version(pair[0]["version"]), model(pair[1]["deviceTypeIdentifier"])))[1])
if not runtimes:
    sys.exit("no iOS simulator runtime is installed")
runtime = max(runtimes.values(), key=lambda r: version(r["version"]))
supported = [t for t in runtime.get("supportedDeviceTypes", []) if t.get("productFamily") == family]
if not supported:
    sys.exit("the runtime %s supports no %s" % (runtime["name"], family))
newest = max(supported, key=lambda t: model(t["identifier"]))
print("create", newest["identifier"], runtime["identifier"], newest["name"], sep="\t")
'

# Sets SIMULATOR_ID and SIMULATOR_NAME for a device family (iPhone unless named); creates a
# simulator when none of the family is installed. Every later step addresses the simulator by its
# UDID, so other simulators that run on the same Mac (booted or not, with the same name or not)
# are never touched.
choose_simulator() {
  local family="${1:-iPhone}" choice action rest device_type runtime wanted default
  [[ -z "${SIMULATOR_ID}" || "${SIMULATOR_FAMILY}" != "${family}" ]] || return 0
  command -v python3 >/dev/null || die "'python3' not found (ships with the Xcode command line tools)"
  if [[ "${family}" == iPad ]]; then
    wanted="${IOS_IPAD_SIMULATOR:-}" default="${DEFAULT_IPAD_SIMULATOR}"
  else
    wanted="${IOS_SIMULATOR:-}" default="${DEFAULT_SIMULATOR}"
  fi
  choice="$(xcrun simctl list --json | python3 -c "${SIMULATOR_CHOICE}" "${wanted}" "${default}" "${family}")" ||
    die "no ${family} simulator to run on (see above); pick an installed one with IOS_SIMULATOR or IOS_IPAD_SIMULATOR=\"<name>\" ('xcrun simctl list devices available')"
  SIMULATOR_FAMILY="${family}"
  IFS=$'\t' read -r action rest <<<"${choice}"
  case "${action}" in
    use) IFS=$'\t' read -r SIMULATOR_ID SIMULATOR_NAME <<<"${rest}" ;;
    create)
      IFS=$'\t' read -r device_type runtime SIMULATOR_NAME <<<"${rest}"
      log "No ${family} simulator is installed; creating '${SIMULATOR_NAME}' (${runtime})"
      SIMULATOR_ID="$(xcrun simctl create "${SIMULATOR_NAME}" "${device_type}" "${runtime}")" ;;
    *) die "unexpected simulator choice '${choice}'" ;;
  esac
  log "Simulator: ${SIMULATOR_NAME} (${SIMULATOR_ID})"
}

# Boots the simulator and allows the microphone. The audio tests open the microphone side of the
# audio unit; allowed up front, the simulator does not put the system's permission question
# over the tests. Never a reason to fail.
prepare_simulator() {
  choose_simulator "${1:-iPhone}"
  xcrun simctl boot "${SIMULATOR_ID}" 2>/dev/null || true
  # A cold simulator on a fresh machine takes minutes; wait here, where it shows in the log.
  xcrun simctl bootstatus "${SIMULATOR_ID}" -b >/dev/null
  xcrun simctl privacy "${SIMULATOR_ID}" grant microphone app.mumcord.mobile ||
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
  if xcrun simctl install "${SIMULATOR_ID}" "${app}" &&
    xcrun simctl launch "${SIMULATOR_ID}" app.mumcord.mobile -mumcordFakeEngine plain -mumcordSetupDone YES >/dev/null; then
    # Long enough for the first screen of a cold start to be drawn.
    sleep 15
    xcrun simctl terminate "${SIMULATOR_ID}" app.mumcord.mobile || true
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
    -destination "platform=iOS Simulator,id=${SIMULATOR_ID}" \
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
  prepare_simulator "$(group_family "${group}")"
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
  choose_simulator
  xcodebuild build-for-testing \
    -project "${IOS_DIR}/Mumcord.xcodeproj" \
    -scheme Mumcord \
    -destination "platform=iOS Simulator,id=${SIMULATOR_ID}" \
    -derivedDataPath "${BUILD_DIR}/DerivedData" \
    -testProductsPath "${TEST_PRODUCTS}"
fi

if [[ "${mode}" == test ]]; then
  failed=()
  for group in "${TEST_GROUPS[@]}"; do
    family="$(group_family "${group}")"
    if [[ "${family}" != "${SIMULATOR_FAMILY}" || -z "${booted:-}" ]]; then
      # One simulator at a time: the one of the groups before is shut down first.
      [[ -z "${booted:-}" ]] || xcrun simctl shutdown "${SIMULATOR_ID}" 2>/dev/null || true
      prepare_simulator "${family}"
      warm_up_app
      booted=yes
    fi
    test_group "${group}" || failed+=("${group}")
  done
  [[ ${#failed[@]} -eq 0 ]] || die "test groups failed: ${failed[*]}"
fi

log "Done"
