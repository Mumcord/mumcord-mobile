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

Moderation in the person sheet (the moderation part of 8.6, mirroring Android commit `1218b43`):
built on a Mac and checked in the simulator against the fake engine. The "Moderation" section
with Move to channel…, Server mute, Kick… and Ban…, each only with the server's right; the
channel picker, the kick question and the ban sheet with the server's default ban and the
"changed meanwhile" conflict. Unit tests `ModerationTests` and `AppModelModerationTests`, UI
tests `ModerationFlowUITests`, screenshots `ios-moderation-*`. A debug build started with
`-mumcordBanSeconds <seconds>` bans for that long instead of the chosen length, for the
end-to-end test that is still open (task 10.4).

More tab, activity log and settings (8.6, the scope of the Android task 8.5): built after the
pause, mirroring Android commit `fcceffa`. More has Activity (with a count of new entries),
Identities, Blocked people, Settings and Disconnect; the activity log has the days, the filters
All, Joins and Streams and its empty states; Settings have Voice, Mobile data, Battery,
Notifications (with "Show sender only", which left More) and Chat. Differences from Android:
"Default audio output" is hidden while a wired headset is plugged in, because iOS then does not
offer the earpiece; the whole settings do not fit one picture, so the screenshots show them in
two parts.

Profile, appearance and themes (the profile and theme part of 8.6, mirroring Android commit
`4d3e6d3`): built and checked in the simulator against the fake engine. More shows the own
profile with "Edit profile" and a Themes row with the app theme's name; Edit profile has the
picture (choose, take, remove), the name that is set per server, the comment with the server's
limit and its counter, "How others see you", and Save, which sends only what changed (the picture
first; a saved picture is not sent again after a failed comment). Every chosen or taken picture
is decoded upright and written again as a PNG with nothing but its pixels, at most 512 pixels on
its longest edge (`avatarImage`), so no location or camera data leaves the phone. Themes has
System, Dark and Light, the built-in themes and the user's own, "Paste theme code" with the
engine's preview in both versions and "Apply theme", and on a long press (or VoiceOver's actions)
Share through the system's share sheet and Delete for an own theme. The edit-server form picks the
server's theme. The app's colours come from one place (`AppThemeHost` on the app's root, fed by
`AppThemeController`), so every screen switches at once, to the server's own theme while it is
shown; the built-in Mumcord theme gives exactly the design tokens' colours, so the app looks as
before in it. Unit tests `AppThemeTests`, `ProfileStateTests`, `ProfileThemesTests` and
`AvatarImageTests`, UI tests `ProfileThemesFlowUITests` (which also check the drawn colours on
screen), screenshots `ios-profile-*`, `ios-themes-*` and `ios-server-theme-*`. As on Android, the
status bar, the keyboard and the system's own sheets follow the brightness of the drawn colours
in all three modes; the phone's own setting is read from the window, which the app's setting does
not reach, so System follows the phone live (`ProfileThemesFlowUITests` switches the simulator
between light and dark). Differences from Android: a colour value from the engine that is not `#rrggbb` keeps the app's own
colour instead of stopping the app; the screenshots also show the long-press sheet and the delete
question, and the paste sheet with its keyboard. The camera for the profile picture is the
system's camera, as for the chat, and is a device check.

Watching screen shares and cameras (the watching half of 7.5, mirroring Android commit
`6b3821d`): built and checked in the simulator against the fake engine, whose pictures are made in
the app and drawn through the real Metal path. Live tile under the sharer, "Tap to watch" on mobile
data, full screen with volume, landscape button and stop, mini window, unreachable states with
"Try again", the direct connection question. Unit tests `StreamsStateTests` and
`StreamsControllerTests`, UI tests `StreamsFlowUITests`, screenshots `ios-streams-*`. Differences
from Android: no system picture-in-picture (FR-5.76), see the open points.

Sharing the phone camera (the sharing half of 7.5, mirroring Android commit `c88cfd5`): built and
checked in the simulator against the fake engine and a drawn camera. The voice view's camera
button explains the camera before iOS asks (or points to the system settings after a refusal),
the sheet "Share your camera" shows the picture with the front camera and the switch to the back
camera, nothing is sent before "Start sharing", the "Camera on · n watching" bar with the switch
and Stop sits above the voice bar and the voice view's buttons, a refused start and a camera that
stops by itself say why. The camera is released while the app is in the background or another
sheet takes the preview's place, and back on return; sharing and voice go on. Cancel and Stop
close the camera at once, also while "Start sharing" still waits for the engine, and the camera
closes whenever the app's state says off. Capture with AVFoundation (`ios/Platform/Camera`),
frames handed to the bridge's C function in place, interruptions followed. Unit tests
`CameraShareControllerTests`, `CameraFramesTests` and `CameraSessionWatchTests`, UI tests
`CameraShareFlowUITests`, screenshots `ios-camera-*`. Differences from Android: the camera is
asked for 1280 x 720 (the smallest 16:9 size every iPhone camera offers; the bridge scales it
down to 640 x 360) instead of 640 x 360; "on screen" is every state but the background, as for
watching streams, so the camera still runs while Control Centre or the app switcher covers the
app; there is no picture-in-picture window to pause for; the end-to-end test pattern camera of
Android (`TestSettings.cameraSource`) waits for the iOS end-to-end tests (task 10.4).

Public server browser (2.10, FR-5.9, mirroring Android commit `525a028`): built and checked in
the simulator against the fake engine and a made-up list (`PublicServersMockup`); neither the real
list service nor a listed server is contacted by any test. "Find more" with "Browse public
servers" below Favourites and as a button on the empty list; the "Public servers" screen with
search by name, address or country, country chips, "N of M servers" with Refresh, the rows with
name, "address · country" and the ping line or "No answer", the states loading, failed with "Try
again", refresh failed (the last list kept), empty and no match; "Add" saves the server with the
default identity's name and the row then offers "Connect", which closes the browser and runs the
normal connect flow. Only the rows on screen are pinged, at most four at a time, once per load
and again after Refresh, and not while the browser is closed or the app is in the background.
Unit tests `PublicServersStateTests`, `PublicPingSchedulerTests` and
`PublicServersControllerTests`, UI tests `PublicServersFlowUITests`, screenshots
`ios-public-servers-*`. The screen has no mockup: it follows the Android screen and waits for the
product manager's review of the screenshots. Differences from Android: which rows are on screen
comes from their frames in the list (SwiftUI has no list of the visible rows); the pings pause
when the app goes to the background, not already when Control Centre or the app switcher covers
it, as for streams and the camera; a ping on its way when the browser closes or the app leaves
cannot be called back in the engine, so its answer is dropped and the server is asked again
later; what Android's screen readers hear through polite live regions (loading, the failures, an
empty list) VoiceOver hears as announcements; with the largest text sizes the rows shorten their
texts with "…" like the server list instead of wrapping them (the accessibility pass, step 10,
decides this for all lists). The end-to-end scenario with a locally served list (AC-5.38 against
the local test server) waits for the iOS end-to-end tests (task 10.4).

Settings from the server list (mockup `01-server-list`, specification section 5 "Mockups and
missing functions"): the settings icon on the server list's title bar, with the VoiceOver label
"Settings" and a 44 pt target, opens the More screen without a connection: a back button "Back to
the server list" and only Identities, Themes and Settings, which are the same screens as on the
More tab. Left out there because they need a server: the profile card and Edit profile (picture
and comment live on the server), Activity (the log of a connection), Blocked people (the list of
the identity used on the connection, cleared when it ends) and Disconnect. Settings leave out the
microphone level (it only moves while the voice runs) and "Default audio output" (which outputs
the phone has is only known during a voice session); every other setting is saved without a
connection. The mockup's search icon is left out: searching the list does not exist. A setting
that fails to save without a connection is only logged: the refusal banner belongs to the
connected screens and would otherwise show up on the next connection. Unit tests in
`MoreStateTests` and `MoreControllerTests`, UI tests `ServerListMoreFlowUITests`, screenshots
`ios-server-list-more-*` and `ios-server-list-settings-*`.

**Note for the maintainer: intended difference from Android.** Android has no settings icon on
the server list yet (`android/app/src/main/kotlin/app/mumcord/mobile/ui/servers/ServerListScreen.kt`),
so there Settings, Themes and Identities are only reachable through the More tab while connected.
iOS brings the icon back as the mockup shows it; Android should follow the same way.

## Open tasks

### 8.6 Translations and the language override (FR-5.82)

Task 8.6 is the iOS scope of the Android tasks 8.3, 8.4 and 8.5. Moderation (8.4), the More
tab, the activity log and the settings (8.5), and the profile and themes of 8.3 are built, see
"State when paused". What is left of 8.3 is the language.

Translations (task 8.1) and the language override of task 8.3 are done on Android only. For iOS:

- Extend `scripts/translations.py` to write `<language>.lproj/Localizable.strings` from the same
  sources (keys equal the Android names; `%1$s` becomes `%1$@`, `%1$d` stays) and a
  `Localizable.stringsdict` for the plurals.
- The Swift code picks `.one` and `.other` keys by the English rule today; it must use the
  `.stringsdict` plurals instead, or Polish, Czech, Irish and the other languages with more
  plural forms show wrong texts.
- The language override: iOS offers the per-app language in the system settings; the app
  follows it and saves it as the shared `language` setting.

### 9.2 Tablet layout and 9.4 accessibility

iPad layout after the Android tablet layout (9.1); accessibility pass (VoiceOver labels and
order, talking announcements, Dynamic Type at 200 %, reduced motion, 44 pt targets) after 9.3.
The stream full screen has no "Turn to landscape" button on an iPad so far: the app allows
iPad multitasking, so iOS ignores its wish for an orientation there. The tablet layout decides
whether a stream needs a landscape of its own on an iPad.

### 10.3 Release: TestFlight and About screen (FR-5.102)

Signed build on a macOS runner, TestFlight upload, About screen with engine version and
licences. Needs the Apple Developer account's signing certificate, provisioning profile and an
App Store Connect API key as GitHub secrets in a protected environment (like
`android-release`), created by the product manager. CI so far only builds and tests the
simulator app; linking for a real device is first proven here.

## Open points from the iOS work so far

- Screenshots are large (about 37 MB for the Messages set alone); shrink the PNGs. The sets
  of moderation, More, the activity log and the settings show how: saved with a palette of 256
  colours and no dithering (Pillow, `quantize(colors=256, method=MAXCOVERAGE, dither=NONE)`),
  a picture takes about 0.1 to 0.2 MB instead of 0.9 to 1.7 MB, and no colour is off by more
  than 7 of 255. The older sets are still as they were.
- Wording needing product review: the notification explanation sheet ("Mumcord shows private
  messages and mentions as notifications while you are connected.") and the camera permission
  text ("Mumcord uses the camera to read the codes shown by the desktop app and to take photos
  you choose to send in a chat."). That text is the one iOS shows in its own camera question, and
  it does not mention sharing the camera with the channel, which the app now also does; it was
  left as it is, because neither the specification nor Android gives a text for it.
- Product review: editing a formatted comment on the phone flattens it. The editor shows the
  comment as plain text and saves plain text (FR-5.80 says nothing about formatting), so a comment
  set on the desktop with bold text, links or a picture loses them, without a word, when the user
  fixes a typo on the phone. Android does the same (`ProfileController.open` starts from the
  comment's plain text). Decide whether the phone should warn before saving over such a comment,
  keep it read-only, or accept the loss; then change both apps together.
- The comment counter counts the typed characters, as on Android, while the engine checks the
  comment as Mumble HTML (a `<` or `&` takes four or five characters, a line break `<br>`, and
  the engine may add its status lines). A comment the counter lets through can therefore be
  refused; iOS then says "Can't save: your comment is too long for this server" instead of the
  general error. Counting what will be sent needs the bridge to say it, for both apps.
- Headset behaviour differs from Android on purpose: on iOS the system's End button always
  leaves the session, only the CallKit mute action toggles mute. Wired EarPods' single press
  therefore leaves. Decide after trying real headsets (one function, `endRequestMeans`).
- A test with real QR transfer parts (host tool `e2e/transfer-maker`) is not run on iOS.
- There are no iOS end-to-end tests against the local test server yet (task 10.4): the
  simulator half of `e2e/run.sh` (the same scenarios against the test server on `localhost`, run
  in the iOS CI job) and the iOS column of [acceptance.md](acceptance.md) are open.

- Camera sharing, still open on iOS: the real camera on a real iPhone (the picture, upright for
  both cameras, as the phone turns and right after a switch, unmirrored on the desktop, the
  camera indicator going off after Stop and in the background, a phone call, another app or
  Split View taking the camera and the picture coming back); an
  end-to-end scenario with `e2e/stream-peer --viewer` against the local test server. As on
  Android, viewers see the last picture instead of "paused" while the phone is in the background:
  the engine has no paused signal for cameras (see AC-5.24 in [acceptance.md](acceptance.md)).
- Streams, still open on iOS: the system's picture-in-picture window when leaving the app while
  watching (FR-5.76; needs `AVPictureInPictureController` with sample buffers, so the pictures
  would be copied into pixel buffers, and the decoding would go on in the background); real
  streams from the desktop app on a real iPhone (picture, sound, decoding load, rotation); an
  end-to-end scenario with `e2e/stream-peer` against the local test server. The status bar stays
  dark on the black full screen in the light theme. The tablet layout of a stream (right column,
  chat strip) comes with the iPad layout.

## CI notes

- Runner noise seen repeatedly: a macOS runner without the "iPhone 17" simulator ("Unable to
  find a device matching …"), and `NativeAudioTests` aborting with "RPC timeout … deadlocked".
  Both pass on re-run. `scripts/build-ios.sh` now picks the newest available iPhone simulator, or
  creates one, when "iPhone 17" is missing (plan step 0); not yet proven on a runner that lacks
  it. The audio test abort is still open.
- The test group `output-settings` failed in CI because a tap on the voice bar came while the bar
  was still moving in and did not open the voice view. Every test now opens the voice view through
  one helper that waits until the bar holds still and taps once more if needed (plan step 0);
  proof on GitHub's runners is still to come.
- The first UI test of a group on a cold simulator used to time out; every test group now
  starts the app once before its tests.
- On a Mac whose simulator has a camera (seen with Xcode 26.6 and the iOS 26.5 simulator),
  `ChatFlowUITests.testAddingAnImageOffersThePhotoLibraryAndTheCamera` fails: it expects the
  simulator to have no camera, and there the camera screen opens. It fails the same way on the
  commit before the More tab and moderation were built.
- Seen once in a full local run and green on the next run:
  `IdentitiesScreenshotUITests.testImportWrongPasswordDark`.
- `ProfileThemesFlowUITests.testSystemFollowsALightAndADarkPhoneAndComesBackToItAfterDark` switches
  the simulator between light and dark. A simulator that has been running for a long time can
  ignore that switch, and the test then fails with a light screen where it expects a dark one;
  after shutting the simulator down and booting it again the test passes (three of three runs).
- `DesignReferenceUITests.testComponentGalleryShowsEveryGroup` ("the gallery does not scroll to
  its last group") failed whenever its drag started on the slider, which happened every time once
  the profile picture's avatar size made the gallery taller. It now tries another starting point
  where a drag moved nothing.
- Every pushed commit gets its own complete CI run. When the macOS queue is long, cancel the
  queued iOS runs of superseded commits that do not change `ios/**`.
- SwiftUI section headers are drawn in capitals; UI tests must look for "MOBILE DATA", not
  "Mobile data".

## Real-device checks

All iOS behaviour that only a real iPhone can show is listed in the
[device checklist](device-checklist.md).
