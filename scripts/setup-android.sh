#!/usr/bin/env bash
# Installs the local Android toolchain for Mumcord mobile on Linux x86_64.
#
# Everything lands in the user's home, nothing needs sudo, no shell profile is
# edited and the system default Java is left alone. Re-running is safe: parts
# that are already present at the pinned version are skipped.
#
#   scripts/setup-android.sh            install (or complete) the toolchain
#   scripts/setup-android.sh --no-emulator   the same without emulator, its image and device
#                                            (enough to build; used by the CI build job)
#   scripts/setup-android.sh --verify   prove it works (SDK list, NDK compile, emulator boot)
#   scripts/setup-android.sh --env      print only the export lines, for eval
set -euo pipefail

# --- Pinned versions -------------------------------------------------------
# Bump these together and re-run the script; see docs/development.md.
JDK_VERSION="21.0.13+9"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz"
JDK_SHA256="d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c"

CMDLINE_TOOLS_VERSION="23.0"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
CMDLINE_TOOLS_SHA256="0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8"

COMPILE_API="37.0"
MIN_API="31"
BUILD_TOOLS_VERSION="37.0.0"
NDK_VERSION="30.0.16248370"
# Builds the small C++ audio library (android/platform/audio); same version as in
# android/gradle/libs.versions.toml.
CMAKE_VERSION="3.31.6"
SYSTEM_IMAGE="system-images;android-${COMPILE_API};google_apis;x86_64"
# sdkmanager cannot pin the emulator; this is the revision the setup was
# verified with. A different one only triggers a warning.
EMULATOR_REVISION="37.2.13"

CARGO_NDK_VERSION="4.1.2"
RUST_TARGETS=(aarch64-linux-android x86_64-linux-android)

AVD_NAME="mumcord-test"
AVD_DEVICE="pixel_7"
# Not the default 5554, so the check does not collide with an emulator the
# developer already has open.
VERIFY_PORT=5580
BOOT_TIMEOUT_SECONDS=300

# --- Locations -------------------------------------------------------------
SDK_ROOT="${HOME}/Android/Sdk"
JDK_HOME="${HOME}/.local/share/mumcord/jdk-${JDK_VERSION}"
NDK_HOME="${SDK_ROOT}/ndk/${NDK_VERSION}"
AVD_HOME="${ANDROID_AVD_HOME:-${HOME}/.android/avd}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/avdmanager"
ADB="${SDK_ROOT}/platform-tools/adb"
EMULATOR="${SDK_ROOT}/emulator/emulator"

log() { printf '==> %s\n' "$*" >&2; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

print_env() {
  printf 'export ANDROID_HOME="%s"\n' "${SDK_ROOT}"
  printf 'export ANDROID_NDK_HOME="%s"\n' "${NDK_HOME}"
  printf 'export JAVA_HOME="%s"\n' "${JDK_HOME}"
}

check_host() {
  [[ "$(uname -s)" == "Linux" && "$(uname -m)" == "x86_64" ]] \
    || die "this script supports Linux x86_64 only"
  local tool
  for tool in curl unzip tar sha256sum cargo rustup; do
    command -v "${tool}" >/dev/null \
      || die "'${tool}' is missing; install it with the system package manager and re-run"
  done
}

# download <url> <sha256> <target file>
download() {
  curl --fail --location --silent --show-error --output "$3" "$1"
  echo "$2  $3" | sha256sum --check --quiet - \
    || die "checksum mismatch for $1"
}

# Reads Pkg.Revision from an SDK package folder, empty if it is not installed.
pkg_revision() {
  local props="$1/source.properties"
  [[ -f "${props}" ]] || return 0
  sed -n 's/^Pkg\.Revision *= *//p' "${props}" | tr -d '\r'
}

install_jdk() {
  if [[ -x "${JDK_HOME}/bin/java" ]]; then
    log "JDK ${JDK_VERSION} already installed"
    return
  fi
  log "Installing JDK ${JDK_VERSION} (Eclipse Temurin)"
  local tmp
  tmp="$(mktemp -d)"
  download "${JDK_URL}" "${JDK_SHA256}" "${tmp}/jdk.tar.gz"
  mkdir -p "${tmp}/unpacked" "$(dirname "${JDK_HOME}")"
  tar -xzf "${tmp}/jdk.tar.gz" -C "${tmp}/unpacked" --strip-components=1
  mv "${tmp}/unpacked" "${JDK_HOME}"
  rm -rf "${tmp}"
}

install_cmdline_tools() {
  if [[ "$(pkg_revision "${SDK_ROOT}/cmdline-tools/latest")" == "${CMDLINE_TOOLS_VERSION}" ]]; then
    log "Android command-line tools ${CMDLINE_TOOLS_VERSION} already installed"
    return
  fi
  log "Installing Android command-line tools ${CMDLINE_TOOLS_VERSION}"
  local tmp
  tmp="$(mktemp -d)"
  download "${CMDLINE_TOOLS_URL}" "${CMDLINE_TOOLS_SHA256}" "${tmp}/tools.zip"
  unzip -q "${tmp}/tools.zip" -d "${tmp}"
  mkdir -p "${SDK_ROOT}/cmdline-tools"
  rm -rf "${SDK_ROOT}/cmdline-tools/latest"
  mv "${tmp}/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
  rm -rf "${tmp}"
}

# install_sdk_packages <1 to include the emulator and its image, 0 to leave them out>
install_sdk_packages() {
  local with_emulator="$1"
  # sdkmanager verifies the checksum of every package it downloads.
  local -A packages=(
    ["platform-tools"]="platform-tools"
    ["platforms;android-${COMPILE_API}"]="platforms/android-${COMPILE_API}"
    ["platforms;android-${MIN_API}"]="platforms/android-${MIN_API}"
    ["build-tools;${BUILD_TOOLS_VERSION}"]="build-tools/${BUILD_TOOLS_VERSION}"
    ["ndk;${NDK_VERSION}"]="ndk/${NDK_VERSION}"
    ["cmake;${CMAKE_VERSION}"]="cmake/${CMAKE_VERSION}"
  )
  if ((with_emulator)); then
    packages["emulator"]="emulator"
    packages["${SYSTEM_IMAGE}"]="${SYSTEM_IMAGE//;//}"
  fi
  local missing=() package
  for package in "${!packages[@]}"; do
    [[ -f "${SDK_ROOT}/${packages[${package}]}/source.properties" ]] || missing+=("${package}")
  done

  # `yes` gets SIGPIPE once sdkmanager stops reading; that is not a failure.
  log "Accepting Android SDK licences"
  (set +o pipefail; yes | "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --licenses >/dev/null)

  if ((${#missing[@]} == 0)); then
    log "Android SDK packages already installed"
  else
    log "Installing Android SDK packages: ${missing[*]}"
    "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --install "${missing[@]}" >/dev/null
  fi

  ((with_emulator)) || return 0
  local emulator_revision
  emulator_revision="$(pkg_revision "${SDK_ROOT}/emulator")"
  if [[ "${emulator_revision}" != "${EMULATOR_REVISION}" ]]; then
    warn "emulator is ${emulator_revision}, the setup was verified with ${EMULATOR_REVISION}"
  fi
}

create_avd() {
  if [[ -f "${AVD_HOME}/${AVD_NAME}.ini" ]]; then
    log "Emulator device '${AVD_NAME}' already exists"
    return
  fi
  log "Creating emulator device '${AVD_NAME}'"
  # "no" answers the question about a custom hardware profile.
  echo no | "${AVDMANAGER}" create avd --name "${AVD_NAME}" \
    --package "${SYSTEM_IMAGE}" --device "${AVD_DEVICE}" >/dev/null
}

install_rust_parts() {
  # Run inside the repository so rustup uses the toolchain pinned in
  # rust-toolchain.toml and fetches it if it is missing.
  log "Installing Rust targets: ${RUST_TARGETS[*]}"
  (cd "${REPO_ROOT}" && rustup toolchain install >&2 && rustup target add "${RUST_TARGETS[@]}" >&2)

  if cargo ndk --version 2>/dev/null | grep -qF "${CARGO_NDK_VERSION}"; then
    log "cargo-ndk ${CARGO_NDK_VERSION} already installed"
  else
    log "Installing cargo-ndk ${CARGO_NDK_VERSION}"
    # Built from the home folder so the default toolchain is used, not the
    # repository's pinned one (cargo-ndk is a host tool with its own MSRV).
    (cd "${HOME}" && cargo install cargo-ndk --version "${CARGO_NDK_VERSION}" --locked --quiet >&2)
  fi
}

verify_ndk_compile() {
  log "Compiling a C file for aarch64 with the NDK"
  local tmp clang="${NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${MIN_API}-clang"
  tmp="$(mktemp -d)"
  printf 'int answer(void) { return 42; }\n' >"${tmp}/probe.c"
  "${clang}" -shared -o "${tmp}/libprobe.so" "${tmp}/probe.c"
  # Captured first: piping straight into `grep -q` would fail under pipefail
  # when grep closes the pipe early.
  local header
  header="$("${NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "${tmp}/libprobe.so")"
  [[ "${header}" == *AArch64* ]] || die "NDK output is not an aarch64 library"
  rm -rf "${tmp}"
}

verify_emulator_boot() {
  [[ -r /dev/kvm && -w /dev/kvm ]] \
    || die "no access to /dev/kvm; the emulator needs KVM (add the user to the 'kvm' group)"
  log "Booting '${AVD_NAME}' headless (up to ${BOOT_TIMEOUT_SECONDS}s)"
  local serial="emulator-${VERIFY_PORT}" log_file emulator_pid booted=""
  log_file="$(mktemp)"
  "${EMULATOR}" -avd "${AVD_NAME}" -port "${VERIFY_PORT}" -no-window -no-audio \
    -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"${log_file}" 2>&1 &
  emulator_pid=$!
  # Never leave an emulator running behind, whatever happens below.
  trap 'kill "${emulator_pid}" 2>/dev/null || true' EXIT

  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  while ((SECONDS < deadline)); do
    if ! kill -0 "${emulator_pid}" 2>/dev/null; then
      tail -n 20 "${log_file}" >&2
      die "the emulator exited before it finished booting"
    fi
    booted="$("${ADB}" -s "${serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [[ "${booted}" == "1" ]] && break
    sleep 2
  done
  if [[ "${booted}" != "1" ]]; then
    tail -n 20 "${log_file}" >&2
    die "the emulator did not boot within ${BOOT_TIMEOUT_SECONDS}s"
  fi
  log "Emulator booted (sys.boot_completed=1), shutting it down"
  "${ADB}" -s "${serial}" emu kill >/dev/null
  wait "${emulator_pid}" 2>/dev/null || true
  trap - EXIT
  rm -f "${log_file}"
}

verify() {
  [[ -x "${SDKMANAGER}" ]] || die "toolchain not installed; run scripts/setup-android.sh first"
  log "Installed SDK packages"
  "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --list_installed
  "${JDK_HOME}/bin/java" -version
  cargo ndk --version
  verify_ndk_compile
  verify_emulator_boot
  log "Android toolchain verified"
}

main() {
  local mode="${1:-install}"
  # The SDK tools are Java programs; run them on the pinned JDK, never on the
  # system default.
  export JAVA_HOME="${JDK_HOME}" ANDROID_HOME="${SDK_ROOT}" ANDROID_NDK_HOME="${NDK_HOME}"

  case "${mode}" in
    --env)
      print_env
      ;;
    --verify)
      check_host
      verify
      ;;
    install | --no-emulator)
      check_host
      install_jdk
      install_cmdline_tools
      if [[ "${mode}" == "install" ]]; then
        install_sdk_packages 1
        create_avd
      else
        install_sdk_packages 0
      fi
      install_rust_parts
      log "Done. Set these variables in the shell that builds the project:"
      print_env
      ;;
    *)
      die "unknown option '${mode}' (use no option, --no-emulator, --verify or --env)"
      ;;
  esac
}

main "$@"
