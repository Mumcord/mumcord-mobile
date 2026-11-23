#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Prints the Android version of a build as two lines for $GITHUB_OUTPUT:
#   name=<versionName>
#   code=<versionCode>
#
#   scripts/release-version.sh v0.1.0          a release tag: name 0.1.0, code 100
#   scripts/release-version.sh --dry-run <sha> a test build: name 0.0.0-dryrun+<short sha>, code 1
#
# A release tag is `v` plus X.Y.Z with no suffix. The version code, which Android compares to
# decide whether an APK is an update, is X * 10000 + Y * 100 + Z, so Y and Z stay below 100.
# 0.1.0 is 100, 0.2.3 is 203, 1.0.0 is 10000. A test build has code 1, below every release, so it
# can never replace an installed release (its signing key differs anyway).
set -euo pipefail

die() {
  printf 'release-version: %s\n' "$*" >&2
  exit 1
}

if [[ "${1:-}" == "--dry-run" ]]; then
  sha="${2:?usage: release-version.sh --dry-run <commit>}"
  printf 'name=0.0.0-dryrun+%s\ncode=1\n' "${sha:0:12}"
  exit 0
fi

tag="${1:?usage: release-version.sh <tag> | --dry-run <commit>}"
[[ "${tag}" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] \
  || die "${tag} is not a release tag: expected v<major>.<minor>.<patch> without a suffix, minor and patch below 100"
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
code=$((major * 10000 + minor * 100 + patch))
# Android's limit for version codes.
((code <= 2100000000)) || die "${tag} is too large for an Android version code"
((code >= 1)) || die "v0.0.0 cannot be released: Android needs a version code of at least 1"
printf 'name=%s.%s.%s\ncode=%s\n' "${major}" "${minor}" "${patch}" "${code}"
