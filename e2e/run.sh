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
#      machine. What they heard, and for how long, is the other half of each proof. The chat
#      scenario has two more people: e2e/chat-peer (the bridge, writing as the desktop app
#      does, and reporting what the phone wrote) and e2e/raw-chat.py (a foreign client that
#      sends raw script and markup). It is followed by a scenario that starts the app again
#      with the same app data, after its process was killed, and looks for the history. In
#      the private message scenario e2e/chat-peer sends the phone a private message whenever
#      the test asks for one, which is what the notifications are about. The identity
#      scenario is watched by e2e/identity-watch.py, a foreign client that reports whether the
#      server saw the same certificate on every connect. For the transfer scenario
#      e2e/transfer-maker makes a transfer with the engine as the desktop app does (a new
#      identity and a new code in every run); its QR code texts and its code reach the test
#      as an argument and are written to no file and no log. In the stream scenario
#      e2e/stream-peer (the engine on this machine, built with libvpx) shares its moving test
#      pattern; it writes down how many people watch whenever the test asks, which shows that
#      nothing was fetched before the tap. The tablet scenario watches the same share with the
#      emulator's screen made the size of a tablet (`wm size`, `wm density`); the test puts the
#      phone's screen back, and so does this script whenever a scenario ends, also after a
#      crash. The public server scenario
#      serves its own list inside the emulator (android/e2e LocalPublicList) and never
#      contacts the real list service. In the moderation scenarios the phone connects as the
#      test server's SuperUser (its password reaches the test as an argument, never a file or
#      log) and moderates e2e/moderation-target.py, a foreign client that reports what the
#      server did to it; the ban scenario is the last one, because a ban covers the address
#      all people of the test server share, and it ends with the person let in again. In the
#      certificate scenario this script restarts the test server with the test environment's
#      second certificate when the test asks for it, and puts the first one back afterwards.
#      A scenario that fails is run once more (E2E_RETRIES); the retry is reported in
#      e2e/build/summary.md (and the job summary on GitHub), never hidden.
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
#   E2E_TEST_TIMEOUT      seconds one scenario may take (default 600; the longest keeps the
#                         screen locked for three minutes, so this only ends one that hangs)
#   E2E_DROP_FIRST_ANSWER set to 1 for debugging the phone call scenario: the network hangs up
#                         just before the first answer, so the test has to repeat the call
#                         (the path for an emulator that loses an answered call by itself)
#   E2E_RELEASE_BOUND_MS  how much longer than a hold the app may be heard (default 200)
#   E2E_RETRIES           how often a failed scenario is run again (default 1, 0 for none)
#   E2E_EVIDENCE_DIR      a folder that keeps what explains a speaker gap after the run: the
#                         device log of every scenario in its own file, this machine's load
#                         every second, and in the locked push-to-talk scenario the report of
#                         a third person who only listens to the second one on this machine
#
# Logs of the run land in e2e/build/: the test output (instrument.txt), the device log of all
# scenarios (logcat.txt), the emulator log, what each other person reported
# (voice-peer-<scenario>.log, voice-bystander-<scenario>.log), for a scenario that hung, the
# screen at that moment (hung-screen.png, hung-screen.xml), the outcome of every scenario with
# its duration and retries (summary.md), and what a failed attempt left before its retry
# (retried/).
set -euo pipefail

export MUMCORD_PORT_BASE="${MUMCORD_PORT_BASE:-64838}"
AVD="${E2E_AVD:-mumcord-test}"
BOOT_TIMEOUT="${E2E_BOOT_TIMEOUT:-300}"
TEST_TIMEOUT="${E2E_TEST_TIMEOUT:-600}"

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
# "headset" the same as "mute", the gaps being a headset's button; "network" a second person
# who has to hear the app before and after it changed from Wi-Fi to mobile data; "gap" nobody,
# the phone loses every network for a while; "idle" nobody, and the picture of the warning
# notification is fetched;
# "whisper" a second person who has to hear the whisper and a third who must hear nothing;
# "person" a second person who talks and a third who has to hear them without a break while
# the phone has them muted;
# "certificate" nobody, and the test server is restarted with its second certificate when the
# test asks for it;
# "chat" a second person who writes and has to receive what the app wrote, and a third who
# sends raw markup; "chat-restart" nobody, and the app keeps the data of the scenario before it
# (its process is killed instead), so it must follow a "chat" scenario; "pm" a second person
# who sends the phone a private message whenever the test asks for one; "identity" a second
# person who has to see the same certificate each time the phone connects; "transfer" nobody,
# and the test gets a transfer made for this run (the camera permission is asked for in it);
# "activity" a second person who joins the server, moves and leaves whenever the test asks;
# "profile" a second person (e2e/profile-watch.py) who has to see the comment and the avatar the
# phone sets, the avatar without EXIF data;
# "stream" a second person who shares a screen (e2e/stream-peer) and writes down its viewers
# whenever the test asks; "camera" a second person (e2e/stream-peer --viewer) who watches the
# phone's camera, checks its pictures for the phone's test pattern and writes down what it saw
# whenever the test asks;
# "moderation" a second person (e2e/moderation-target.py) whom the phone, connected as the test
# server's SuperUser, moves and kicks, and who has to report both; "ban" the
# same person, who has to be removed by a short ban, refused while it lasts and let in again
# after it. A ban covers the address all people of the test server share (the host), so the
# "ban" scenario stays the last one.
SCENARIOS=(
  "granted granted alone ${TEST_PACKAGE}.ConnectFlowTest"
  "granted granted certificate ${TEST_PACKAGE}.CertificateFlowTest"
  "granted granted alone ${TEST_PACKAGE}.LinkFlowTest"
  "granted granted alone ${TEST_PACKAGE}.PublicServersFlowTest"
  "ask granted alone ${TEST_PACKAGE}.LocalNetworkFlowTest#aLocalServerIsExplainedThenTheSystemAsksAndAllowingItConnects"
  "ask granted alone ${TEST_PACKAGE}.LocalNetworkFlowTest#refusingItIsNamedInTheErrorWhichLeadsToTheSystemSettingsAndBackToAConnection"
  "granted ask alone ${TEST_PACKAGE}.MicrophoneFlowTest#itIsExplainedWhenTalkingBecomesPossibleThenTheSystemAsksAndAllowingItLeavesNoNotice"
  "granted ask alone ${TEST_PACKAGE}.MicrophoneFlowTest#refusingItKeepsTheConnectionWithANoticeThatLeadsToTheQuestionAndThenToTheSystemSettings"
  "granted granted peer ${TEST_PACKAGE}.VoiceFlowTest"
  "granted granted person ${TEST_PACKAGE}.PersonSheetFlowTest"
  "granted granted ptt ${TEST_PACKAGE}.PushToTalkFlowTest"
  "granted granted mute ${TEST_PACKAGE}.MuteDeafenFlowTest"
  "granted granted whisper ${TEST_PACKAGE}.WhisperFlowTest"
  "granted granted alone ${TEST_PACKAGE}.AudioOutputFlowTest#theVoiceViewShowsTheOutputAndTheSheetListsWhatThePhoneOffers"
  "granted granted headset ${TEST_PACKAGE}.AudioOutputFlowTest#aHeadsetsButtonTogglesTheMicrophoneInTheBackgroundAndWithTheScreenOff"
  "granted granted network ${TEST_PACKAGE}.NetworkFlowTest#switchingFromWifiToMobileDataReconnectsIntoTheSameChannelWithTheSameMuteState"
  "granted granted gap ${TEST_PACKAGE}.NetworkFlowTest#afterALongerGapTheSessionIsBackAsSoonAsANetworkIs"
  "granted granted idle ${TEST_PACKAGE}.IdleDisconnectFlowTest"
  "granted granted notify ${TEST_PACKAGE}.NotificationFlowTest#itIsExplainedWithTheFirstSessionThenTheSystemAsksAndAllowingItShowsTheCall"
  "granted granted notify ${TEST_PACKAGE}.NotificationFlowTest#notNowKeepsTheSessionRunningInTheBackground"
  "granted granted alone ${TEST_PACKAGE}.CallNotificationFlowTest"
  "granted granted lock ${TEST_PACKAGE}.BackgroundVoiceFlowTest#withVoiceActivityBothDirectionsKeepWorkingInTheBackgroundAndWhileLocked"
  "granted granted lockptt ${TEST_PACKAGE}.BackgroundVoiceFlowTest#withPushToTalkThePhoneStaysSilentWhileLockedAndStillPlaysTheOthers"
  "granted granted call ${TEST_PACKAGE}.PhoneCallFlowTest"
  "granted granted chat ${TEST_PACKAGE}.ChatFlowTest#messagesOfOthersAppearAndThePhonesMessagesReachThem"
  "granted granted chat-restart ${TEST_PACKAGE}.ChatFlowTest#theHistoryIsStillThereAfterTheAppWasKilledAndStartedAgain"
  "granted granted pm ${TEST_PACKAGE}.MessagesFlowTest"
  "granted granted identity ${TEST_PACKAGE}.IdentityFlowTest"
  "granted granted transfer ${TEST_PACKAGE}.TransferFlowTest"
  "granted granted stream ${TEST_PACKAGE}.StreamFlowTest"
  "granted granted tablet ${TEST_PACKAGE}.TabletFlowTest"
  "granted granted camera ${TEST_PACKAGE}.CameraFlowTest"
  "granted granted activity ${TEST_PACKAGE}.ActivityFlowTest"
  "granted granted profile ${TEST_PACKAGE}.ProfileThemeFlowTest"
  "granted granted moderation ${TEST_PACKAGE}.ModerationFlowTest#moveAndKickReachTheOtherPerson"
  "granted granted ban ${TEST_PACKAGE}.ModerationFlowTest#aShortBanRemovesThePersonUntilItEnds"
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
# Where the "idle" scenario leaves its picture of the warning notification on the device.
IDLE_PICTURE="/data/local/tmp/mumcord-idle-notification.png"
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
EVIDENCE_DIR="${E2E_EVIDENCE_DIR:-}"
RETRIES="${E2E_RETRIES:-1}"
SUMMARY_FILE="${OUT_DIR}/summary.md"
# The outcome of each scenario, one table row each, for the summary.
summary_rows=()
# How many scenarios passed only on their retry.
retried=0
# Created on the device once the test server presents its second certificate.
CERT_SWITCHED_MARK="/data/local/tmp/mumcord-cert-switched"
# The third person of the evidence: hears the second person without the emulator in between.
MONITOR_NAME="e2e-monitor"
# What the other people of the running scenario report; set by run_scenario.
PEER_LOG=""
BYSTANDER_LOG=""
CHAT_PEER_BINARY="${CARGO_TARGET_DIR:-${REPO_ROOT}/target}/debug/chat-peer"
CHAT_PEER_LOG="${OUT_DIR}/chat-peer.log"
RAW_CHAT_LOG="${OUT_DIR}/raw-chat.log"
IDENTITY_WATCH_LOG="${OUT_DIR}/identity-watch.log"
PROFILE_WATCH_LOG="${OUT_DIR}/profile-watch.log"
MODERATION_LOG="${OUT_DIR}/moderation-target.log"
# The SuperUser password of the test server, which scripts/testenv.sh wrote here.
TESTENV_FILE="${REPO_ROOT}/.engine/testenv/.env"
TRANSFER_MAKER_BINARY="${CARGO_TARGET_DIR:-${REPO_ROOT}/target}/debug/transfer-maker"
STREAM_PEER_BINARY="${CARGO_TARGET_DIR:-${REPO_ROOT}/target}/debug/stream-peer"
STREAM_PEER_LOG="${OUT_DIR}/stream-peer.log"
CAMERA_VIEWER_LOG="${OUT_DIR}/camera-viewer.log"
# The numbers of the private messages the second person of the "pm" scenario is to send, and
# the steps of the visitor of the "activity" scenario.
PM_FIFO="${OUT_DIR}/pm-requests"
# The user name the app connects with in the tests (AppDriver.USER_NAME).
PHONE_NAME="e2e-phone"
# The comment the profile scenario sets on the phone (ProfileThemeFlowTest.COMMENT).
PROFILE_COMMENT="Hello from the phone"

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
messenger_pid=""
evidence_pid=""
evidence_file=""
switcher_pid=""
load_pid=""
server_started=0
# The animation scales the emulator had before the run switched them off, as "name=value".
animation_scales=()

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
  local log="$1"
  shift
  start_person "${log}" "${PEER_BINARY}" "$@"
}

# Starts another person's program and waits until it reports that it is on the server.
start_person() {
  local log="$1" pid waited=0
  shift
  : >"${log}"
  # PERSON_INPUT: what the person reads as its input; nothing unless the scenario sets it.
  "$@" <"${PERSON_INPUT:-/dev/null}" >"${log}" 2>&1 &
  pid=$!
  peer_pids+=("${pid}")
  # The visitor of the "activity" scenario only connects when the test asks; it says READY.
  until grep -Eq '^(CONNECTED|READY)' "${log}"; do
    kill -0 "${pid}" 2>/dev/null || die "a second person ended early, see ${log#"${REPO_ROOT}/"}"
    ((waited < PEER_CONNECT_TIMEOUT)) \
      || die "a second person did not connect within ${PEER_CONNECT_TIMEOUT} s, see ${log#"${REPO_ROOT}/"}"
    sleep 1
    waited=$((waited + 1))
  done
}

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

# Passes on what the test of the "pm" scenario asks for in the device log ("pm send <n>"): the
# second person then sends the phone the private message with that number. The same for the
# "activity" scenario ("visitor join|move|leave"). The pipe is held open here (descriptor 9) so
# that the second person can keep reading from it.
start_messenger() {
  rm -f "${PM_FIFO}"
  mkfifo "${PM_FIFO}"
  exec 9<>"${PM_FIFO}"
  (
    "${ADB}" -s "${serial}" logcat -v raw -T 1 -s MumcordE2E:I | while read -r word command number; do
      if [[ "${word}" == "pm" && "${command}" == "send" && "${number}" =~ ^[0-9]+$ ]]; then
        printf '%s\n' "${number}" >&9
      elif [[ "${word}" == "visitor" && "${command}" =~ ^(join|move|leave)$ ]]; then
        printf '%s\n' "${command}" >&9
      elif [[ "${word}" == "stream" && "${command}" == "mark" && "${number}" =~ ^[a-z-]+$ ]]; then
        printf 'mark %s\n' "${number}" >&9
      fi
    done
  ) &
  messenger_pid=$!
}

stop_messenger() {
  [[ -n "${messenger_pid}" ]] || return 0
  pkill -P "${messenger_pid}" 2>/dev/null || true
  kill "${messenger_pid}" 2>/dev/null || true
  wait "${messenger_pid}" 2>/dev/null || true
  messenger_pid=""
  exec 9>&-
  rm -f "${PM_FIFO}"
}

# Restarts the test server with the certificate set the test asks for in the device log
# ("testenv cert B"), then tells the test by a file on the device.
start_cert_switcher() {
  (
    "${ADB}" -s "${serial}" logcat -v raw -T 1 -s MumcordE2E:I | while read -r word command set; do
      if [[ "${word}" == "testenv" && "${command}" == "cert" && "${set}" =~ ^[AB]$ ]] \
        && "${REPO_ROOT}/scripts/testenv.sh" up --cert "${set}" >>"${OUT_DIR}/testenv-cert.log" 2>&1 </dev/null; then
        "${ADB}" -s "${serial}" shell touch "${CERT_SWITCHED_MARK}" </dev/null || true
      fi
    done
  ) &
  switcher_pid=$!
}

# Stops listening and gives the test server its first certificate back, whatever the test did.
stop_cert_switcher() {
  [[ -n "${switcher_pid}" ]] || return 0
  pkill -P "${switcher_pid}" 2>/dev/null || true
  kill "${switcher_pid}" 2>/dev/null || true
  wait "${switcher_pid}" 2>/dev/null || true
  switcher_pid=""
  "${REPO_ROOT}/scripts/testenv.sh" up --cert A >>"${OUT_DIR}/testenv-cert.log" 2>&1 \
    || printf 'e2e: giving the test server its first certificate back failed, see e2e/build/testenv-cert.log\n' >&2
  timeout 20 "${ADB}" -s "${serial}" shell rm -f "${CERT_SWITCHED_MARK}" >/dev/null 2>&1 || true
}

# adbd logs every shell command it runs; the transfer argument (the code with its QR code
# texts) and the SuperUser password must not reach a file that is kept.
redact_device_log() {
  sed -E 's/-e transfer [^ ]+/-e transfer (removed)/g; s/-e superuser [^ ]+/-e superuser (removed)/g'
}

# Keeps the device log of one scenario in its own file of the evidence folder, with the
# difference between the device's clock and this machine's in its first line.
start_evidence() {
  [[ -n "${EVIDENCE_DIR}" ]] || return 0
  local file="${EVIDENCE_DIR}/logcat-$1.txt"
  evidence_file="${file}"
  printf 'clocks: host_unix_ms=%s device_unix_ms=%s\n' \
    "$(date +%s%3N)" "$(adb_device shell date +%s%3N | tr -d '\r')" >"${file}"
  "${ADB}" -s "${serial}" logcat -v threadtime -T 1 >>"${file}" 2>/dev/null &
  evidence_pid=$!
}

stop_evidence() {
  [[ -n "${evidence_pid}" ]] || return 0
  kill "${evidence_pid}" 2>/dev/null || true
  wait "${evidence_pid}" 2>/dev/null || true
  evidence_pid=""
  local redacted="${evidence_file}.redacted"
  redact_device_log <"${evidence_file}" >"${redacted}" && mv "${redacted}" "${evidence_file}"
}

# The lengths (ms) of the stretches of voice a person heard that count as voice, one per line.
voice_spans() {
  sed -n 's/^SPAN ms=//p' "$1" | awk -v least="${VOICE_SPAN_MS}" '$1 >= least'
}

# The other half of the proof: what the other people received from the app.
check_peers() {
  local kind="$1" report="$2" spans count held longest lost
  local peer_log="${PEER_LOG#"${REPO_ROOT}/"}" bystander_log="${BYSTANDER_LOG#"${REPO_ROOT}/"}"
  if [[ "${kind}" != "chat" && "${kind}" != "pm" && "${kind}" != "gap" && "${kind}" != "idle" \
    && "${kind}" != "identity" && "${kind}" != "activity" && "${kind}" != "moderation" \
    && "${kind}" != "ban" && "${kind}" != "profile" && "${kind}" != "stream" && "${kind}" != "certificate" \
    && "${kind}" != "camera" && "${kind}" != "tablet" ]]; then
    spans="$(voice_spans "${PEER_LOG}")"
    count="$(grep -c . <<<"${spans}" || true)"
  fi
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
      # The emulator sometimes loses a call when it is answered; the test then lets
      # the phone ring again and says so. The moment on hold in between may or may not be
      # long enough for the second person to hear it as a gap.
      lost="$(sed -n 's/^phone: lost_answers=//p' "${report}" | tr -d '\r')"
      [[ -n "${lost}" ]] || die "the test did not report whether the emulator lost the answered call"
      ((lost == 0)) || printf 'e2e: phone call: the emulator lost %s answered call(s); the test called again\n' "${lost}"
      printf 'e2e: phone call: the second person heard the app %s times (ms: %s)\n' \
        "${count}" "$(tr '\n' ' ' <<<"${spans}")"
      ((count >= 2 && count <= 2 + lost)) \
        || die "the second person should have heard the app before and after the phone call, see ${peer_log}"
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
    mute | headset)
      printf 'e2e: %s: the second person heard the app %s times (ms: %s)\n' \
        "$([[ "${kind}" == mute ]] && echo 'mute and deafen' || echo 'headset button')" \
        "${count}" "$(tr '\n' ' ' <<<"${spans}")"
      ((count == 3)) || die "the second person should have heard the app three separate times, see ${peer_log}"
      ;;
    network)
      sed -n 's/^network: /e2e: network change: /p' "${report}" | tr -d '\r'
      printf 'e2e: network change: the second person heard the app %s times (ms: %s)\n' \
        "${count}" "$(tr '\n' ' ' <<<"${spans}")"
      ((count >= 2)) \
        || die "the second person should have heard the app before and after the network change, see ${peer_log}"
      ;;
    gap)
      sed -n 's/^network: /e2e: network gap: /p' "${report}" | tr -d '\r'
      ;;
    idle)
      sed -n 's/^idle: /e2e: disconnect when alone: /p' "${report}" | tr -d '\r'
      adb_device pull "${IDLE_PICTURE}" "${OUT_DIR}/idle-notification.png" >/dev/null 2>&1 \
        || die "the picture of the warning notification is missing"
      adb_device shell rm -f "${IDLE_PICTURE}"
      ;;
    person)
      grep '^HEARD' "${PEER_LOG}" || die "the second person did not hear the app, see ${peer_log}"
      sed -n 's/^person sheet: /e2e: person sheet: /p' "${report}" | tr -d '\r'
      held="$(sed -n 's/^person sheet: muted for me ms=//p' "${report}" | tr -d '\r')"
      [[ -n "${held}" ]] || die "the test did not report how long the second person was muted for the phone"
      # The second person stops talking, which ends the stretch the third one is hearing.
      kill "${peer_pids[0]}" 2>/dev/null || true
      sleep 2
      longest="$(voice_spans "${BYSTANDER_LOG}" | sort -n | tail -n 1)"
      printf 'e2e: person sheet: the third person heard the second one for %s ms without a break\n' "${longest:-0}"
      ((${longest:-0} >= held)) \
        || die "the third person did not hear the second one while the phone had them muted, see ${bystander_log}"
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
    identity)
      # The second connect may still be on its way to the second person.
      sleep 2
      grep -qx 'FIRST 1' "${IDENTITY_WATCH_LOG}" \
        || die "the second person did not see the phone's certificate, see e2e/build/identity-watch.log"
      grep -qx 'SAME 2' "${IDENTITY_WATCH_LOG}" \
        || die "the server did not see the same certificate on the second connect, see e2e/build/identity-watch.log"
      printf 'e2e: identity: the server saw the same certificate on both connects\n'
      ;;
    profile)
      grep -q "^COMMENT .*${PROFILE_COMMENT}" "${PROFILE_WATCH_LOG}" \
        || die "the second person did not see the phone's comment, see e2e/build/profile-watch.log"
      grep -qE '^AVATAR (png|jpeg) no-exif$' "${PROFILE_WATCH_LOG}" \
        || die "the second person did not get the phone's avatar as a picture without EXIF data, see e2e/build/profile-watch.log"
      ! grep -q '^AVATAR .* exif$' "${PROFILE_WATCH_LOG}" \
        || die "the phone's avatar reached the second person with EXIF data, see e2e/build/profile-watch.log"
      printf 'e2e: profile: the second person saw the comment and the avatar (no EXIF data)\n'
      ;;
    chat)
      grep -q '^SENT' "${CHAT_PEER_LOG}" || die "the second person did not get its messages out, see e2e/build/chat-peer.log"
      grep -q '^RAW SENT' "${RAW_CHAT_LOG}" || die "the raw markup was not sent, see e2e/build/raw-chat.log"
      # What the phone wrote may still be on its way to the second person.
      sleep 2
      local expected
      for expected in \
        'text="Hello from the phone"' \
        'links=\["https://phone.example/page"\]' \
        'links=\["https://gifs.example/phone.gif"\]' \
        'images=1'; do
        grep "^RECEIVED from=${PHONE_NAME} " "${CHAT_PEER_LOG}" | grep -q -- "${expected}" \
          || die "the second person did not receive ${expected} from the app, see e2e/build/chat-peer.log"
      done
      printf 'e2e: chat: the second person received the text, the link, the GIF link and the picture of the app\n'
      ;;
    moderation)
      local expected
      for expected in 'MOVED Raid' 'REMOVED reason=e2e kick' 'BACK'; do
        grep -qx "${expected}" "${MODERATION_LOG}" \
          || die "the moderated person did not report ${expected}, see e2e/build/moderation-target.log"
      done
      ! grep -qx 'REFUSED' "${MODERATION_LOG}" \
        || die "a kick kept the moderated person out, see e2e/build/moderation-target.log"
      printf 'e2e: moderation: the other person was moved and kicked\n'
      ;;
    ban)
      local expected
      for expected in 'REMOVED reason=e2e ban' 'REFUSED' 'BACK'; do
        grep -qx "${expected}" "${MODERATION_LOG}" \
          || die "the banned person did not report ${expected}, see e2e/build/moderation-target.log"
      done
      printf 'e2e: ban: the other person was removed, refused while the ban lasted and let in after it\n'
      ;;
    pm)
      local number
      for number in 1 2 3 4; do
        grep -qx "PM SENT ${number}" "${CHAT_PEER_LOG}" \
          || die "the second person did not get private message ${number} out, see e2e/build/chat-peer.log"
      done
      # The fifth is asked for after the phone left. Usually there is nobody to send it to
      # any more; what counts is that the second person tried, and that the phone shows nothing.
      grep -Eqx 'PM (SENT|FAILED) 5' "${CHAT_PEER_LOG}" \
        || die "the second person did not try the private message after the phone disconnected, see e2e/build/chat-peer.log"
      printf 'e2e: pm: the second person sent four private messages and tried a fifth after the phone left\n'
      ;;
    activity)
      local visit
      for visit in JOINED MOVED LEFT; do
        grep -qx "VISITOR ${visit}" "${CHAT_PEER_LOG}" \
          || die "the visitor did not report ${visit}, see e2e/build/chat-peer.log"
      done
      printf 'e2e: activity: the visitor joined, moved and left while the phone watched\n'
      ;;
    stream)
      # In order: nobody watched before the tap, then the phone did, then it stopped.
      local after_mark first_frame
      grep -qx 'MARK before-tap viewers=0' "${STREAM_PEER_LOG}" \
        || die "the sharer did not report zero viewers before the tap, see e2e/build/stream-peer.log"
      after_mark="$(sed -n '/^MARK before-tap/,$p' "${STREAM_PEER_LOG}")"
      grep -q '^VIEWERS 1$' <<<"${after_mark}" || die "the sharer never had the phone as viewer, see e2e/build/stream-peer.log"
      [[ "$(grep '^VIEWERS' <<<"${after_mark}" | tail -n 1)" == "VIEWERS 0" ]] \
        || die "the phone still watched after it stopped, see e2e/build/stream-peer.log"
      first_frame="$(sed -n 's/^stream: first_frame_ms=\([0-9]*\).*/\1/p' "${report}" | tr -d '\r')"
      printf 'e2e: stream: nothing fetched before the tap; first picture %s ms after the tap\n' "${first_frame:-?}"
      ;;
    tablet)
      grep -q '^VIEWERS 1$' "${STREAM_PEER_LOG}" \
        || die "the sharer never had the tablet as viewer, see e2e/build/stream-peer.log"
      printf 'e2e: tablet: the share played in the right column\n'
      ;;
    camera)
      # The viewer's notes at the test's marks: "MARK <word> frames=<n> pattern=<p>".
      local log="${CAMERA_VIEWER_LOG#"${REPO_ROOT}/"}" first
      frames_at() { sed -n "s/^MARK $1 frames=\([0-9]*\) .*/\1/p" "${CAMERA_VIEWER_LOG}" | tail -n 1; }
      pattern_at() { sed -n "s/^MARK $1 frames=[0-9]* pattern=\([a-z]*\)$/\1/p" "${CAMERA_VIEWER_LOG}" | tail -n 1; }
      grep -qx 'MARK preview frames=0 pattern=other' "${CAMERA_VIEWER_LOG}" \
        || die "the viewer got pictures from the preview, see ${log}"
      sed -n '/^MARK preview/q;p' "${CAMERA_VIEWER_LOG}" | grep -q '^CAMERA ON' \
        && die "the camera was on before \"Start sharing\", see ${log}"
      first="$(sed -n 's/^FIRST_FRAME .* ms_after_on=\([0-9-]*\) ms_after_start=\([0-9-]*\)$/\2 ms after \"Start sharing\", \1 ms after the camera went on/p' "${CAMERA_VIEWER_LOG}" | head -n 1)"
      [[ -n "${first}" ]] || die "the viewer never decoded a picture of the phone, see ${log}"
      [[ "$(pattern_at front)" == "front" ]] || die "the viewer did not see the front camera's pattern, see ${log}"
      [[ "$(pattern_at back)" == "back" ]] || die "the viewer did not see the back camera's pattern, see ${log}"
      [[ "$(frames_at background-start)" == "$(frames_at background-end)" ]] \
        || die "pictures kept coming while the app was in the background, see ${log}"
      (($(frames_at resumed) > $(frames_at background-end))) \
        || die "no pictures came after the app returned, see ${log}"
      sed -n '/^MARK resumed/,$p' "${CAMERA_VIEWER_LOG}" | grep -q '^CAMERA OFF' \
        || die "the viewer did not see the camera turn off, see ${log}"
      printf 'e2e: camera: nothing sent from the preview; first picture %s; front %s and back %s pictures; paused in the background (%s); %s after return\n' \
        "${first}" "$(frames_at front)" "$(frames_at back)" "$(frames_at background-end)" "$(frames_at resumed)"
      grep -h '^camera: ' "${report}" | tr -d '\r' | sed 's/^/e2e: /' || true
      ;;
  esac
}

# The tablet scenario makes the emulator's screen a tablet's; the phone's screen comes back
# here too, so that a test that crashed never leaves the shared emulator tablet-sized.
reset_screen() {
  [[ -n "${serial}" ]] || return 0
  timeout 20 "${ADB}" -s "${serial}" shell 'wm size reset; wm density reset' >/dev/null 2>&1 || true
}

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  step "cleaning up"
  stop_peers
  reset_screen
  if ((status != 0)); then
    # What the other people reported belongs next to the failure, not only into a file.
    local log
    for log in "${PEER_LOG}" "${BYSTANDER_LOG}" "${CHAT_PEER_LOG}" "${RAW_CHAT_LOG}" "${IDENTITY_WATCH_LOG}" \
      "${MODERATION_LOG}" "${PROFILE_WATCH_LOG}" "${STREAM_PEER_LOG}" "${CAMERA_VIEWER_LOG}"; do
      [[ -s "${log}" ]] || continue
      printf 'e2e: the end of %s:\n' "${log#"${REPO_ROOT}/"}"
      tail -n 40 "${log}"
    done
  fi
  stop_phone_network
  stop_messenger
  stop_evidence
  write_summary
  [[ -z "${load_pid}" ]] || kill "${load_pid}" 2>/dev/null
  if [[ -n "${serial}" ]]; then
    # What the network and idle scenarios change is put back, also after a test that died:
    # mobile data on, no saved Wi-Fi network, awake and on its normal power state.
    timeout 20 "${ADB}" -s "${serial}" shell \
      'svc data enable; svc wifi enable; for id in $(cmd wifi list-networks | grep AndroidWifi | cut -d" " -f1); do cmd wifi forget-network "$id"; done; dumpsys deviceidle unforce; dumpsys battery reset' \
      >/dev/null 2>&1
    # The settings outlive the emulator in its device data: without this, every later run on
    # the same device, the instrumented tests among them, would run without animations.
    local entry
    for entry in "${animation_scales[@]}"; do
      if [[ "${entry#*=}" == "null" ]]; then
        timeout 20 "${ADB}" -s "${serial}" shell settings delete global "${entry%%=*}" >/dev/null 2>&1
      else
        timeout 20 "${ADB}" -s "${serial}" shell settings put global "${entry%%=*}" "${entry#*=}" >/dev/null 2>&1
      fi
    done
    timeout 20 "${ADB}" -s "${serial}" logcat -d 2>/dev/null | redact_device_log >"${OUT_DIR}/logcat.txt"
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
    animation_scales+=("${scale}=$(adb_device shell settings get global "${scale}" | tr -d '\r')")
    adb_device shell settings put global "${scale}" 0 || die "switching off the animations failed"
  done
}

mkdir -p "${OUT_DIR}"
rm -f "${OUT_DIR}/instrument.txt" "${OUT_DIR}/logcat.txt" "${OUT_DIR}/emulator.log" \
  "${OUT_DIR}"/voice-*.log "${OUT_DIR}"/hung-screen.* "${CHAT_PEER_LOG}" "${RAW_CHAT_LOG}" "${IDENTITY_WATCH_LOG}" \
  "${PROFILE_WATCH_LOG}" "${STREAM_PEER_LOG}" "${CAMERA_VIEWER_LOG}"
trap cleanup EXIT
if [[ -n "${EVIDENCE_DIR}" ]]; then
  mkdir -p "${EVIDENCE_DIR}"
  (while sleep 1; do printf '%s %s\n' "$(date +%s%3N)" "$(cat /proc/loadavg)"; done) >"${EVIDENCE_DIR}/host-load.txt" &
  load_pid=$!
fi

step "building the app and the tests"
(cd "${REPO_ROOT}/android" && ./gradlew --console=plain --quiet :app:assembleDebug :e2e:assembleDebug)
(cd "${REPO_ROOT}" && cargo build --locked --quiet --package mumcord-voice-peer --package mumcord-chat-peer \
  --package mumcord-transfer-maker)
[[ -x "${PEER_BINARY}" ]] || die "the second person's program was not built at ${PEER_BINARY}"
[[ -x "${CHAT_PEER_BINARY}" ]] || die "the chat person's program was not built at ${CHAT_PEER_BINARY}"
[[ -x "${TRANSFER_MAKER_BINARY}" ]] || die "the transfer maker was not built at ${TRANSFER_MAKER_BINARY}"
# The sharer encodes VP9 on this machine, so it is built on its own with libvpx (a build
# together with the others would give them its codec and test features too).
if [[ "${E2E_SCENARIO:-}" == "" || "${TEST_PACKAGE}.StreamFlowTest" == *"${E2E_SCENARIO}"* \
  || "${TEST_PACKAGE}.CameraFlowTest" == *"${E2E_SCENARIO}"* || "${TEST_PACKAGE}.TabletFlowTest" == *"${E2E_SCENARIO}"* ]]; then
  pkg-config --exists vpx 2>/dev/null || die "libvpx is missing for the stream and camera scenarios (Debian: libvpx-dev, Arch: libvpx)"
  (cd "${REPO_ROOT}" && cargo build --locked --quiet --package mumcord-stream-peer --features sharer)
  [[ -x "${STREAM_PEER_BINARY}" ]] || die "the sharer was not built at ${STREAM_PEER_BINARY}"
fi
command -v python3 >/dev/null 2>&1 || die "python3 is required for the raw chat message and the identity watcher"

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
  if [[ "${peer}" == "chat-restart" ]]; then
    # The app's process is killed and its data kept: what the next start shows was stored.
    adb_device shell am force-stop "${APP_PACKAGE}" || die "stopping the app failed"
  else
    # Clearing the data also takes back every permission and forgets earlier answers to prompts.
    adb_device shell pm clear "${APP_PACKAGE}" >/dev/null || die "clearing the app data failed"
  fi
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
    alone | notify | chat-restart | gap | idle) ;;
    certificate) start_cert_switcher ;;
    person)
      start_peer "${PEER_LOG}"
      start_peer "${BYSTANDER_LOG}" --name "${BYSTANDER_NAME}" --listen-only
      ;;
    identity)
      start_person "${IDENTITY_WATCH_LOG}" python3 "${REPO_ROOT}/e2e/identity-watch.py" "${PHONE_NAME}"
      ;;
    profile)
      start_person "${PROFILE_WATCH_LOG}" python3 "${REPO_ROOT}/e2e/profile-watch.py" "${PHONE_NAME}"
      ;;
    transfer)
      # Base64 keeps the QR code texts whole on their way through the device's shell. The
      # transfer is valid for two minutes, so it is made right before its test starts.
      local made
      made="$("${TRANSFER_MAKER_BINARY}" "${HOST_FROM_EMULATOR}" "${MUMCORD_PORT_BASE}")" \
        || die "making the transfer failed"
      extra=(-e transfer "$(base64 -w0 <<<"${made}")")
      ;;
    lockptt)
      extra=(-e lockSeconds "${LOCK_PTT_SECONDS}")
      start_peer "${PEER_LOG}"
      [[ -z "${EVIDENCE_DIR}" ]] \
        || start_peer "${EVIDENCE_DIR}/voice-monitor-${peer}.log" --name "${MONITOR_NAME}" --listen-only
      ;;
    call)
      [[ "${E2E_DROP_FIRST_ANSWER:-}" != 1 ]] || extra=(-e dropFirstAnswer true)
      start_phone_network
      start_peer "${PEER_LOG}"
      ;;
    chat)
      start_person "${CHAT_PEER_LOG}" "${CHAT_PEER_BINARY}" --phone "${PHONE_NAME}"
      start_person "${RAW_CHAT_LOG}" python3 "${REPO_ROOT}/e2e/raw-chat.py" "${PHONE_NAME}"
      ;;
    pm)
      start_messenger
      PERSON_INPUT="${PM_FIFO}" start_person "${CHAT_PEER_LOG}" "${CHAT_PEER_BINARY}" --phone "${PHONE_NAME}" --private
      ;;
    activity)
      start_messenger
      PERSON_INPUT="${PM_FIFO}" start_person "${CHAT_PEER_LOG}" "${CHAT_PEER_BINARY}" --phone "${PHONE_NAME}" --visitor
      ;;
    stream | tablet)
      start_messenger
      PERSON_INPUT="${PM_FIFO}" start_person "${STREAM_PEER_LOG}" "${STREAM_PEER_BINARY}" 127.0.0.1 "${MUMCORD_PORT_BASE}"
      ;;
    camera)
      start_messenger
      PERSON_INPUT="${PM_FIFO}" start_person "${CAMERA_VIEWER_LOG}" "${STREAM_PEER_BINARY}" --viewer 127.0.0.1 "${MUMCORD_PORT_BASE}"
      ;;
    moderation | ban)
      local superuser
      superuser="$(sed -n 's/^SUPERUSER_PASSWORD=//p' "${TESTENV_FILE}")"
      [[ -n "${superuser}" ]] || die "the test server's SuperUser password is missing in ${TESTENV_FILE#"${REPO_ROOT}/"}"
      extra=(-e superuser "${superuser}")
      start_person "${MODERATION_LOG}" python3 "${REPO_ROOT}/e2e/moderation-target.py"
      ;;
    whisper)
      start_peer "${PEER_LOG}" --listen-only
      start_peer "${BYSTANDER_LOG}" --name "${BYSTANDER_NAME}" --listen-only
      ;;
    *) start_peer "${PEER_LOG}" ;;
  esac
  start_evidence "$((scenarios_run + 1))-${peer}"
  timeout "${TEST_TIMEOUT}" "${ADB}" -s "${serial}" shell am instrument -w \
    -e serverHost "${HOST_FROM_EMULATOR}" -e serverPort "${MUMCORD_PORT_BASE}" -e class "${test}" \
    "${extra[@]}" "${@:5}" "${TEST_PACKAGE}/${RUNNER}" | tee "${report}" || status=$?
  cat "${report}" >>"${OUT_DIR}/instrument.txt"
  stop_evidence
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
  stop_messenger
  stop_cert_switcher
  if [[ "${peer}" != "alone" && "${peer}" != "notify" && "${peer}" != "chat-restart" && "${peer}" != "transfer" \
    && "${peer}" != "certificate" ]]; then
    check_peers "${peer}" "${report}"
    stop_peers
  fi
}

# Runs one scenario in a shell of its own, so that a failure ends only that attempt: whatever
# the attempt started (other people, listeners, the second certificate) is stopped with it.
# Called with "set +e" and never as a condition, which would switch "set -e" off inside.
attempt_scenario() {
  (
    set -e
    trap 'stop_peers; stop_phone_network; stop_messenger; stop_evidence; stop_cert_switcher; reset_screen' EXIT
    run_scenario "$@"
  )
}

# Keeps what a failed attempt left, before its retry writes over it.
keep_failed_attempt() {
  local folder="${OUT_DIR}/retried/$1" log
  mkdir -p "${folder}"
  for log in "${OUT_DIR}/instrument-scenario.txt" "${PEER_LOG}" "${BYSTANDER_LOG}" "${CHAT_PEER_LOG}" "${RAW_CHAT_LOG}" \
    "${IDENTITY_WATCH_LOG}" "${MODERATION_LOG}" "${PROFILE_WATCH_LOG}" "${STREAM_PEER_LOG}" "${CAMERA_VIEWER_LOG}" \
    "${OUT_DIR}/hung-screen.png" \
    "${OUT_DIR}/hung-screen.xml"; do
    [[ -s "${log}" ]] && mv "${log}" "${folder}/"
  done
  timeout 20 "${ADB}" -s "${serial}" logcat -d 2>/dev/null | redact_device_log >"${folder}/logcat.txt"
}

# The outcome of every scenario as a table: e2e/build/summary.md, and the job summary on GitHub.
write_summary() {
  {
    printf '## End-to-end scenarios\n\n'
    if ((${#summary_rows[@]} == 0)); then
      printf 'No scenario finished.\n'
    else
      printf '| Scenario | Result | Duration | Attempts |\n|---|---|---|---|\n'
      printf '%s\n' "${summary_rows[@]}"
    fi
    if ((retried > 0)); then
      printf '\n%s scenario(s) failed once and passed on the retry: a flake to look into (logs in e2e/build/retried/).\n' "${retried}"
    fi
  } >"${SUMMARY_FILE}"
  [[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || cat "${SUMMARY_FILE}" >>"${GITHUB_STEP_SUMMARY}"
}

scenarios_run=0
for scenario in "${SCENARIOS[@]}"; do
  read -r permission microphone peer test <<<"${scenario}"
  [[ "${test}" == *"${E2E_SCENARIO:-}"* ]] || continue
  name="${test##*.}"
  # As run_scenario names them; also read here, to keep them from a failed attempt and to show
  # their end after a failure.
  PEER_LOG="${OUT_DIR}/voice-peer-${peer}.log"
  BYSTANDER_LOG="${OUT_DIR}/voice-bystander-${peer}.log"
  started="${SECONDS}"
  attempt=1
  while true; do
    set +e
    attempt_scenario "${permission}" "${microphone}" "${peer}" "${test}" "$@"
    status=$?
    set -e
    ((status != 0)) || break
    if ((attempt > RETRIES)); then
      summary_rows+=("| ${name} | failed | $((SECONDS - started)) s | ${attempt} |")
      die "${name} failed (attempts: ${attempt})"
    fi
    keep_failed_attempt "$((scenarios_run + 1))-${peer}-attempt-${attempt}"
    if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
      printf '::warning title=End-to-end retry::%s failed and is run again (logs in e2e/build/retried/)\n' "${name}"
    fi
    step "RETRY: ${name} failed, running it again"
    attempt=$((attempt + 1))
  done
  if ((attempt > 1)); then
    retried=$((retried + 1))
    summary_rows+=("| ${name} | passed on retry | $((SECONDS - started)) s | ${attempt} |")
  else
    summary_rows+=("| ${name} | passed | $((SECONDS - started)) s | 1 |")
  fi
  scenarios_run=$((scenarios_run + 1))
done
((scenarios_run > 0)) || die "no scenario matches E2E_SCENARIO=${E2E_SCENARIO:-}"
((retried == 0)) || step "PASSED with ${retried} retried scenario(s), see e2e/build/summary.md"
step "PASSED"
