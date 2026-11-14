# mumcord-mobile – Mumcord for Android and iOS

Native mobile apps on the shared Mumcord engine, compatible with standard Mumble servers (murmur / mumble-server). The engine lives in the Mumcord repository (`Mumcord/mumcord`); the specification is `specs/05-mobile-app-specification.md` there, the task plan `specs/05-mobile-app-tasks.md`.
The user is a product manager and not a technical person → make technology decisions with a clear recommendation, explained simply.
Everything is in English: chat, docs, code, comments, commit messages and UI text.

## Answer style: caveman mode (always)

- Chat answers extremely short: no filler, no intro, no repetition. Sentence fragments are fine.
- Only the result and next steps. Bullet points instead of prose. For code changes, `file:line` is enough instead of code excerpts.
- Must still be understandable for a non-technical person.
- **Applies to chat only.** Docs, commit messages and code comments are written fully and cleanly.

## Way of working

- The specification is the truth: only build what it says. Unclear or contradictory → ask instead of guessing. No scope creep.
- One task = small, verifiable steps. One fresh agent per subtask with a self-contained brief; the agent ends after its final report.
- Commit only on request. Never `git add -A`. Never push without being asked. Never merge your own pull request.
- Commits are small and focused, Conventional Commits style (`feat:`, `fix:`, `docs:`, `chore:` …), clear English message. Never add AI attribution: no `Co-Authored-By` trailers, no "Generated with" lines, no mention of Claude or AI.
- Default branch `main`; feature branches `feat/<area>`, other work `chore/…` or `fix/…`. Rebase on the latest `main` before every push; push an already pushed feature branch with `--force-with-lease`; never force-push `main`.
- All work of the first version goes to the branch `feat/first-version` and its single pull request.
- Every user-visible change adds a `CHANGELOG.md` entry under `[Unreleased]`.
- Delete your build cache in `~/.cache/mumcord-build/<agent>` when done (Rust targets and Gradle caches are large). Use `CARGO_TARGET_DIR=~/.cache/mumcord-build/<agent>`.
- **Secrets never go into the repo**: no passwords, Android keystore, Apple certificates, provisioning profiles or App Store Connect keys in files, docs or examples. Only git-ignored local env files and CI secrets.
- **Never test against public or third-party Mumble servers.** Use the local Docker test server of the Mumcord repository (`testenv/up.sh`; from the Android emulator `10.0.2.2`, from the iOS simulator `localhost`).
- **Client only.** Never require or modify anything on the server side. Mumcord Mobile must work fully against an unmodified standard Mumble server.
- Every new source file starts with an SPDX licence header (`SPDX-License-Identifier: AGPL-3.0-only`) in the comment style of its language.

## Architecture and stack

- **Bridge:** Rust crate `mumcord-mobile-bridge` with UniFFI (proc-macro mode), generating Kotlin (`app.mumcord.mobile.bridge`) and Swift (`MumcordBridge`). Commands in, state and events out; bridge types are own records and enums, engine types never leak into the bindings.
- **Android (leads):** Kotlin, Jetpack Compose, minSdk 31, JDK 21 for Gradle. Platform code in thin modules under `android/platform/`.
- **iOS:** Swift, SwiftUI, deployment target 17.0, project generated from `ios/project.yml` (XcodeGen). Platform code under `ios/Platform/`.
- **Android leads:** build and verify Android first; iOS follows the same behaviour.
- **Engine changes go to Mumcord/mumcord first.** Never patch or fork engine code here; land the change in the Mumcord repository, then move the pin in `engine.lock.toml`.
- **Engine pin:** always an exact commit hash (development) or release tag (mobile release), never a branch name. `engine.lock.toml` and the bridge dependency must agree.
- Phone targets: `aarch64-linux-android`, `x86_64-linux-android` (emulator only), `aarch64-apple-ios`, `aarch64-apple-ios-sim`.

## Coding rules

**General**
- Simple over clever. No speculative abstractions. No dead code, no commented-out blocks.
- Comments explain the *why*, not the *what*. Follow the style of the surrounding code.
- Never swallow errors silently: log them or report them cleanly to the user.
- Strict typing; no escape hatches without a reason.

**Real-time audio & networking**
- Never block the audio thread (no allocation, locks, I/O or logging in the real-time path), also not in bridge callbacks. Callbacks run on a bridge thread.
- UI code never calls the engine from a composable or view body; Kotlin wraps events in `StateFlow`, Swift in `@Observable` stores. UI never talks to the network directly.
- Stay compatible with standard Mumble servers; no private protocol extensions.

**UI**
- Mockups are binding: follow them as closely as possible; deviations need a reason stated in the PR.
- Every UI change needs dark and light screenshots, committed to `docs/images/screenshots/` and embedded in the PR description.
- Keep components small. Separate state/data access from presentation.
- Accessibility: TalkBack and VoiceOver labels, touch targets, dynamic font and display scaling, system dark/light theme.

**Tests**
- New logic gets tests. Critical flows get an end-to-end test against the local test server (emulator / simulator).
- A bug is first reproduced with a test, then fixed.
- Before "done": build, tests, type checks and linters pass. If not, say so honestly.

**Security & privacy**
- TLS always on; verify server certificates and warn clearly on changes. Protect the user's client certificate: keystore on Android, Keychain on iOS.
- No personal data, chat contents or audio in logs.
- Treat everything from the server (names, comments, chat HTML) as untrusted input: sanitize before rendering.
