# mumcord-mobile

Mumcord for Android and iOS: native apps on the shared Mumcord engine. Works with any standard Mumble server.

**Status: in development.** Nothing is released yet.

## How it relates to Mumcord

- [Mumcord/mumcord](https://github.com/Mumcord/mumcord) is the desktop client and owns the engine (protocol, audio, codecs, storage). This repository pulls it in as a library pinned to an exact reference, see `engine.lock.toml`.
- This repository holds the mobile apps: a small Rust bridge (UniFFI), the Android app (Kotlin, Jetpack Compose) and the iOS app (Swift, SwiftUI).
- Specification: `specs/05-mobile-app-specification.md` in the Mumcord repository, with the task plan in `specs/05-mobile-app-tasks.md`.

## Layout

| Path | Content |
|---|---|
| `bridge/` | Rust crate `mumcord-mobile-bridge` (UniFFI) |
| `android/` | Gradle project: `app/`, `engine/`, `platform/` |
| `ios/` | XcodeGen `project.yml`, SwiftUI app, platform code, generated bridge |
| `design/` | Theme tokens and fonts from the desktop app, generators |
| `i18n/` | Translation conversion from the desktop files |
| `e2e/` | Emulator and simulator tests |
| `scripts/` | Setup and build scripts |
| `docs/` | Device checklist, screenshots |

Directories fill up as the tasks of the plan land.

## License

AGPL-3.0, see `LICENSE`.
