#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Signs the unsigned release APK with APK signature scheme v2 and v3 and checks the result:
#
#   scripts/sign-apk.sh <unsigned.apk> <signed.apk>
#
# The key comes from the environment, never from a file in the repository:
#   SIGNING_KEYSTORE           path of the keystore file (PKCS12)
#   SIGNING_KEYSTORE_PASSWORD  its password
#   SIGNING_KEY_ALIAS          the key's alias in it
#   SIGNING_KEY_PASSWORD       the key's password (for PKCS12 usually the keystore password)
# The passwords are handed to apksigner through the environment, so they never appear in a
# command line or the log. The release workflow sets them from the android-release environment;
# its test run uses a throwaway key it makes itself.
#
# Needs apksigner and zipalign of the build tools (ANDROID_HOME from scripts/setup-android.sh).
# v1 (JAR) signing stays off: it is only read by Android 6 and older, and the app needs Android 12.
set -euo pipefail

die() {
  printf 'sign-apk: %s\n' "$*" >&2
  exit 1
}

unsigned="${1:?usage: sign-apk.sh <unsigned.apk> <signed.apk>}"
signed="${2:?usage: sign-apk.sh <unsigned.apk> <signed.apk>}"
[[ -f "${unsigned}" ]] || die "${unsigned} does not exist"
for var in SIGNING_KEYSTORE SIGNING_KEYSTORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD; do
  [[ -n "${!var:-}" ]] || die "${var} is not set"
done
[[ -f "${SIGNING_KEYSTORE}" ]] || die "the keystore ${SIGNING_KEYSTORE} does not exist"

BUILD_TOOLS_VERSION="$(sed -n 's/^BUILD_TOOLS_VERSION="\(.*\)"/\1/p' \
  "$(dirname "${BASH_SOURCE[0]}")/setup-android.sh")"
tools="${ANDROID_HOME:?ANDROID_HOME is not set; run: eval \"\$(scripts/setup-android.sh --env)\"}/build-tools/${BUILD_TOOLS_VERSION}"
[[ -x "${tools}/apksigner" ]] || die "apksigner is missing in ${tools}; run scripts/setup-android.sh"
# apksigner runs on the java it finds first; prefer the project's JDK over whatever else is there.
if [[ -n "${JAVA_HOME:-}" ]]; then
  PATH="${JAVA_HOME}/bin:${PATH}"
fi

# The Android build aligns the unsigned APK already; this only checks it, because signing with
# v2 and later must happen after alignment.
"${tools}/zipalign" -c -P 16 4 "${unsigned}" || die "${unsigned} is not aligned"

"${tools}/apksigner" sign \
  --ks "${SIGNING_KEYSTORE}" --ks-type PKCS12 --ks-pass env:SIGNING_KEYSTORE_PASSWORD \
  --ks-key-alias "${SIGNING_KEY_ALIAS}" --key-pass env:SIGNING_KEY_PASSWORD \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "${signed}" "${unsigned}"

# For the app's minimum Android version apksigner checks only the newest scheme it finds (v3).
# Verifying as for Android 7 (the first with v2) makes it check the v2 signature as well.
report="$("${tools}/apksigner" verify --verbose --print-certs --min-sdk-version 24 "${signed}")"
printf '%s\n' "${report}" | grep -v -i 'warning' >&2
for scheme in v2 v3; do
  grep -q "^Verified using ${scheme} scheme (APK Signature Scheme ${scheme}): true" <<<"${report}" \
    || die "${signed} is not signed with scheme ${scheme}"
done
