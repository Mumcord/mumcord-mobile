# iOS backlog

iOS work is paused since 2026-11-15 so that Android can be finished first. This file lists
everything the iOS app still needs to reach the same state as Android, plus the open points
found while building iOS so far. Task numbers refer to `specs/05-mobile-app-tasks.md` in the
Mumcord repository; FR and AC numbers to `specs/05-mobile-app-specification.md` there.

The general rule for every item: the Android implementation is the reference. Read its commit,
mirror the rules, states and wording (UI texts copied verbatim), and list any intended
difference in the pull request.

## Why iOS is slower to build

- iOS can only be built and tested on a Mac. The development machine runs Linux, so every iOS
  change is checked by GitHub's macOS runners: about 20–40 minutes per round plus queue time.
- Speeding this up needs a Mac: either a self-hosted GitHub runner on an own Mac or a rented
  cloud Mac. With that, iOS checks take minutes and two iOS tasks can run side by side.

## State when paused

Built on iOS and green in CI: server list and connecting (phase 2 without public servers),
voice (phase 3), background voice as a CallKit call with fallback and the "session ended"
notice (4.5 first half), audio output choice and headset handling, network changes, mobile
data switches and idle disconnect with the minimal Settings screen (4.5 second half), channel
chat (5.4 first half), Messages tab with message requests, blocking and notifications (5.4
second half), app icon.

Identities and "Scan from desktop app" (6.5): built and pushed (commits `1666543`, `bb58722`,
`32e72eb`); see the last CI iOS run on `feat/first-version` for its final state.

## Open tasks

### 2.10 Public server browser (FR-5.9, AC-5.38)

Mirror Android commit `525a028` (`feat(android): browse public servers`):

- Entry points: a "Find more" section below Favourites with "Browse public servers", and the
  same as a button in the empty server list. No "Scan an invite code" row (that feature does
  not exist).
- Screen "Public servers": search (name, address or country), country chips, header
  "N of M servers" with Refresh, rows with name, "address · country" and "● users / max · ms"
  or "No answer". States: loading, first load failed (with "Try again"), refresh failed (last
  list kept), empty, no match.
- "Add" saves the server with the default identity's name, then the row shows "In your
  servers" and "Connect", which runs the normal connect flow.
- Pings: only rows on screen, at most 4 at a time, once per load and again after Refresh,
  stopped when the browser closes or the app goes to the background (FR-5.43).
- Tests only against a fake list; never contact the real list service or public servers.
  The browser screen has no mockup and needs product review.

### 8.6 Moderation, More tab, activity log, settings, profile and themes (FR-5.80 … FR-5.100)

Task 8.6 is the iOS scope of the Android tasks 8.3, 8.4 and 8.5.

Moderation, mirroring Android commit `1218b43` (`feat(android): moderation in the person sheet`):

- A "Moderation" section at the end of the person sheet with Move to channel…, Server mute
  ("Remove server mute" when muted), Kick… and Ban…, each only when the server grants that
  right: move and mute by the rights in the person's channel, kick and ban by the root
  channel. No section for yourself; no mute in a temporary channel.
- Every step returns to the person sheet. Move and mute act at once; a refusal appears in the
  existing refusal banner.
- Ban sheet: reason, length (1 hour, 1 day, 7 days, 30 days, permanent) with the end date, ban
  by certificate (only if the person has one), "Remove from server now" (only with the kick
  right), "Kick only instead"; the server's default-ban case and the "changed meanwhile"
  conflict. A standard server always bans the address too, which the sheet says in text.
- A debug-only override of the ban length for tests.

More tab, activity log and settings, mirroring Android commit `fcceffa`
(`feat(android): activity log and the full settings under More`):

- More: Activity (with a count of new entries), Identities, Blocked people, Settings,
  Disconnect.
- "Show sender only" moves from More to Settings → Notifications.
- Activity log: groups Today / Yesterday / date, filters All, Joins and Streams (no Moderation
  filter until the engine reports who kicked or banned), updates live, empty states.
- Settings: Voice (talk mode sheet, noise suppression, microphone level, default audio output
  only where an earpiece exists), Mobile data, Battery, Notifications (private messages,
  mentions, "Show sender only", note "only while connected"), Chat (delete history older than
  Never / 7 / 30 / 90 / 365 days).
- `ios/Mumcord/Session/SessionReducer.swift` ignores `ActivityAdded` and `AppSettingsChanged`
  today; they must be handled as the Android reducer does.

Profile, appearance and themes, mirroring the Android task 8.3 commit (in progress on Android
when iOS was paused):

- Profile card at the top of More with "Edit profile"; Edit profile with photo (choose, take,
  remove), read-only name, comment with the server's length limit. Profile photos must not
  carry location or other metadata to the server (re-encode before sending).
- Themes screen with System / Dark / Light, the built-in themes, own themes, "Paste theme
  code" with preview, share and delete. There is no server-offered theme ("Use server theme
  when offered" does not exist); the only per-server theme is the one the user picks in the
  edit-server form.
- The app's colours switch at runtime to the chosen theme, including the per-server theme
  while connected to that server.
- `SessionReducer.swift` ignores `ProfileChanged` and `ThemesChanged` today.

Translations (task 8.1) and the language override of task 8.3 are done on Android only. For iOS:

- Extend `scripts/translations.py` to write `<language>.lproj/Localizable.strings` from the same
  sources (keys equal the Android names; `%1$s` becomes `%1$@`, `%1$d` stays) and a
  `Localizable.stringsdict` for the plurals.
- The Swift code picks `.one` and `.other` keys by the English rule today; it must use the
  `.stringsdict` plurals instead, or Polish, Czech, Irish and the other languages with more
  plural forms show wrong texts.
- The language override: iOS offers the per-app language in the system settings; the app
  follows it and saves it as the shared `language` setting.

### 7.5 Streams and camera (FR-5.70 … FR-5.76)

Waits for the Android tasks 7.3 (watching streams) and 7.4 (camera sharing). Notes:

- The video codecs are already cross-built for iOS device and simulator
  (`scripts/build-codecs.sh`, task 7.1).
- The bridge (task 7.2) hands decoded frames as I420 through a signal plus a C pull function
  (no bytes through UniFFI); iOS uploads the planes into a Metal texture.
- Nothing camera-related may load before the user taps a camera (AC-5.23); a direct
  connection reveals the IP address to the other person, so the app asks first.

### 9.2 Tablet layout and 9.4 accessibility

iPad layout after the Android tablet layout (9.1); accessibility pass (VoiceOver labels and
order, talking announcements, Dynamic Type at 200 %, reduced motion, 44 pt targets) after 9.3.

### 10.3 Release: TestFlight and About screen (FR-5.102)

Signed build on a macOS runner, TestFlight upload, About screen with engine version and
licences. Needs the Apple Developer account's signing certificate, provisioning profile and an
App Store Connect API key as GitHub secrets in a protected environment (like
`android-release`), created by the product manager. CI so far only builds and tests the
simulator app; linking for a real device is first proven here.

## Open points from the iOS work so far

- Tests missing in the Messages feature: the person row turning to "Unblock" after blocking,
  unblocking from the blocked list, the "Show sender only" switch changing its value,
  `keepDraft: false`, `AppModel.openChat`.
- Screenshots are large (about 37 MB for the Messages set alone); shrink the PNGs.
- Wording needing product review: the notification explanation sheet ("Mumcord shows private
  messages and mentions as notifications while you are connected.") and the camera permission
  text ("Mumcord uses the camera to read the codes shown by the desktop app and to take photos
  you choose to send in a chat.").
- Headset behaviour differs from Android on purpose: on iOS the system's End button always
  leaves the session, only the CallKit mute action toggles mute. Wired EarPods' single press
  therefore leaves. Decide after trying real headsets (one function, `endRequestMeans`).
- A test with real QR transfer parts (host tool `e2e/transfer-maker`) is not run on iOS.
- There are no iOS end-to-end tests against the local test server yet (task 10.4): the
  simulator half of `e2e/run.sh` (the same scenarios against the test server on `localhost`, run
  in the iOS CI job) and the iOS column of [acceptance.md](acceptance.md) are open.

## CI notes

- Runner noise seen repeatedly: a macOS runner without the "iPhone 17" simulator ("Unable to
  find a device matching …"), and `NativeAudioTests` aborting with "RPC timeout … deadlocked".
  Both pass on re-run. A fix that picks or creates an available iPhone simulator in
  `scripts/build-ios.sh` is still open.
- The first UI test of a group on a cold simulator used to time out; every test group now
  starts the app once before its tests.
- Every pushed commit gets its own complete CI run. When the macOS queue is long, cancel the
  queued iOS runs of superseded commits that do not change `ios/**`.
- SwiftUI section headers are drawn in capitals; UI tests must look for "MOBILE DATA", not
  "Mobile data".

## Real-device checks

All iOS behaviour that only a real iPhone can show is listed in the
[device checklist](device-checklist.md).
