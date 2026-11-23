#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Cross-builds the video codec libraries of the engine for one phone target: libvpx (VP9
# encoder and decoder) and dav1d (AV1 decoder), static and with their assembly. The engine's
# screen-share crate links them when its features `vp9` and `av1-decode` are on and
# LIBVPX_STATIC_DIR / DAV1D_STATIC_DIR point at them; scripts/build-android.sh and
# scripts/build-ios.sh do that for each target they build.
#
#   scripts/build-codecs.sh <target>        build (kept when already built by this script and
#                                           toolchain), then print the target's prefix
#   scripts/build-codecs.sh --id <target>   print the build id: library versions, a hash of this
#                                           script and the toolchain version (CI cache key)
#
# Targets: aarch64-linux-android, x86_64-linux-android (needs ANDROID_NDK_HOME, from
# scripts/setup-android.sh --env), aarch64-apple-ios, aarch64-apple-ios-sim (needs Xcode).
#
# Result below ${MUMCORD_CODECS_DIR:-<repository>/.codecs}/<target>/:
#   libvpx/include/vpx/*.h  libvpx/lib/libvpx.a
#   dav1d/include/dav1d/*.h dav1d/lib/libdav1d.a
#   BUILD-ID                the build id the libraries were built with
#
# Needs curl, make, perl, meson and ninja; the x86_64 emulator target also needs nasm. All build
# output stays below the result folder. CODECS_JOBS limits the parallel compile jobs.
#
# Runs with the bash of macOS (3.2) too: no associative arrays, no mapfile.
set -euo pipefail

# The versions the desktop release builds use (scripts/build-libvpx.sh and scripts/build-dav1d.sh
# of the Mumcord repository), checked against the checksum of their release archive.
LIBVPX_VERSION="1.15.2"
LIBVPX_URL="https://github.com/webmproject/libvpx/archive/refs/tags/v${LIBVPX_VERSION}.tar.gz"
LIBVPX_SHA256="26fcd3db88045dee380e581862a6ef106f49b74b6396ee95c2993a260b4636aa"
DAV1D_VERSION="1.5.5"
DAV1D_URL="https://code.videolan.org/videolan/dav1d/-/archive/${DAV1D_VERSION}/dav1d-${DAV1D_VERSION}.tar.gz"
DAV1D_SHA256="76ad07ab121d68407b64a6f50f540c90ca0b066d178bdc9adbea14acf4ce25a9"

# Lowest Android version (minSdk, scripts/build-android.sh) and iOS version (deployment target,
# ios/project.yml) the app supports: the libraries must not use anything newer.
ANDROID_API="31"
IOS_VERSION="17.0"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${REPO_ROOT}/scripts/build-codecs.sh"
CODECS_DIR="${MUMCORD_CODECS_DIR:-${REPO_ROOT}/.codecs}"

log() { printf '==> %s\n' "$*" >&2; }
die() { printf 'build-codecs: %s\n' "$*" >&2; exit 1; }

usage() { die "usage: build-codecs.sh [--id] <aarch64-linux-android|x86_64-linux-android|aarch64-apple-ios|aarch64-apple-ios-sim>"; }

sha256_of() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

job_count() {
  printf '%s' "${CODECS_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
}

mode="build"
if [[ "${1:-}" == "--id" ]]; then
  mode="id"
  shift
fi
[[ $# -eq 1 ]] || usage
target="$1"

case "${target}" in
  aarch64-linux-android | x86_64-linux-android) platform="android" ;;
  aarch64-apple-ios) platform="ios"; sdk="iphoneos"; clang_target="arm64-apple-ios${IOS_VERSION}" ;;
  aarch64-apple-ios-sim) platform="ios"; sdk="iphonesimulator"; clang_target="arm64-apple-ios${IOS_VERSION}-simulator" ;;
  *) usage ;;
esac

# The toolchain is part of the build id: a new NDK or Xcode rebuilds the libraries.
toolchain_id() {
  if [[ "${platform}" == "android" ]]; then
    [[ -f "${ANDROID_NDK_HOME:-}/source.properties" ]] \
      || die 'ANDROID_NDK_HOME is not set or no NDK; run: eval "$(scripts/setup-android.sh --env)"'
    printf 'ndk%s' "$(sed -n 's/^Pkg.Revision *= *//p' "${ANDROID_NDK_HOME}/source.properties")"
  else
    command -v xcrun >/dev/null || die "xcrun not found; iOS targets build on macOS with Xcode only"
    printf 'xcode%s-%s%s' "$(xcodebuild -version | sed -n 's/^Build version //p')" "${sdk}" \
      "$(xcrun --sdk "${sdk}" --show-sdk-version)"
  fi
}

build_id="libvpx${LIBVPX_VERSION}-dav1d${DAV1D_VERSION}-$(sha256_of "${SCRIPT}" | cut -c1-12)-$(toolchain_id)"
if [[ "${mode}" == "id" ]]; then
  printf '%s\n' "${build_id}"
  exit 0
fi

prefix="${CODECS_DIR}/${target}"
if [[ "$(cat "${prefix}/BUILD-ID" 2>/dev/null)" == "${build_id}" ]]; then
  log "Video codecs for ${target} already built (${build_id})"
  printf '%s\n' "${prefix}"
  exit 0
fi

for tool in curl make perl meson ninja; do
  command -v "${tool}" >/dev/null || die "'${tool}' not found (apt: meson ninja-build nasm, Homebrew: meson ninja)"
done
if [[ "${target}" == "x86_64-linux-android" ]]; then
  command -v nasm >/dev/null || die "'nasm' not found; the x86_64 assembly of libvpx and dav1d needs it"
fi

rm -rf "${prefix}"
work="${prefix}.work"
rm -rf "${work}"
mkdir -p "${work}/bin"
trap 'rm -rf "${work}"' EXIT

# fetch <url> <sha256> <folder name>
fetch() {
  local archive="${work}/$3.tar.gz"
  log "Downloading $3"
  curl -fsSL --retry 5 --retry-delay 5 -o "${archive}" "$1"
  [[ "$(sha256_of "${archive}")" == "$2" ]] || die "checksum of $1 does not match"
  tar -xzf "${archive}" -C "${work}"
  [[ -d "${work}/$3" ]] || die "$1 did not contain $3/"
}

fetch "${LIBVPX_URL}" "${LIBVPX_SHA256}" "libvpx-${LIBVPX_VERSION}"
fetch "${DAV1D_URL}" "${DAV1D_SHA256}" "dav1d-${DAV1D_VERSION}"

# The compilers, the libvpx target and the meson machine description of the target.
if [[ "${platform}" == "android" ]]; then
  case "$(uname -s)" in
    Linux) ndk_host="linux-x86_64" ;;
    Darwin) ndk_host="darwin-x86_64" ;;
    *) die "unsupported build system $(uname -s)" ;;
  esac
  ndk_bin="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${ndk_host}/bin"
  cc="${ndk_bin}/${target}${ANDROID_API}-clang"
  cxx="${ndk_bin}/${target}${ANDROID_API}-clang++"
  ar="${ndk_bin}/llvm-ar"
  strip="${ndk_bin}/llvm-strip"
  [[ -x "${cc}" ]] || die "${cc} not found"
  if [[ "${target}" == "aarch64-linux-android" ]]; then
    vpx_target="arm64-android-gcc"
    vpx_as="${cc}"
    cpu_family="aarch64"
  else
    vpx_target="x86_64-android-gcc"
    vpx_as="nasm"
    cpu_family="x86_64"
  fi
  meson_system="system = 'android'"
else
  sdk_path="$(xcrun --sdk "${sdk}" --show-sdk-path)"
  # libvpx has no target for the arm64 simulator, and its iOS target embeds bitcode, which
  # current Xcode no longer accepts. Both iOS targets therefore use libvpx's arm64 macOS target
  # through a compiler wrapper that swaps the macOS SDK and architecture flags it adds for the
  # iOS ones.
  cat >"${work}/bin/ios-clang" <<EOF
#!/usr/bin/env bash
args=()
skip=""
for arg in "\$@"; do
  if [[ -n "\${skip}" ]]; then skip=""; continue; fi
  case "\${arg}" in
    -isysroot | -arch) skip=1 ;;
    -mmacosx-version-min=*) ;;
    *) args+=("\${arg}") ;;
  esac
done
exec "$(xcrun --sdk "${sdk}" --find clang)" -target ${clang_target} -isysroot "${sdk_path}" \${args[@]+"\${args[@]}"}
EOF
  chmod +x "${work}/bin/ios-clang"
  cc="${work}/bin/ios-clang"
  cxx="${cc}"
  ar="$(xcrun --sdk "${sdk}" --find ar)"
  strip="$(xcrun --sdk "${sdk}" --find strip)"
  vpx_target="arm64-darwin20-gcc"
  vpx_as="${cc}"
  cpu_family="aarch64"
  if [[ "${sdk}" == "iphoneos" ]]; then
    meson_system="system = 'darwin'
subsystem = 'ios'"
  else
    meson_system="system = 'darwin'
subsystem = 'ios-simulator'"
  fi
fi

log "Building libvpx ${LIBVPX_VERSION} for ${target}"
# VP9 only, realtime only: the engine never sends or receives VP8 and always encodes with
# VPX_DL_REALTIME, so the other encoder modes would only add size.
(
  mkdir -p "${work}/libvpx-build"
  cd "${work}/libvpx-build"
  CC="${cc}" CXX="${cxx}" LD="${cc}" AS="${vpx_as}" AR="${ar}" STRIP="${strip}" \
    "${work}/libvpx-${LIBVPX_VERSION}/configure" \
    --target="${vpx_target}" --prefix="${prefix}/libvpx" --libdir="${prefix}/libvpx/lib" \
    --enable-static --disable-shared --enable-pic \
    --disable-vp8 --enable-vp9 --enable-realtime-only \
    --disable-examples --disable-tools --disable-docs --disable-unit-tests \
    --disable-install-bins --disable-install-srcs >&2
  make -j"$(job_count)" >&2
  make install >&2
)

log "Building dav1d ${DAV1D_VERSION} for ${target}"
cat >"${work}/cross.ini" <<EOF
[binaries]
c = '${cc}'
ar = '${ar}'
strip = '${strip}'
nasm = 'nasm'

[properties]
needs_exe_wrapper = true

[host_machine]
${meson_system}
cpu_family = '${cpu_family}'
cpu = '${cpu_family}'
endian = 'little'
EOF
# Only 8-bit decoding, like the desktop: streams are never sent in 10-bit.
meson setup "${work}/dav1d-build" "${work}/dav1d-${DAV1D_VERSION}" \
  --cross-file="${work}/cross.ini" --prefix="${prefix}/dav1d" --libdir=lib \
  --buildtype=release --default-library=static \
  -Dbitdepths=8 -Denable_asm=true -Denable_tools=false -Denable_examples=false \
  -Denable_tests=false -Denable_docs=false -Dlogging=false >&2
meson compile -C "${work}/dav1d-build" -j "$(job_count)" >&2
meson install -C "${work}/dav1d-build" >&2

for required in libvpx/include/vpx/vpx_encoder.h libvpx/lib/libvpx.a \
  dav1d/include/dav1d/dav1d.h dav1d/lib/libdav1d.a; do
  [[ -f "${prefix}/${required}" ]] || die "${prefix}/${required} was not built"
done
# The pkg-config files name the build paths; the engine finds the libraries without them.
rm -rf "${prefix}/libvpx/lib/pkgconfig" "${prefix}/dav1d/lib/pkgconfig"
printf '%s\n' "${build_id}" >"${prefix}/BUILD-ID"
log "Video codecs for ${target} built into ${prefix}"
printf '%s\n' "${prefix}"
