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
takes under a minute once everything is built (the first build takes several minutes). If an
emulator is already running, the script uses it and leaves it running. The result is
`e2e: PASSED` or `e2e: FAILED: …` and a matching exit code; the test output, the app log and the emulator log are in `e2e/build/`.

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
- **Besides the Android toolchain** the machine needs KVM, `git`, Docker with the compose
  plugin, `sqlite3` and `openssl`. No Docker network is created; the server uses Docker's
  built-in bridge.
- **Settings:** `E2E_AVD` (emulator device, default `mumcord-test`), `E2E_BOOT_TIMEOUT` and
  `E2E_TEST_TIMEOUT` (seconds, default 300 and 600).

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
