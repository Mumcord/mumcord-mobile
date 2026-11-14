#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# End-to-end tests of the Android app: the real app with the real engine in the emulator against
# the local Mumble test server. One command does everything and cleans up after itself:
#
#   1. build the app and the test APK (android/e2e)
#   2. start the test server (scripts/testenv.sh)
#   3. start the emulator without a window, unless one is already running
#   4. install the app afresh
#   5. run the scenarios one after the other, each with fresh app data (no remembered server,
#      certificate or permission answer) and its own state of the local network and the
#      microphone permission (granted up front, or not, so that the app has to ask), and report
#      the result. Notifications are granted up front except where the question itself is
#      tested. The phone call scenario lets this script play the phone network through the
#      emulator's console. The voice scenarios talk to a second person (and the whisper scenario to
#      a third who must hear nothing): e2e/voice-peer, the engine itself running on this
#      machine. What they heard, and for how long, is the other half of each proof.
#   6. stop the server, and the emulator if this script started it
#
# Usage: e2e/run.sh [extra arguments for "am instrument"]
#   e2e/run.sh                              run everything
#   E2E_SCENARIO=LocalNetwork e2e/run.sh    run only the scenarios whose test name contains this
#
# Needs the toolchain of scripts/setup-android.sh and its environment
# (eval "$(scripts/setup-android.sh --env)"), KVM, cargo, and what scripts/testenv.sh needs.
#
# Environment:
#   MUMCORD_PORT_BASE     host port of the test server (default 64838)
#   E2E_SCENARIO          run only the scenarios whose test name contains this text
#   E2E_AVD               emulator device to start (default mumcord-test)
#   E2E_BOOT_TIMEOUT      seconds the emulator may take to boot (default 300)
#   E2E_TEST_TIMEOUT      seconds one scenario may take (default 300; a scenario takes well
#                         under a minute, so this only ends one that hangs)
#   E2E_RELEASE_BOUND_MS  how much longer than a hold the app may be heard (default 200)
#
# Logs of the run land in e2e/build/: the test output (instrument.txt), the device log of all
# scenarios (logcat.txt), the emulator log, what each other person reported
# (voice-peer-<scenario>.log, voice-bystander-<scenario>.log) and, for a scenario that hung,
# the screen at that moment (hung-screen.png, hung-screen.xml).
set -euo pipefail

export MUMCORD_PORT_BASE="${MUMCORD_PORT_BASE:-64838}"
AVD="${E2E_AVD:-mumcord-test}"
BOOT_TIMEOUT="${E2E_BOOT_TIMEOUT:-300}"
TEST_TIMEOUT="${E2E_TEST_TIMEOUT:-300}"

APP_PACKAGE="app.mumcord.mobile"
TEST_PACKAGE="app.mumcord.mobile.e2e"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
# The machine the emulator runs on, as seen from inside the emulator.
HOST_FROM_EMULATOR="10.0.2.2"
# First Android version (17) that has the local network permission.
LOCAL_NETWORK_PERMISSION_API=37
LOCAL_NETWORK_PERMISSION="android.permission.ACCESS_LOCAL_NETWORK"
MICROPHONE_PERMISSION="android.permission.RECORD_AUDIO"
# Exists since Android 13. Granted in every scenario except those of the kind "notify", which
# are about the question itself.
NOTIFICATION_PERMISSION_API=33
NOTIFICATION_PERMISSION="android.permission.POST_NOTIFICATIONS"
# How long the push-to-talk scenario keeps the screen locked; the voice activity scenario uses
# the test's own default of three minutes.
LOCK_PTT_SECONDS=60
# The scenarios: "<local network permission> <microphone permission> <second person> <test>".
# With "granted" a permission is given up front (most tests need the local network to reach the
# server on the host, and are not about the microphone); with "ask" the app starts without it
# and the test goes through the app's explanation and the system's dialog. The third word says
# who else is on the server and what they have to report (see check_peers): "alone" nobody;
# "peer" a second person who has to hear the app talk; "ptt" the same, and the app may be heard
# only for as long as hold-to-talk was held; "mute" the same, heard three separate times;
# "whisper" a second person who has to hear the whisper and a third who must hear nothing.
SCENARIOS=(
  "granted granted alone ${TEST_PACKAGE}.ConnectFlowTest"
  "ask granted alone ${TEST_PACKAGE}.LocalNetworkFlowTest#aLocalServerIsExplainedThenTheSystemAsksAndAllowingItConnects"
  "ask granted alone ${TEST_PACKAGE}.LocalNetworkFlowTest#refusingItIsNamedInTheErrorWhichLeadsToTheSystemSettingsAndBackToAConnection"
  "granted ask alone ${TEST_PACKAGE}.MicrophoneFlowTest#itIsExplainedWhenTalkingBecomesPossibleThenTheSystemAsksAndAllowingItLeavesNoNotice"
  "granted ask alone ${TEST_PACKAGE}.MicrophoneFlowTest#refusingItKeepsTheConnectionWithANoticeThatLeadsToTheQuestionAndThenToTheSystemSettings"
  "granted granted peer ${TEST_PACKAGE}.VoiceFlowTest"
  "granted granted ptt ${TEST_PACKAGE}.PushToTalkFlowTest"
  "granted granted mute ${TEST_PACKAGE}.MuteDeafenFlowTest"
  "granted granted whisper ${TEST_PACKAGE}.WhisperFlowTest"
  "granted granted notify ${TEST_PACKAGE}.NotificationFlowTest#itIsExplainedWithTheFirstSessionThenTheSystemAsksAndAllowingItShowsTheCall"
  "granted granted notify ${TEST_PACKAGE}.NotificationFlowTest#notNowKeepsTheSessionRunningInTheBackground"
  "granted granted alone ${TEST_PACKAGE}.CallNotificationFlowTest"
  "granted granted lock ${TEST_PACKAGE}.BackgroundVoiceFlowTest#withVoiceActivityBothDirectionsKeepWorkingInTheBackgroundAndWhileLocked"
  "granted granted lockptt ${TEST_PACKAGE}.BackgroundVoiceFlowTest#withPushToTalkThePhoneStaysSilentWhileLockedAndStillPlaysTheOthers"
  "granted granted call ${TEST_PACKAGE}.PhoneCallFlowTest"
)
# A stretch of voice a person heard counts from this length (ms); the engine's own short
# sounds, like the one for somebody entering the channel, are shorter.
VOICE_SPAN_MS=1000
# AC-5.6: after hold-to-talk is released, the app may be heard for at most this much longer.
# What is compared is the time the finger was down, measured in the emulator, with the stretch
# of voice the second person played, measured on this machine: 200 ms holds on a developer
# machine. Where the emulator shares few cores with everything else (the hosted CI runner), a
# stall of either side at the end of the stretch adds to it, so CI allows more (see the
# workflow); a button that does not stop the voice is heard for seconds longer either way.
RELEASE_BOUND_MS="${E2E_RELEASE_BOUND_MS:-200}"
# The name of the third person, who must not hear a whisper.
BYSTANDER_NAME="e2e-bystander"
# Seconds the second person may take to connect to the test server and enter its channel.
PEER_CONNECT_TIMEOUT=90
# The device keeps this much of its log, enough for all scenarios of a run.
LOGCAT_BUFFER=16M
# Port of the emulator this script starts; its adb name follows from it.
EMULATOR_PORT=5554

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${REPO_ROOT}/e2e/build"
APP_APK="${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="${REPO_ROOT}/android/e2e/build/outputs/apk/debug/e2e-debug.apk"
PEER_BINARY="${CARGO_TARGET_DIR:-${REPO_ROOT}/target}/debug/voice-peer"
# What the other people of the running scenario report; set by run_scenario.
PEER_LOG=""
BYSTANDER_LOG=""

step() {
  printf '\ne2e: %s\n' "$*"
}

die() {
  printf 'e2e: FAILED: %s\n' "$*" >&2
  exit 1
}

[[ -d "${ANDROID_HOME:-}" && -d "${JAVA_HOME:-}" ]] \
  || die 'ANDROID_HOME or JAVA_HOME is not set; run: eval "$(scripts/setup-android.sh --env)"'
ADB="${ANDROID_HOME}/platform-tools/adb"
EMULATOR="${ANDROID_HOME}/emulator/emulator"
[[ -x "${ADB}" && -x "${EMULATOR}" ]] || die "adb or the emulator is missing; run scripts/setup-android.sh"

serial=""
emulator_pid=""
peer_pids=()
network_pid=""
server_started=0

adb_device() {
  "${ADB}" -s "${serial}" "$@"
}

stop_peers() {
  local pid
  for pid in "${peer_pids[@]}"; do
    kill "${pid}" 2>/dev/null || true
    # Its exit status is that of being stopped, which is not a failure.
    wait "${pid}" 2>/dev/null || true
  done
  peer_pids=()
}

# Starts another person (e2e/voice-peer with the given arguments), who writes to the log file
# given first, and waits until they are on the server.
start_peer() {
  local log="$1" pid waited=0
  shift
  : >"${log}"
  "${PEER_BINARY}" "$@" >"${log}" 2>&1 &
  pid=$!
  peer_pids+=("${pid}")
  until grep -q '^CONNECTED' "${log}"; do
    kill -0 "${pid}" 2>/dev/null || die "a second person ended early, see ${log#"${REPO_ROOT}/"}"
    ((waited < PEER_CONNECT_TIMEOUT)) \
      || die "a second person did not connect within ${PEER_CONNECT_TIMEOUT} s, see ${log#"${REPO_ROOT}/"}"
    sleep 1
    waited=$((waited + 1))
  done
}

# The lengths (ms) of the stretches of voice a person heard that count as voice, one per line.
# Plays the phone network for a scenario: the test asks in the device log ("gsm call <number>",
# "gsm cancel <number>") and the emulator's console, which only the host reaches, does it.
start_phone_network() {
  (
    "${ADB}" -s "${serial}" logcat -v raw -T 1 -s MumcordE2E:I | while read -r word command number; do
      if [[ "${word}" == "gsm" && "${command}" =~ ^(call|cancel)$ && "${number}" =~ ^[0-9]+$ ]]; then
        "${ADB}" -s "${serial}" emu gsm "${command}" "${number}" >/dev/null 2>&1 || true
      fi
    done
  ) &
  network_pid=$!
}

stop_phone_network() {
  [[ -n "${network_pid}" ]] || return 0
  pkill -P "${network_pid}" 2>/dev/null || true
  kill "${network_pid}" 2>/dev/null || true
  wait "${network_pid}" 2>/dev/null || true
  network_pid=""
}

voice_spans() {
  sed -n 's/^SPAN ms=//p' "$1" | awk -v least="${VOICE_SPAN_MS}" '$1 >= least'
}

# The other half of the proof: what the other people received from the app.
check_peers() {
  local kind="$1" report="$2" spans count held longest
  local peer_log="${PEER_LOG#"${REPO_ROOT}/"}" bystander_log="${BYSTANDER_LOG#"${REPO_ROOT}/"}"
  spans="$(voice_spans "${PEER_LOG}")"
  count="$(grep -c . <<<"${spans}" || true)"
  case "${kind}" in
    peer)
      grep '^HEARD' "${PEER_LOG}" || die "the second person did not hear the app, see ${peer_log}"
      ;;
    lock)
      held="$(sed -n 's/^lock: locked_ms=\([0-9]*\).*/\1/p' "${report}" | tr -d '\r')"
      [[ -n "${held}" ]] || die "the test did not report how long the app was in the background and locked"
      longest="$(sort -n <<<"${spans}" | tail -n 1)"
      printf 'e2e: background and lock: %s ms away from the screen, heard without a gap for %s ms\n' \
        "${held}" "${longest:-0}"
      ((${longest:-0} >= held)) \
        || die "the second person did not hear the app for the whole time it was locked, see ${peer_log}"
      ;;
    call)
      printf 'e2e: phone call: the second person heard the app %s times (ms: %s)\n' \
        "${count}" "$(tr '\n' ' ' <<<"${spans}")"
      ((count == 2)) || die "the second person should have heard the app before and after the phone call, see ${peer_log}"
      ;;
    lockptt)
      # How exactly the release is timed is the push-to-talk scenario's business. Here the
      # point is that the one stretch heard is the press, and nothing of the time locked.
      held="$(sed -n 's/^ptt: held_ms=//p' "${report}" | tr -d '\r')"
      [[ -n "${held}" ]] || die "the test did not report how long hold-to-talk was held"
      printf 'e2e: push-to-talk while locked: held %s ms, heard %s times (ms: %s), locked for %s s\n' \
        "${held}" "${count}" "$(tr '\n' ' ' <<<"${spans}")" "${LOCK_PTT_SECONDS}"
      ((count == 1)) || die "the second person heard the app ${count} times instead of once, see ${peer_log}"
      ((spans < held + LOCK_PTT_SECONDS * 1000 / 2)) || die "the app was heard while the screen was locked"
      ;;
    ptt)
      held="$(sed -n 's/^ptt: held_ms=//p' "${report}" | tr -d '\r')"
      [[ -n "${held}" ]] || die "the test did not report how long hold-to-talk was held"
      ((count == 1)) || die "the second person heard the app ${count} times instead of once, see ${peer_log}"
      printf 'e2e: push-to-talk: held %s ms, heard for %s ms (allowed: at most %s ms longer)\n' \
        "${held}" "${spans}" "${RELEASE_BOUND_MS}"
      ((spans <= held + RELEASE_BOUND_MS)) || die "the app was heard longer than hold-to-talk was held"
      # Heard for most of the hold, so the bound above is not met by sending too little.
      ((spans >= held / 2)) || die "the app was heard for less than half of the hold"
      ;;
    mute)
      printf 'e2e: mute and deafen: the second person heard the app %s times (ms: %s)\n' \
        "${count}" "$(tr '\n' ' ' <<<"${spans}")"
      ((count == 3)) || die "the second person should have heard the app three separate times, see ${peer_log}"
      ;;
    whisper)
      held="$(sed -n 's/^whisper: held_ms=//p' "${report}" | tr -d '\r')"
      [[ -n "${held}" ]] || die "the test did not report how long the whisper was held"
      longest="$(sed -n 's/^SPAN ms=//p' "${BYSTANDER_LOG}" | sort -n | tail -n 1)"
      printf 'e2e: whisper: held %s ms, the person whispered to heard %s ms, the third person at most %s ms\n' \
        "${held}" "${spans:-0}" "${longest:-0}"
      ((count == 1)) || die "the person whispered to heard the app ${count} times instead of once, see ${peer_log}"
      ((spans >= held / 2 && spans <= held + RELEASE_BOUND_MS)) \
        || die "the whisper was not heard for as long as its button was held"
      [[ -z "$(voice_spans "${BYSTANDER_LOG}")" ]] \
        || die "the third person heard the whisper, see ${bystander_log}"
      ;;
  esac
}

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  step "cleaning up"
  stop_peers
  if ((status != 0)); then
    # What the other people reported belongs next to the failure, not only into a file.
    local log
    for log in "${PEER_LOG}" "${BYSTANDER_LOG}"; do
      [[ -s "${log}" ]] || continue
      printf 'e2e: the end of %s:\n' "${log#"${REPO_ROOT}/"}"
      tail -n 40 "${log}"
    done
  fi
  stop_phone_network
  if [[ -n "${serial}" ]]; then
    timeout 20 "${ADB}" -s "${serial}" logcat -d >"${OUT_DIR}/logcat.txt" 2>/dev/null
  fi
  if [[ -n "${emulator_pid}" ]]; then
    timeout 10 "${ADB}" -s "${serial}" emu kill >/dev/null 2>&1
    # Give it time to shut down by itself; a hanging emulator would keep its memory forever.
    for _ in $(seq 30); do
      kill -0 "${emulator_pid}" 2>/dev/null || break
      sleep 1
    done
    kill -9 "${emulator_pid}" 2>/dev/null
  fi
  if ((server_started)); then
    "${REPO_ROOT}/scripts/testenv.sh" down >"${OUT_DIR}/testenv-down.log" 2>&1 \
      || printf 'e2e: stopping the test server failed, see e2e/build/testenv-down.log\n' >&2
  fi
  exit "${status}"
}

# The emulator that is already running, if any; empty otherwise.
running_emulator() {
  "${ADB}" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }'
}

start_emulator() {
  [[ -r /dev/kvm && -w /dev/kvm ]] || die "/dev/kvm is not usable by this user; the emulator needs KVM"
  "${EMULATOR}" -list-avds | grep -qx "${AVD}" || die "emulator device ${AVD} does not exist; run scripts/setup-android.sh"
  serial="emulator-${EMULATOR_PORT}"
  # -no-snapshot: always a cold boot, so a state saved by an earlier session cannot leak in.
  "${EMULATOR}" -avd "${AVD}" -port "${EMULATOR_PORT}" -no-window -no-audio -no-boot-anim \
    -no-snapshot >"${OUT_DIR}/emulator.log" 2>&1 &
  emulator_pid=$!

  local waited=0
  until [[ "$(timeout 5 "${ADB}" -s "${serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    kill -0 "${emulator_pid}" 2>/dev/null \
      || die "the emulator exited while booting, see e2e/build/emulator.log"
    ((waited < BOOT_TIMEOUT)) || die "the emulator did not finish booting within ${BOOT_TIMEOUT} s"
    sleep 2
    waited=$((waited + 2))
  done
  # No animations: a system dialog that slides in takes a tap only once it has arrived.
  local scale
  for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb_device shell settings put global "${scale}" 0 || die "switching off the animations failed"
  done
}

mkdir -p "${OUT_DIR}"
rm -f "${OUT_DIR}/instrument.txt" "${OUT_DIR}/logcat.txt" "${OUT_DIR}/emulator.log" \
  "${OUT_DIR}"/voice-*.log "${OUT_DIR}"/hung-screen.*
trap cleanup EXIT

step "building the app and the tests"
(cd "${REPO_ROOT}/android" && ./gradlew --console=plain --quiet :app:assembleDebug :e2e:assembleDebug)
(cd "${REPO_ROOT}" && cargo build --locked --quiet --package mumcord-voice-peer)
[[ -x "${PEER_BINARY}" ]] || die "the second person's program was not built at ${PEER_BINARY}"

step "starting the test server on port ${MUMCORD_PORT_BASE}"
server_started=1
"${REPO_ROOT}/scripts/testenv.sh" up

serial="$(running_emulator)"
if [[ -n "${serial}" ]]; then
  step "using the running emulator ${serial} (it is left running)"
else
  step "starting the emulator ${AVD}"
  start_emulator
fi

step "installing the app afresh"
# Removing the app first also removes its data: no remembered server, certificate or identity.
adb_device uninstall "${TEST_PACKAGE}" >/dev/null 2>&1 || true
adb_device uninstall "${APP_PACKAGE}" >/dev/null 2>&1 || true
timeout 120 "${ADB}" -s "${serial}" install "${APP_APK}" >/dev/null || die "installing the app failed"
timeout 120 "${ADB}" -s "${serial}" install "${TEST_APK}" >/dev/null || die "installing the tests failed"
adb_device logcat -G "${LOGCAT_BUFFER}" || true
adb_device logcat -c || true
device_api="$(adb_device shell getprop ro.build.version.sdk | tr -d '\r')"

# Runs one scenario: fresh app data, the permissions as the scenario wants them, then its test.
run_scenario() {
  local permission="$1" microphone="$2" peer="$3" test="$4" report="${OUT_DIR}/instrument-scenario.txt" status=0
  PEER_LOG="${OUT_DIR}/voice-peer-${peer}.log"
  BYSTANDER_LOG="${OUT_DIR}/voice-bystander-${peer}.log"
  step "scenario: ${test##*.} (local network: ${permission}, microphone: ${microphone}, second person: ${peer})"
  # Clearing the data also takes back every permission and forgets earlier answers to prompts.
  adb_device shell pm clear "${APP_PACKAGE}" >/dev/null || die "clearing the app data failed"
  # Since Android 17 the test server on the host counts as local network, which needs this
  # permission. Older versions do not know it; there the address is reachable without.
  if [[ "${permission}" == "granted" ]] && ((device_api >= LOCAL_NETWORK_PERMISSION_API)); then
    adb_device shell pm grant "${APP_PACKAGE}" "${LOCAL_NETWORK_PERMISSION}" \
      || die "granting local network access failed"
  fi
  if [[ "${microphone}" == "granted" ]]; then
    adb_device shell pm grant "${APP_PACKAGE}" "${MICROPHONE_PERMISSION}" \
      || die "granting the microphone failed"
  fi
  if [[ "${peer}" != "notify" ]] && ((device_api >= NOTIFICATION_PERMISSION_API)); then
    adb_device shell pm grant "${APP_PACKAGE}" "${NOTIFICATION_PERMISSION}" \
      || die "granting notifications failed"
  fi
  local extra=()
  case "${peer}" in
    alone | notify) ;;
    lockptt)
      extra=(-e lockSeconds "${LOCK_PTT_SECONDS}")
      start_peer "${PEER_LOG}"
      ;;
    call)
      start_phone_network
      start_peer "${PEER_LOG}"
      ;;
    whisper)
      start_peer "${PEER_LOG}" --listen-only
      start_peer "${BYSTANDER_LOG}" --name "${BYSTANDER_NAME}" --listen-only
      ;;
    *) start_peer "${PEER_LOG}" ;;
  esac
  timeout "${TEST_TIMEOUT}" "${ADB}" -s "${serial}" shell am instrument -w \
    -e serverHost "${HOST_FROM_EMULATOR}" -e serverPort "${MUMCORD_PORT_BASE}" -e class "${test}" \
    "${extra[@]}" "${@:5}" "${TEST_PACKAGE}/${RUNNER}" | tee "${report}" || status=$?
  cat "${report}" >>"${OUT_DIR}/instrument.txt"
  if ((status == 124)); then
    # The test still runs, so the screen shows where it hangs.
    timeout 20 "${ADB}" -s "${serial}" exec-out screencap -p >"${OUT_DIR}/hung-screen.png" 2>/dev/null || true
    timeout 20 "${ADB}" -s "${serial}" exec-out uiautomator dump /dev/tty >"${OUT_DIR}/hung-screen.xml" 2>/dev/null || true
    die "the scenario did not finish within ${TEST_TIMEOUT} s, see e2e/build/hung-screen.png and logcat.txt"
  fi
  # "am instrument" exits with 0 even when tests fail, so the verdict comes from its report.
  # A run that passed ends with "OK (N tests)"; a run in which nothing was executed does not count.
  if ((status != 0)) || ! grep -Eq '^OK \([1-9][0-9]* tests?\)' "${report}"; then
    die "tests failed, see above and e2e/build/logcat.txt"
  fi
  stop_phone_network
  if [[ "${peer}" != "alone" && "${peer}" != "notify" ]]; then
    check_peers "${peer}" "${report}"
    stop_peers
  fi
}

scenarios_run=0
for scenario in "${SCENARIOS[@]}"; do
  read -r permission microphone peer test <<<"${scenario}"
  [[ "${test}" == *"${E2E_SCENARIO:-}"* ]] || continue
  run_scenario "${permission}" "${microphone}" "${peer}" "${test}" "$@"
  scenarios_run=$((scenarios_run + 1))
done
((scenarios_run > 0)) || die "no scenario matches E2E_SCENARIO=${E2E_SCENARIO:-}"
step "PASSED"
