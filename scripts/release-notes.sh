#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Prints the release notes of an Android release (Markdown) for the GitHub release:
#
#   scripts/release-notes.sh <version> <apk file name> <sbom file name>
#
# They name the Mumcord engine the app contains (engine.lock.toml: a release tag, or the commit
# while the engine is not released yet), the files of the release, and the "What's new" text from
# CHANGELOG.md: the section headed `## [<version>]` (anything may follow the bracket, such as a
# date), or else the `## [Unreleased]` section.
set -euo pipefail

die() {
  printf 'release-notes: %s\n' "$*" >&2
  exit 1
}

version="${1:?usage: release-notes.sh <version> <apk> <sbom>}"
apk="${2:?usage: release-notes.sh <version> <apk> <sbom>}"
sbom="${3:?usage: release-notes.sh <version> <apk> <sbom>}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

lock_value() {
  sed -n "s/^${1}[[:space:]]*=[[:space:]]*\"\([^\"]*\)\".*/\1/p" engine.lock.toml
}
engine_repository="$(lock_value repository)"
engine_tag="$(lock_value tag)"
engine_rev="$(lock_value rev)"
if [[ -n "${engine_tag}" ]]; then
  engine="Mumcord ${engine_tag} (${engine_repository}/releases/tag/${engine_tag})"
elif [[ -n "${engine_rev}" ]]; then
  engine="Mumcord at commit \`${engine_rev}\` (${engine_repository}/commit/${engine_rev}), not a Mumcord release yet"
else
  die "engine.lock.toml names neither a tag nor a rev"
fi

# The section of the version, else the unreleased one; without the link lines at the bottom.
section() {
  awk -v heading="$1" '
    index($0, heading) == 1 { found = 1; next }
    found && /^## \[/ { exit }
    found && /^\[[^]]+\]: / { next }
    found { print }
  ' CHANGELOG.md
}
changes="$(section "## [${version}]")"
[[ -n "${changes//[[:space:]]/}" ]] || changes="$(section "## [Unreleased]")"
[[ -n "${changes//[[:space:]]/}" ]] \
  || die "CHANGELOG.md has no section \"## [${version}]\" and no \"## [Unreleased]\" section with text"

cat <<NOTES
**Engine:** ${engine}

## Download

- \`${apk}\`: the app for Android 12 and later on phones and tablets with a 64-bit ARM processor. Open the file on the phone and allow your browser to install apps when Android asks. A newer version installs over the old one and keeps all your servers, identities and chats.
- \`SHA256SUMS\`: checksums of all files. Check a download with \`sha256sum --check --ignore-missing SHA256SUMS\`.
- \`${sbom}\`: software bill of materials (CycloneDX) of everything the app is built from.
- \`THIRD_PARTY_NOTICES.txt\`: licences of the third-party software in the app.

## What's new
${changes}
NOTES
