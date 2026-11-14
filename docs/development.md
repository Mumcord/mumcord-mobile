# Development setup

How to get a machine ready to build and test the Mumcord mobile apps. This page covers the
Android toolchain on Linux; the iOS toolchain needs a Mac and is described in [iOS](#ios) below.

## What you need beforehand

- Linux on x86_64 with KVM available (`/dev/kvm` readable and writable for your user). The
  emulator is unusably slow without it.
- `rustup` and `cargo`, plus `curl`, `unzip`, `tar` and `sha256sum`.
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
  push-to-talk (one minute, silent while locked); a phone call during a session, for
  which the script plays the phone network through the emulator's console
  (`adb emu gsm call|cancel`); and the chat with two more people on the host, `e2e/chat-peer`
  (writes like the desktop app and reports what it receives) and `e2e/raw-chat.py` (sends
  markup a hostile client might send), followed by a restart of the app that must still show
  the history.
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

The Rust build, the Gradle downloads and the toolchain are cached between runs. The emulator
and its image are not (they are several GB) and are downloaded on every run.

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
- XcodeGen and cmake: `brew install xcodegen cmake`.

```sh
# Bridge for device and simulator, Swift bindings, MumcordBridge.xcframework, Mumcord.xcodeproj
scripts/build-ios.sh

# The same, then the unit and UI tests in the simulator
scripts/build-ios.sh --test

# Another simulator
IOS_SIMULATOR="iPhone 17 Pro" scripts/build-ios.sh --test
```

The Xcode project is generated from `ios/project.yml` and not committed; open
`ios/Mumcord.xcodeproj` after the script ran. Test results land in
`ios/build/TestResults.xcresult`. CI runs the same script on a macOS runner
(`.github/workflows/ci-ios.yml`). From the simulator the local test server is reachable as
`localhost:64738`.
