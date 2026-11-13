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

From inside the emulator the local test server of the Mumcord repository (`testenv/up.sh`) is
reachable as `10.0.2.2:64738`. Never test against a public server.

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
