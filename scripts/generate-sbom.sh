#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Writes the SBOM (CycloneDX 1.6, JSON) of an Android release:
#
#   scripts/generate-sbom.sh <version> <android-sbom> <out-file>
#
# <android-sbom> is the SBOM of the Android libraries in the APK, written by
#   ./gradlew :app:cyclonedxDirectBom   (android/app/build/reports/cyclonedx-direct/bom.json)
# This script adds the Rust crates compiled into the engine library for phones (cargo-cyclonedx,
# target aarch64-linux-android, without build-only crates) and the C libraries built into it:
# libvpx and dav1d (scripts/build-codecs.sh) and libopus (vendored by the opusic-sys crate).
# scripts/sbom-merge.py puts them into one document. The release workflow runs it; it also works
# locally. Needs cargo-cyclonedx at the version in scripts/license-tools.env, jq and python3.
set -euo pipefail

die() {
  printf 'generate-sbom: %s\n' "$*" >&2
  exit 1
}

version="${1:?usage: generate-sbom.sh <version> <android-sbom> <out-file>}"
android_sbom="$(realpath "${2:?usage: generate-sbom.sh <version> <android-sbom> <out-file>}")"
out="$(realpath "${3:?usage: generate-sbom.sh <version> <android-sbom> <out-file>}")"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"
# shellcheck source=scripts/license-tools.env
source scripts/license-tools.env

cargo cyclonedx --version >/dev/null 2>&1 \
  || die "cargo-cyclonedx is missing; install: cargo install cargo-cyclonedx --version ${CARGO_CYCLONEDX_VERSION} --locked"

name="sbom-$$"
tmp="$(mktemp -d)"
cleanup() {
  rm -rf "${tmp}"
  # cargo-cyclonedx writes a file next to the Cargo.toml of every workspace member.
  find "${REPO_ROOT}" -maxdepth 3 -name "${name}.json" -delete
}
trap cleanup EXIT

cargo cyclonedx --manifest-path bridge/Cargo.toml --format json --target aarch64-linux-android \
  --no-build-deps --spec-version 1.5 --override-filename "${name}" --quiet
mv "bridge/${name}.json" "${tmp}/rust.json"

codec_version() {
  sed -n "s/^${1}_VERSION=\"\(.*\)\"/\1/p" scripts/build-codecs.sh
}
libvpx="$(codec_version LIBVPX)"
dav1d="$(codec_version DAV1D)"
[[ -n "${libvpx}" && -n "${dav1d}" ]] || die "codec versions not found in scripts/build-codecs.sh"

opus_manifest="$(cargo metadata --locked --format-version 1 --filter-platform aarch64-linux-android \
  | jq -r '.packages[] | select(.name == "opusic-sys") | .manifest_path')"
[[ -n "${opus_manifest}" ]] || die "opusic-sys not found in the dependency graph"
opus="$(sed -n 's/^PACKAGE_VERSION="\(.*\)"/\1/p' "$(dirname "${opus_manifest}")/opus/package_version")"
[[ -n "${opus}" ]] || die "libopus version not found in opusic-sys"

python3 -I scripts/sbom-merge.py --version "${version}" --android "${android_sbom}" \
  --rust "${tmp}/rust.json" --repo-root "${REPO_ROOT}" \
  --native "libvpx|${libvpx}|BSD-3-Clause|pkg:github/webmproject/libvpx@v${libvpx}|VP9 video codec, built by scripts/build-codecs.sh" \
  --native "dav1d|${dav1d}|BSD-2-Clause|pkg:generic/dav1d@${dav1d}?vcs_url=git%2Bhttps://code.videolan.org/videolan/dav1d.git|AV1 video decoder, built by scripts/build-codecs.sh" \
  --native "libopus|${opus}|BSD-3-Clause|pkg:generic/libopus@${opus}?vcs_url=git%2Bhttps://gitlab.xiph.org/xiph/opus.git|Opus audio codec, vendored by the opusic-sys crate" \
  --out "${out}"
printf 'generate-sbom: wrote %s (%s components)\n' "${out}" "$(jq '.components | length' "${out}")" >&2
