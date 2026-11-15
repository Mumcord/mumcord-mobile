# Development setup

How to get a machine ready to build and test the Mumcord mobile apps. This page covers the
Android toolchain on Linux; the iOS toolchain needs a Mac and is described in [iOS](#ios) below.

Before every APK or TestFlight release, run the [manual device checklist](device-checklist.md) on real phones (battery and data measurements included).

iOS work is paused; what iOS still needs is listed in the [iOS backlog](ios-backlog.md).

## What you need beforehand

- Linux on x86_64 with KVM available (`/dev/kvm` readable and writable for your user). The
  emulator is unusably slow without it.
- `rustup` and `cargo`, plus `curl`, `unzip`, `tar` and `sha256sum`.
- `meson`, `ninja`, `nasm`, `make` and `perl` for the video codecs; see
  [Video codecs](#video-codecs) for the packages and for using codecs that were built already.
- About 12 GB of free disk space and a download of roughly 4 GB.

## One-time setup

```sh
scripts/setup-android.sh
```

The script needs no `sudo`, does not touch your shell profile and leaves the system default
Java alone. It is safe to run again at any time: parts that are already present at the pinned
version are skipped. Running it accepts the Android SDK licences on your behalf.

| Part | Version | Location |
| --- | --- | --- |
| JDK (Eclipse Temurin) | 21.0.13+9 | `~/.local/share/mumcord/jdk-21.0.13+9` |
| Android command-line tools | 23.0 | `~/Android/Sdk/cmdline-tools/latest` |
| Platforms | API 37.0 (compile), API 31 (minimum) | `~/Android/Sdk/platforms` |
| Build tools | 37.0.0 | `~/Android/Sdk/build-tools/37.0.0` |
| NDK | 30.0.16248370 (r30) | `~/Android/Sdk/ndk/30.0.16248370` |
| CMake (builds the C++ audio library) | 3.31.6 | `~/Android/Sdk/cmake/3.31.6` |
| Platform tools (`adb`) and emulator | current stable | `~/Android/Sdk/platform-tools`, `~/Android/Sdk/emulator` |
| Emulator image | API 37.0, Google APIs, x86_64 | `~/Android/Sdk/system-images` |
| Emulator device `mumcord-test` | Pixel 7 profile | `~/.android/avd` |
| `cargo-ndk` | 4.1.2 | `~/.cargo/bin` |
| Rust targets | `aarch64-linux-android`, `x86_64-linux-android` | rustup, for the toolchain in `rust-toolchain.toml` |

The Android Gradle plugin does not run on newer Java versions, which is why the project uses
its own JDK 21 instead of the system one.

## Environment variables

The script prints the variables the build needs. Set them in the shell you build from:

```sh
# bash / zsh
eval "$(scripts/setup-android.sh --env)"
```

```fish
# fish
set -gx ANDROID_HOME ~/Android/Sdk
set -gx ANDROID_NDK_HOME ~/Android/Sdk/ndk/30.0.16248370
set -gx JAVA_HOME ~/.local/share/mumcord/jdk-21.0.13+9
```

`JAVA_HOME` is meant for this project only. Do not export it globally if other projects on the
machine rely on the system Java.

## Checking the setup

```sh
scripts/setup-android.sh --verify
```

This lists the installed SDK packages, compiles a small C file for `aarch64` with the NDK, and
boots the `mumcord-test` emulator without a window until Android reports that it finished
booting, then shuts it down again. It takes one to two minutes.

## Daily commands

With the environment variables set:

```sh
# Start the emulator (add -no-window to run it headless)
$ANDROID_HOME/emulator/emulator -avd mumcord-test

# Is it up? Prints 1 once Android finished booting
$ANDROID_HOME/platform-tools/adb shell getprop sys.boot_completed

# Stop the emulator
$ANDROID_HOME/platform-tools/adb emu kill

# Build the Rust engine for the emulator and for real phones
cargo ndk -t x86_64 -t arm64-v8a build

# App logs
$ANDROID_HOME/platform-tools/adb logcat
```

From inside the emulator the local test server (`scripts/testenv.sh up`, see
[End-to-end tests](#end-to-end-tests)) is reachable as `10.0.2.2:64838`. Never test against a
public server.

## End-to-end tests

The end-to-end tests run the real app with the real engine in the emulator against the local
Mumble test server and check what a user would see. One command does everything:

```sh
eval "$(scripts/setup-android.sh --env)"
e2e/run.sh
```

It builds the app and the tests, starts the test server and the emulator (without a window),
installs the app afresh, runs the tests, and stops the server and the emulator again. A run
takes about ten minutes once everything is built (the first build takes several minutes);
`E2E_SCENARIO=<part of a test name>` runs only the matching scenarios. If an
emulator is already running, the script uses it and leaves it running. The result is
`e2e: PASSED` or `e2e: FAILED: …` and a matching exit code. The logs are in `e2e/build/`: the
test output (`instrument.txt`), the device log (`logcat.txt`), the emulator log, what the other
people of the voice scenarios reported (`voice-peer-<scenario>.log`) and, for a scenario that
hung, the screen at that moment (`hung-screen.png`). A test that fails while waiting says what
was on the screen in its failure message.

- **Test server:** `scripts/testenv.sh up` and `down` start and stop it on their own. It is the
  test environment of the Mumcord repository (a stock `mumble-server` in Docker with the
  channels Lobby, Raid and AFK) at the commit pinned in `engine.lock.toml`, checked out into
  the git-ignored `.engine/`. It listens on port 64838 (`MUMCORD_PORT_BASE` changes that), so
  it does not collide with a test server of the desktop client. Every run starts with a fresh
  server and freshly installed app data.
- **Tests:** the Gradle module `android/e2e`. A new flow is a new test class there; run a
  single one with `e2e/run.sh -e class app.mumcord.mobile.e2e.ConnectFlowTest`. The tests skip
  themselves when started without the script (for example by `./gradlew connectedDebugAndroidTest`),
  because they then have no server.
- **Scenarios:** `e2e/run.sh` runs each test as its own scenario with fresh app data and the
  permissions it needs: connecting; the local network and the microphone question with the
  system's own dialogs; voice in both directions, push-to-talk, mute and deafen, and whisper
  with a second person on the host (`e2e/voice-peer`); the notification question (allowed,
  and "not now"); the session's notification and its buttons; voice with the app in the
  background and the screen locked, with voice activity (three minutes) and with
  push-to-talk (one minute, silent while locked); the audio output as the emulator has it
  (a loudspeaker only: no earpiece, so the first-connect question never appears there, and
  no headset can be plugged in or paired) and a headset's button, pressed with
  `input keyevent` while the app is in the background and while the screen is off (the
  debug build's `TestSettings.treatOutputAsHeadset` makes the loudspeaker count as the
  headset; the device log says how long the phone system took to have the call again:
  "the call was added again after … ms"); a change from Wi-Fi to mobile data and a longer
  time without any network (the emulator is on mobile data and does not join its simulated
  Wi-Fi "AndroidWifi" by itself: the test joins it with `cmd wifi connect-network` and
  switches with `svc wifi` and `svc data`; the host stays reachable as `10.0.2.2` over both,
  mobile data counts as metered, and the script leaves the emulator on mobile data with no
  saved Wi-Fi network); "disconnect when alone" with the times shortened to 20 and 15
  seconds by the debug build's `TestSettings.idleTimes`, while the phone is asleep and
  forced into Doze (`dumpsys deviceidle force-idle`), which also leaves a picture of the
  warning notification in `e2e/build/idle-notification.png`; a phone call during a session, for
  which the script plays the phone network through the emulator's console
  (`adb emu gsm call|cancel`; the emulator occasionally loses a call at the moment
  it is answered, also with no app running, so the test then lets the phone ring once more
  and the run prints "the emulator lost 1 answered call(s)"; `E2E_DROP_FIRST_ANSWER=1`
  forces that path for debugging); and the chat with two more
  people on the host, `e2e/chat-peer`
  (writes like the desktop app and reports what it receives) and `e2e/raw-chat.py` (sends
  markup a hostile client might send), followed by a restart of the app that must still show
  the history; and private messages, for which `e2e/chat-peer --private` writes to the phone
  whenever the test asks for it (notification with sender and text, tap opens the
  conversation, no notification for the open conversation, "Show sender only", none with
  "Private messages" switched off, nothing after disconnecting); the activity log, for which
  `e2e/chat-peer --visitor` joins the server, moves to "Raid" and leaves whenever the test asks
  for it; the identity created at the first start, which `e2e/identity-watch.py` (a
  foreign client on the host) must see with the same certificate on two connects; and
  "Scan from desktop app" with a transfer that `e2e/transfer-maker` makes for the run with
  the engine's own code, as the desktop app does (a new identity and a new 8-digit code every
  time, handed to the test as an instrumentation argument and written to no file or log). The
  emulator's camera cannot be shown a QR code, so the debug build's `TestSettings.qrTexts`
  feeds the texts of the codes to the scanner instead of the camera; the camera question with
  the system's dialog, the slow check of the code, the wrong-code and expired-code refusals, the
  import and the connection with the imported identity are the real ones. Reading QR codes
  from a real camera image (and its focus and lighting) is only checked on a phone against the
  desktop app. Moderation runs with the phone connected as the test server's SuperUser (the
  password from `.engine/testenv/.env`, passed as an instrumentation argument and removed from
  the device log) against `e2e/moderation-target.py`, a foreign client that reports being
  moved, kicked and banned. Server mute is covered by the instrumented tests only: a standard
  server tells the SuperUser its rights for the root channel alone. A ban covers the address that every person of the
  test server shares, so the debug build's `TestSettings.banSeconds` shortens it to 15
  seconds, its scenario runs last, and it only passes once the person is let in again.
- **Public servers:** the browser is never tested against the real list service or a public
  server. `LocalPublicList` (android/e2e) serves a list of its own inside the emulator on
  127.0.0.1 and points the engine at it with `MUMCORD_PUBLIC_SERVERS_URL`, set by the test
  before the app creates the engine (only test code sets it; the app has no way to change the
  list's address). With that variable the engine contacts nothing but the loopback address, so
  the list's entry for the local test server is `127.0.0.1` too, and a relay in the test passes
  its ping (UDP) and its connection (TCP) on to the test server on the host and to nothing
  else; the other entries are closed loopback ports that do not answer. The test stops serving
  the list to check that a failed refresh keeps the list loaded before. The real list and the
  pings of real servers are not part of any test.
- **QR scanner:** `android/platform/scanner` reads QR codes with CameraX (the camera picture
  and its frames) and ZXing core (finding and reading the code), both Apache-2.0 and both
  without Google Play services, which a phone that installed the APK from outside a store may
  not have. Their files are pinned by checksum in `android/gradle/verification-metadata.xml`,
  so a version change needs new checksums there (`./gradlew --write-verification-metadata
  sha256 …` writes them; keep only the camera and ZXing entries). `QrDecoderTest` reads codes
  from generated pictures on the computer.
- **Besides the Android toolchain** the machine needs KVM, `git`, Docker with the compose
  plugin, `sqlite3`, `openssl` and `python3`. No Docker network is created; the server uses Docker's
  built-in bridge.
- **Settings:** `E2E_AVD` (emulator device, default `mumcord-test`), `E2E_BOOT_TIMEOUT` and
  `E2E_TEST_TIMEOUT` (seconds, default 300 and 600; the second is per scenario) and
  `E2E_RELEASE_BOUND_MS` (how much longer than hold-to-talk was held the app may be heard,
  default 200).
- **Waits and timing:** every wait in the tests has a bound sized for the hosted CI emulator,
  which is several times slower than a developer's (60 s per step, 30 s for a system dialog);
  a wait ends as soon as what it waits for is there, so the bounds cost nothing on a fast
  machine. The push-to-talk and whisper scenarios compare the time the button was held
  (measured in the emulator) with the stretch of voice the second person played (measured on
  the host): at least half of the hold and at most the hold plus `E2E_RELEASE_BOUND_MS`. CI
  sets 300 ms, because a stall of the emulator or of the second person at the end of the
  stretch adds to it there; a button that does not stop the voice is heard for seconds longer.

## Video codecs

Streams are decoded with libvpx (VP9; its encoder is for sending the camera) and dav1d (AV1),
two C libraries that are built into the engine library of every phone target.
`scripts/build-codecs.sh <target>` downloads their pinned releases (versions and checksums at the
top of the script), cross-builds them as static libraries with the NDK or Xcode and keeps them in
`.codecs/<target>/` (git-ignored; set `MUMCORD_CODECS_DIR` to keep them elsewhere).
`scripts/build-android.sh` and `scripts/build-ios.sh` call it for each target they build, so the
first build takes a minute or two longer and later builds reuse the result. A new version of the
script, a new NDK or a new Xcode builds them again. `CODECS_JOBS` limits the parallel compile
jobs.

Building them needs meson, ninja and nasm (nasm only for the x86_64 emulator target):

| System | Install |
| --- | --- |
| Debian, Ubuntu | `sudo apt install meson ninja-build nasm` |
| Fedora | `sudo dnf install meson ninja-build nasm` |
| Arch, CachyOS | `sudo pacman -S meson ninja nasm` |
| macOS | `brew install meson ninja nasm` |

Without these tools, point `MUMCORD_CODECS_DIR` at a folder with codecs that were already built
(one subfolder per target, as `scripts/build-codecs.sh` leaves them). Several working copies on
one machine can share one such folder too. The script uses a prebuilt target as it is when its
`BUILD-ID` matches the current script and toolchain, and only then needs none of the tools; after
a change to the script it builds again.

Builds for this machine (the binding generator, `cargo test`, the end-to-end helpers) leave the
codecs out: the bridge switches the engine's `vp9` and `av1-decode` features on for Android and iOS
only.

## Continuous integration

`.github/workflows/ci-android.yml` runs on pull requests and on `main` whenever the Android
app, the bridge, the end-to-end tests, the scripts or the pinned versions change. It has two
jobs on GitHub's Linux runners, both using `scripts/setup-android.sh`, so CI builds with exactly
the versions listed above:

- **Lint, test and build:** `./gradlew lint test assembleDebug` with the toolchain installed by
  `scripts/setup-android.sh --no-emulator`. The debug APK and the lint reports are attached to
  the run as artifacts.
- **Emulator tests:** boots the `mumcord-test` emulator, runs
  `./gradlew connectedDebugAndroidTest` and then `e2e/run.sh` against the test server in Docker
  (also when an instrumented test failed, so one run shows everything that is broken). The
  contents of `e2e/build/`, the device log of the instrumented tests among them, and the test
  reports are attached to the run as `emulator-test-logs`, also when it fails.

The Rust build, the Gradle downloads, the toolchain and the video codecs are cached between
runs. The emulator
and its image are not (they are several GB) and are downloaded on every run.

`.github/workflows/release.yml` builds the signed release APK for a version tag, and a test
release with a throwaway key on pull requests that change the release pipeline; see
[RELEASING.md](../RELEASING.md).

## Updating a version

All versions and checksums are pinned at the top of `scripts/setup-android.sh`. Change them
there, run the script, run `--verify`, and update the table above in the same change. Old SDK
packages are not removed automatically; delete their folders under `~/Android/Sdk` by hand.

## Removing everything

```sh
rm -rf ~/Android/Sdk ~/.local/share/mumcord ~/.android/avd/mumcord-test.avd ~/.android/avd/mumcord-test.ini
cargo uninstall cargo-ndk
```

## iOS

The iOS app can only be built on a Mac. You need:

- Xcode 26 or newer with an iOS simulator runtime (the script uses the simulator "iPhone 17").
- `rustup`; the Rust version and the iOS targets come from `rust-toolchain.toml`
  (`rustup toolchain install` in the repository folder).
- XcodeGen, cmake, meson and ninja: `brew install xcodegen cmake meson ninja`.

```sh
# Bridge for device and simulator, Swift bindings, MumcordBridge.xcframework, Mumcord.xcodeproj
scripts/build-ios.sh

# The same, then the unit and UI tests in the simulator (all test groups, one after the other)
scripts/build-ios.sh --test

# The steps CI runs in separate jobs: build once, then run one test group from the products
scripts/build-ios.sh --build-for-testing
scripts/build-ios.sh --test-without-building core

# Another simulator
IOS_SIMULATOR="iPhone 17 Pro" scripts/build-ios.sh --test
```

The Xcode project is generated from `ios/project.yml` and not committed; open
`ios/Mumcord.xcodeproj` after the script ran. Test results land in
`ios/build/TestResults-<group>.xcresult`. From the simulator the local test server is reachable
as `localhost:64738`.

CI (`.github/workflows/ci-ios.yml`) runs the same script in parallel jobs: one job builds the
bridge, the app and the tests once (`--build-for-testing`) and uploads the test products
(`ios/build/Mumcord.xctestproducts`, packed as one archive); one job per test group downloads them
and runs `--test-without-building <group>` on a fresh simulator. The job `iOS` needs all parts
and is the one name for branch protection.

The test groups are the list `group_tests` in `scripts/build-ios.sh`, balanced by run time:

| Group | Tests |
| --- | --- |
| `app-screens` | app screenshots, design reference, launch test |
| `core` | all unit tests (`MumcordTests`), app flow, voice flow |
| `voice-chat-screens` | voice screenshots, chat screenshots |
| `chat-flow` | chat flow, session-ended notice (the lightest group: the place for new tests) |
| `messages` | messages flow, messages screenshots |
| `output-settings` | audio output flow, settings and "disconnect when alone" flow, their screenshots |
| `identities` | identities and "Scan from desktop app" flow, their screenshots |

Before its tests each group starts the app once in the simulator and ends it again (about 20
seconds), so the first UI test does not pay for the first start of the app in a cold simulator.

Each group costs about five minutes of simulator start-up on top of its tests, so add a group
only when the longest one exceeds the others by more than that.

To add a UI test class, put it in `ios/Tests/UI` and add `MumcordUITests/<ClassName>` to the group
with the shortest run time (see the Actions run) in `group_tests`; nothing else changes, the CI matrix is generated
from the same list. `scripts/build-ios.sh --check-groups` (a CI job that runs first, works on
any OS) fails when a UI test class is in no group, in two groups, or a listed class does not
exist. New unit tests need nothing: the whole unit bundle runs in `core`.

### Message notifications (iOS)

Private messages and mentions become local notifications (`UNUserNotificationCenter`) only while
the app is connected; there is no push server, so nothing arrives after disconnecting or once
the system has ended the app. One notification per conversation, grouped by conversation, removed
when the chat is opened or read. The UI tests run with a fake permission (`notif-ask`,
`notif-denied`) and never show the system's question. Only a real iPhone shows the system's
permission question, the banner and lock screen with the phone's preview setting, "Show sender
only", grouping in the notification centre, a tap that opens the conversation, and Focus modes.

### Voice in the background and the system call (iOS)

A voice session is shown to iOS as an ongoing outgoing call through CallKit (`ios/Platform/Call`):
that is what keeps it running in the background and with a locked screen, gives it the system's
call screen with mute and end, and lets iOS hold it while the user takes a phone call. While
such a call exists the audio session is started and stopped by iOS, never by the app; the app
only opens its audio unit when iOS says the call's audio is active.

Whether sessions are calls at all is decided in exactly one place, `CallSystems.make`
(`ios/Platform/Call/CallKitSystem.swift`). Where it returns nil, and whenever iOS refuses the
call, the session runs on the app's own audio session instead: voice still works in the
background (background mode `audio`), but iOS shows no call, has no mute or end button for it,
and a phone call only pauses the audio on the phone instead of muting and deafening on the server.
The simulator always takes this path: its call service does take an outgoing call (a unit test
starts and ends one there), but it is reported to end such calls by itself after a moment, which
would end every session started in a simulator.

Only a real iPhone can show: the call screen and the lock screen, a real phone call holding the
session, car systems, the recent calls list, and how long iOS keeps the app alive.

### Audio output and the headset button (iOS)

The outputs of a session come from the audio session, which only `SystemVoiceSession`
(`ios/Platform/Audio/VoiceSession.swift`) touches. iOS lists microphones, never outputs, so the
list is built from them (`outputState` in `ios/Platform/Call/AudioOutput.swift`): the
loudspeaker always, the earpiece on an iPhone (an iPad has none, so it never asks "Loudspeaker
or earpiece?"), every Bluetooth hands-free device, and a wired headset. The loudspeaker is
chosen with an override of the route, every other output by choosing its microphone. iOS forgets
the choice whenever the session's audio stops, so `SessionOutputs` makes it again when the audio
is back. The rules (a headset is used at once, the question, the saved default) are the ones of
Android, in `ios/Mumcord/Session/VoiceOutput.swift`.

While the session is a call, iOS keeps a headset's buttons for itself and only tells the app
"mute" or "end the call", never where the request came from. A mute request (the stem of
AirPods, a car's mute key, the mute button of the call screen) toggles the microphone with a
short sound. An end request always leaves the session, because the red End button of the lock
screen and the Dynamic Island arrives as the same request and must do what it says; that is the
one difference to Android, and it is the single function `endRequestMeans`. The sounds are
played by players of their own (`ios/Platform/Audio/FeedbackTone.swift`), outside the audio
unit.

The simulator has no call, no earpiece, no headset and no route to change, so the tests run
against a faked phone: the debug launch options `outputs`, `output-bluetooth` and `output-ask`
(`ios/Mumcord/Debug/AppDebugLaunch.swift`). Only a real iPhone can show: that the output really
moves while iOS owns the call's audio, what headphones without a microphone and a second
Bluetooth device look like, which request each headset's button sends (the button of wired
EarPods is expected to end the call and so to leave the session), and that the sounds are heard
on the headset.

**For the store release (Epic 6).**

- The app declares the background modes `audio` and `voip`. `voip` is what gives access to
  CallKit according to Apple's developer support, but the app uses no PushKit and has no push
  server. App Review may ask to demonstrate the VoIP feature or to remove the mode; whether a
  CallKit-only app passes is not confirmed by Apple's documentation.
- Apple does not accept CallKit being active in mainland China. Nothing switches it off by
  region yet: `CallSystems.make` has to return nil there, which gives the behaviour described
  above. Apple names no official way to detect the region.

### Network changes, mobile data and "disconnect when alone" (iOS)

The network comes from `NWPathMonitor` (`ios/Platform/Network/NetworkMonitor.swift`). A path
that iOS calls expensive (mobile data, a personal hotspot) or constrained (Low Data Mode) counts
as metered; the switches of Settings → "Mobile data" apply there (`ios/Mumcord/Session/MobileData.swift`):
images and GIFs are loaded as "Load images and GIFs" says, and "Reduced voice quality" limits the
voice to 24 kbit/s. The engine forgets that limit when it starts, so `NetworkSession` sets it
again with every connection and every change of the network or the switch.

The engine reconnects by itself after a network change. From its third attempt on it waits up
to half a minute between tries, so when a working network appears then, the app ends the
connection and starts it again itself (`SessionStore.reconnectNow`): into the same channel, with
the same mute and deafen, and with the password typed for this connection, which is kept in
memory only and forgotten when the connection ends. The bridge has no command for this yet.

"Disconnect when alone for 30 minutes" (`ios/Mumcord/Session/IdleDisconnect.swift`) waits in the
app's own process on the continuous clock, which goes on while the phone sleeps; a session only
exists while the call keeps the app running. The warning is a local notification with the action
"Stay connected", which works without opening the app; its answer comes to the app's one
notification delegate (`SystemMessageNotifier.onResponse`). Without the permission for
notifications the app still disconnects and says so when it is opened. The debug launch options
`alone`, `idle-on` and `idle-short` (3 seconds instead of 30 minutes and 1 minute) let the UI
tests go through it; `metered` puts the app on a pretend mobile data network.

The simulator always has the Mac's network, so only a real iPhone can show: switching between
Wi-Fi and mobile data during a session, the reconnect into the same channel, the expensive and
constrained flags of a real path, and the warning notification with its "Stay connected" button.

### Identities and "Scan from desktop app" (iOS)

The identity screens (`ios/Mumcord/Screens/Identities/`) follow the identities the engine reports
(`IdentitiesChanged`); certificates and keys stay in the engine and the Keychain. An identity file
comes through the system's file picker, a backup leaves through the share sheet as a `.p12` file
that exists in the app's temporary folder only while the sheet is open.

The scanner reads QR codes with the system's own reader (`AVCaptureMetadataOutput`,
`ios/Platform/QrCameraSource.swift`) behind the protocol `QrSource`. The typed code is kept in
memory only, cleared after each check and never logged; the check itself runs in the bridge, off
the main thread. The camera is explained before iOS asks for it (`CameraSheet`), for the scanner
and for the chat's "Take photo"; after a refusal only the system settings help.

The simulator has no camera to show a code to. The debug launch option `qr-parts` makes the
scanner see the QR codes of a scripted transfer (`FakeDesktopTransfer`) instead, `camera-ask`,
`camera-refuse` and `camera-blocked` stand for the camera permission, and
`-mumcordIdentities <preset>` puts the identity screens into the states of the screenshots.
Only a real iPhone can show the camera picture, reading the desktop's codes from a screen and the
system's camera question; a transfer made by the desktop app is checked there by hand.
