#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Checks that a release APK is the product and nothing else:
#
#   scripts/check-release-apk.sh <apk> <versionName> <versionCode>
#
# - application id app.mumcord.mobile with the expected version, not debuggable
# - native libraries for arm64-v8a only (x86_64 is for the emulator, specification section 10)
# - none of the debug-only code: no class from a file under android/**/src/debug (design
#   gallery, audio probe, the debug manifest's activities). A file that has a release counterpart
#   under src/release (TestSettings) must be the release one, which has no setters: only the
#   debug build lets the tests change it.
#
# Needs aapt2 and dexdump of the build tools (ANDROID_HOME from scripts/setup-android.sh).
set -euo pipefail

die() {
  printf 'check-release-apk: %s\n' "$*" >&2
  exit 1
}

apk="${1:?usage: check-release-apk.sh <apk> <versionName> <versionCode>}"
version_name="${2:?usage: check-release-apk.sh <apk> <versionName> <versionCode>}"
version_code="${3:?usage: check-release-apk.sh <apk> <versionName> <versionCode>}"
[[ -f "${apk}" ]] || die "${apk} does not exist"

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

found=0
while IFS= read -r source; do
  relative="${source#*/src/debug/kotlin/}"
  class="${relative%.kt}"
  release_source="${source/\/src\/debug\//\/src\/release\/}"
  if [[ -f "${release_source}" ]]; then
    # Only the release object: its class must be there and must have no setters.
    setters="$(awk -v cls="'L${class};'" '
      /Class descriptor/ { inside = ($NF == cls) }
      inside && /name *: .set[A-Z]/ { print $NF }
    ' "${tmp}/dex.txt")"
    grep -q "Class descriptor  : 'L${class};'" "${tmp}/dex.txt" || die "${class} is missing"
    [[ -z "${setters}" ]] || die "${class} is the debug version (setters ${setters//$'\n'/ })"
  elif grep -q -E "Class descriptor  : 'L${class}(Kt)?[;\$]" "${tmp}/dex.txt"; then
    printf 'check-release-apk: debug-only class in the APK: %s\n' "${class}" >&2
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
