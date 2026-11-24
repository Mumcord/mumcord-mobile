# iOS plan: bringing the iPhone and iPad app level with Android

Working plan for the open iOS tasks of [ios-backlog.md](ios-backlog.md). The backlog says *what*
is missing; this plan says in which order it is built, how each step is cut, and when a step
counts as done. Task numbers refer to `specs/05-mobile-app-tasks.md` in the Mumcord repository,
FR and AC numbers to `specs/05-mobile-app-specification.md` there. The specification stays the
truth; this plan adds nothing to it.

State on 2026-11-24: steps 1 (moderation), 2 (More tab, activity log, settings), 6 (watching
streams, without the system's picture-in-picture window) and 7 (sharing the phone camera) are
built on `feat/ios-backlog`, each read by a review round whose confirmed findings are fixed. The
real camera and a real stream are device checks (step 12). Everything else is open.

## Starting point

- `feat/first-version` at `b286b2c` builds on a Mac with `scripts/build-ios.sh` (Xcode 26.6,
  simulator "iPhone 17", iOS 26.5). The app starts in the simulator against the fake engine.
- The test group `output-settings`, red in CI for that commit, passed three times in a row
  locally (17 of 17 tests each). The CI failure is a tap on the voice bar that does not open the
  voice view on a slow runner; the app itself is not broken. See step 0.
- Every Android reference the backlog was waiting for exists now: public servers (`525a028`),
  moderation (`1218b43`), activity log and settings (`fcceffa`), profile and themes (`4d3e6d3`),
  languages (`00314a8`), watching streams (`6b3821d`), camera sharing (`c88cfd5`), About and
  update check (`423bf93`), crash reports (`25e177f`), tablet layout (`204caa1`), accessibility
  (`8d4fc2a`).

## How the work is organised

- **Where:** a fork (`avkruedener/mumcord-mobile`), one branch `feat/ios-backlog` cut from
  `feat/first-version`. Nothing is pushed to the Mumcord repository and nothing is posted there
  until the product manager says so.
- **Shape:** one step of this plan is one short series of small commits (Conventional Commits,
  no AI attribution), so a maintainer can take a single step with `git cherry-pick` or the whole
  branch with one merge. The branch is rebased onto `feat/first-version` whenever that moves.
- **Rule for every step:** the Android implementation is the reference. Read its commit, mirror
  rules, states and wording (UI texts verbatim), and list every intended difference.
- **One fresh agent per step**, with a self-contained brief; steps that touch the same files
  (`SessionReducer.swift`, `Strings.swift`, the More tab) run one after the other.

### Done means (every step)

1. Behaviour matches the specification and the Android reference; differences are written down.
2. Unit tests for new logic, UI flow tests against the fake engine, and dark and light
   screenshots in `docs/images/screenshots/` (through the screenshot tests, shrunk PNGs).
3. Every new UI test class is listed in a test group in `scripts/build-ios.sh`.
4. `scripts/build-ios.sh --test` is green in the local simulator.
5. `CHANGELOG.md` has an entry under `[Unreleased]`; `docs/ios-backlog.md` drops what is done;
   `docs/acceptance.md` gets its iOS column entries.
6. New source files carry the SPDX header. No secrets, no contact with public servers.
7. The build cache of the agent is deleted when the step ends.

## Steps

Sizes are the size of the Android reference (changed lines under `android/`), as a rough guide.

| # | Step | Task | Reference | Size | Depends on |
|---|------|------|-----------|------|------------|
| 0 | Stable ground: the wobbly voice-bar tap, simulator choice | CI notes | – | small | – |
| 1 | Moderation in the person sheet | 8.6 | `1218b43` | 1,800 | – |
| 2 | More tab, activity log, full settings | 8.6 | `fcceffa` | 2,000 | – |
| 3 | Profile, appearance and themes | 8.6 | `4d3e6d3` | 3,300 | 2 |
| 4 | Public server browser | 2.10 | `525a028` | 1,900 | – |
| 5 | Translations and plural rules | 8.1, 8.3 | `00314a8` | script + generated files | 1–4 |
| 6 | Watching screen shares and cameras | 7.5 | `6b3821d` | 3,100 | – |
| 7 | Sharing the phone camera | 7.5 | `c88cfd5` | 3,000 | 6 |
| 8 | About, licences, update notice, crash reports | 10.3 (part) | `423bf93`, `25e177f` | 3,000 | 2 |
| 9 | iPad layout | 9.2 | `204caa1` | 1,500 | 1–8 |
| 10 | Accessibility pass | 9.4 | `8d4fc2a` | 700 | 9 |
| 11 | End-to-end tests against the local test server | 10.4 | `e2e/run.sh` | medium | 1–8 |
| 12 | Real iPhone: device build and device checklist | 10.3 (part) | `device-checklist.md` | small | 1–8 |
| 13 | TestFlight release | 10.3 | `release.yml` | medium | 12, Apple account |

### 0 – Stable ground

- The voice-bar tap that opens the voice view is used in four test files
  (`OutputFlowUITests`, `VoiceFlowUITests`, `PhoneScreenshotUITests`, `VoiceScreenshotUITests`).
  One shared helper waits until the bar has stopped moving before it taps.
- `scripts/build-ios.sh` picks or creates an available iPhone simulator instead of failing when
  "iPhone 17" is missing (open CI note).
- Can only be proven on GitHub's runners: needs CI runs in the fork. Optional for our own work,
  because we test locally; useful for the maintainer.

### 1 – Moderation

Person sheet section with Move to channel, Server mute, Kick and Ban, each only with the right
the server grants; the ban sheet with reason, length, certificate ban and the conflict cases; the
debug-only ban length override for tests.

### 2 – More tab, activity log, settings

More with Activity (count of new entries), Identities, Blocked people, Settings, Disconnect;
"Show sender only" moves to Settings → Notifications; activity log with groups and filters;
the full settings (Voice, Mobile data, Battery, Notifications, Chat). `SessionReducer.swift`
handles `ActivityAdded` and `AppSettingsChanged`.

### 3 – Profile, appearance and themes

Profile card and Edit profile (photo re-encoded without metadata, comment with the server's
limit); Themes screen with System / Dark / Light, built-in and own themes, "Paste theme code";
colours switch at runtime, including the per-server theme. `SessionReducer.swift` handles
`ProfileChanged` and `ThemesChanged`.

### 4 – Public server browser

Entry points, the "Public servers" screen with search, country chips, refresh and all states;
"Add" and "Connect"; pings only for rows on screen, four at a time, stopped in the background.
Tests against a fake list only. The screen has no mockup: by the product manager's decision the
iOS screen is built like the Android one and reviewed from the screenshots.

### 5 – Translations and plural rules

`scripts/translations.py` writes `<language>.lproj/Localizable.strings` and
`Localizable.stringsdict` from the same sources as Android; the Swift code uses the
`.stringsdict` plurals instead of the English rule; the app follows the per-app language of the
system settings and saves it as the shared `language` setting. Placed after steps 1–4 so their
texts are covered in one go; later steps re-run the script.

### 6 and 7 – Streams and camera

Watching: decoded I420 frames from the bridge's pull function into a Metal texture; nothing
camera-related loads before the user taps a camera (AC-5.23); the question before a direct
connection. Sharing: the phone camera through the engine, with the system's permission flow.
The tests use the app's drawn camera, never the simulator's (which films nothing a test can
check, where it has one at all); the real camera is part of step 12.

### 8 – About, licences, update notice, crash reports

About screen with engine version and third-party licences. **Open question:** the Android update
notice points to the APK on GitHub; iOS updates through TestFlight, so the notice probably does
not apply. Opt-in crash reports exist on Android but are not in the iOS backlog. Both are
decided by the specification before this step starts; unclear points go to the product manager.

### 9 and 10 – iPad and accessibility

Two-column layout with a sidebar on iPad after the Android tablet layout; then VoiceOver labels
and order, announcements while talking, Dynamic Type at 200 %, reduced motion, 44 pt targets.
Last among the screens, so every screen exists when they run.

### 11 – End-to-end tests

The simulator half of `e2e/run.sh`: the same scenarios against the local test server on
`localhost`, and the iOS column of `docs/acceptance.md`. Needs Docker running on the Mac (not set
up yet).

### 12 – Real iPhone

Link and run the app on a real iPhone for the first time (CI only builds for the simulator),
then walk through `docs/device-checklist.md`: background voice as a call, lock screen, headsets
(the open decision on `endRequestMeans`), camera, QR transfer, battery and data. Needs the
product manager's iPhone; a free Apple ID is enough for a personal build.

### 13 – TestFlight

Signed build and upload from a macOS runner. **Blocked on the Apple Developer account:** signing
certificate, provisioning profile and App Store Connect API key as GitHub secrets in a protected
environment, created by the product manager together with the maintainer. Never in the repo.

## What can run side by side

Each parallel agent needs its own clone, its own simulator and its own build folder (about 9 GB
each). Two at a time is the sensible limit on one Mac.

- Lane A (More tab and reducer): 2 → 3 → 8
- Lane B (independent screens): 1 → 4 → 6 → 7
- Then, one after the other: 5 → 9 → 10 → 11 → 12 → 13

## Open points carried from the backlog

- Missing tests in Messages (unblock paths, "Show sender only", `keepDraft: false`,
  `AppModel.openChat`): added with step 2, which moves that switch anyway.
- Screenshots are large (about 37 MB for Messages alone): shrink them once, before step 1 adds
  more.
- Wording for product review: the notification explanation and the camera permission text.
- The QR transfer test with real transfer parts (`e2e/transfer-maker`): with step 11.

## Decisions needed from the product manager

1. Public server browser: decided on 2026-11-24. The Android layout is the design; no mockup
   first. The product manager reviews the result from the screenshots.
2. Update notice and crash reports on iOS (step 8): in or out for the first version?
3. Step 0: allow CI runs in the fork to prove the test fix?
4. Apple Developer account for TestFlight (step 13): whose account, and when?
