#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Checks that a release APK is the product and nothing else:
#
#   scripts/check-release-apk.sh <apk> <versionName> <versionCode> <mapping.txt>
#
# <mapping.txt> is R8's map of the release build (android/app/build/outputs/mapping/release/):
# R8 renames classes and moves code into other classes, so only the map tells what code the APK
# contains under its original names.
#
# - application id app.mumcord.mobile with the expected version, not debuggable
# - native libraries for arm64-v8a only (x86_64 is for the emulator, specification section 10)
# - none of the debug-only code: no class from a file under android/**/src/debug (design
#   gallery, audio probe, the debug manifest's activities), also not inlined into other code. A
#   file that has a release counterpart under src/release (TestSettings) must be the release
#   one, which has no setters: only the debug build lets the tests change it. R8 may fold the
#   release one into the code that reads it; then it has no class of its own.
#
# Needs aapt2 and dexdump of the build tools (ANDROID_HOME from scripts/setup-android.sh).
set -euo pipefail

die() {
  printf 'check-release-apk: %s\n' "$*" >&2
  exit 1
}

usage="usage: check-release-apk.sh <apk> <versionName> <versionCode> <mapping.txt>"
apk="${1:?${usage}}"
version_name="${2:?${usage}}"
version_code="${3:?${usage}}"
mapping="${4:?${usage}}"
[[ -f "${apk}" ]] || die "${apk} does not exist"
[[ -f "${mapping}" ]] || die "${mapping} does not exist"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_TOOLS_VERSION="$(sed -n 's/^BUILD_TOOLS_VERSION="\(.*\)"/\1/p' "${REPO_ROOT}/scripts/setup-android.sh")"
tools="${ANDROID_HOME:?ANDROID_HOME is not set; run: eval \"\$(scripts/setup-android.sh --env)\"}/build-tools/${BUILD_TOOLS_VERSION}"
[[ -x "${tools}/dexdump" ]] || die "dexdump is missing in ${tools}; run scripts/setup-android.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

badging="$("${tools}/aapt2" dump badging "${apk}")"
grep -q "^package: name='app.mumcord.mobile' versionCode='${version_code}' versionName='${version_name}'" \
  <<<"${badging}" || die "wrong application id or version: $(head -n 1 <<<"${badging}")"
if grep -q "^application-debuggable" <<<"${badging}"; then
  die "the APK is debuggable"
fi
abis="$(sed -n "s/^native-code: //p" <<<"${badging}")"
[[ "${abis}" == "'arm64-v8a'" ]] || die "native code for ${abis:-nothing}, expected 'arm64-v8a' only"

unzip -q -o "${apk}" 'classes*.dex' -d "${tmp}"
for dex in "${tmp}"/classes*.dex; do
  "${tools}/dexdump" "${dex}"
done >"${tmp}/dex.txt"
grep -q "Class descriptor  : 'Lapp/mumcord/mobile/MainActivity;'" "${tmp}/dex.txt" \
  || die "the app's classes were not found in the APK; is it the right file?"
grep -q '^app\.mumcord\.mobile\.MainActivity -> ' "${mapping}" \
  || die "${mapping} does not belong to this APK"

# The original names of everything in the APK, one per line: the classes R8 kept (not those it
# removed) and their fields and methods as class.member. A member that names another class was
# inlined from there; its code is in the APK all the same.
awk '
  /^ *#/ { next }
  /^[^ ]/ { class = $1; removed = ($3 ~ /^R8\$\$REMOVED\$\$/); if (!removed) print class; next }
  removed { next }
  {
    member = $2
    sub(/\(.*/, "", member)
    if (member !~ /\./) member = class "." member
    print member
  }
' "${mapping}" | sort -u >"${tmp}/names.txt"

found=0
while IFS= read -r source; do
  relative="${source#*/src/debug/kotlin/}"
  class="${relative%.kt}"
  class="${class//\//.}"
  pattern="${class//./\\.}"
  release_source="${source/\/src\/debug\//\/src\/release\/}"
  if [[ -f "${release_source}" ]]; then
    # Only the release object, which has no setters.
    setters="$(grep -E "^${pattern}\.set[A-Z]" "${tmp}/names.txt" || true)"
    [[ -z "${setters}" ]] || die "${class} is the debug version (setters ${setters//$'\n'/ })"
  elif grep -q -E "^${pattern}(Kt)?([.\$]|$)" "${tmp}/names.txt"; then
    printf 'check-release-apk: debug-only code in the APK: %s\n' "${class}" >&2
    found=1
  fi
done < <(find "${REPO_ROOT}/android" -path '*/src/debug/kotlin/*.kt' -not -path '*/build/*' | sort)
((found == 0)) || die "the APK contains debug-only code"

manifest="$("${tools}/aapt2" dump xmltree --file AndroidManifest.xml "${apk}")"
if grep -q 'DesignGalleryActivity' <<<"${manifest}"; then
  die "the APK declares the debug design gallery"
fi

printf 'check-release-apk: %s is a release build of %s (%s), arm64-v8a, without debug code\n' \
  "$(basename "${apk}")" "${version_name}" "${version_code}" >&2
