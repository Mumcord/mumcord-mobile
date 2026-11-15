#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Writes the third-party notices of an Android release (plain text):
#
#   scripts/generate-notices.sh <version> <android-sbom> <out-file>
#
# <android-sbom> is the SBOM of the Android libraries in the APK, written by
#   ./gradlew :app:cyclonedxDirectBom   (android/app/build/reports/cyclonedx-direct/bom.json)
# The result is THIRD_PARTY_NOTICES of the repository (fonts, icons, libvpx, dav1d; written by
# hand), followed by the Rust crates of the engine library for phones (cargo-about, configured in
# about.toml) and the Android libraries (scripts/notices.py). It fails when a dependency has a
# licence that is not accepted in about.toml, so an incompatible dependency is noticed at once.
# The release workflow runs it; it also works locally. Needs cargo-about at the version in
# scripts/license-tools.env and python3 (3.11 or later).
set -euo pipefail

die() {
  printf 'generate-notices: %s\n' "$*" >&2
  exit 1
}

version="${1:?usage: generate-notices.sh <version> <android-sbom> <out-file>}"
android_sbom="$(realpath "${2:?usage: generate-notices.sh <version> <android-sbom> <out-file>}")"
out="$(realpath "${3:?usage: generate-notices.sh <version> <android-sbom> <out-file>}")"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"
# shellcheck source=scripts/license-tools.env
source scripts/license-tools.env

cargo about --version >/dev/null 2>&1 \
  || die "cargo-about is missing; install: cargo install cargo-about --version ${CARGO_ABOUT_VERSION} --locked --features cli"
installed="$(cargo about --version | awk '{print $2}')"
if [[ "${installed}" != "${CARGO_ABOUT_VERSION}" ]]; then
  printf 'generate-notices: warning: cargo-about %s is installed, CI uses %s; the output may differ\n' \
    "${installed}" "${CARGO_ABOUT_VERSION}" >&2
fi

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

cargo about generate --locked --manifest-path bridge/Cargo.toml --format json \
  --output-file "${tmp}/rust.json" \
  || die "cargo-about failed. If it reports 'failed to satisfy license requirements', a Rust dependency uses a licence that is not accepted in about.toml: replace the dependency, or add the licence only after checking that it is compatible with the AGPL-3.0"

python3 -I scripts/notices.py --version "${version}" --static THIRD_PARTY_NOTICES \
  --about-config about.toml --rust "${tmp}/rust.json" --android "${android_sbom}" --out "${out}"
printf 'generate-notices: wrote %s\n' "${out}" >&2
