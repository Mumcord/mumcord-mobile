# Acceptance criteria and their tests (Android)

This page shows how each acceptance criterion of the specification (`specs/05-mobile-app-specification.md`
in the Mumcord repository, section 11, AC-5.1 to AC-5.38) is checked on Android. iOS is paused
(see `ios-backlog.md`); its half of every criterion is open.

There are four kinds of proof:

- **End-to-end**: a scenario of `e2e/run.sh`. The real app with the real engine runs in the
  emulator against the local Mumble test server (Docker), with other people on the host where
  the criterion needs them: `e2e/voice-peer` (talks and listens), `e2e/chat-peer` (writes as the
  desktop app does), `e2e/stream-peer` (shares a screen), and small Python clients
  (`e2e/*.py`) that report what the server did. The tests live in `android/e2e`.
- **Instrumented**: an `android/app` test in the emulator with a fake engine. It checks the
  screens and what the app asks of the engine, without a server.
- **Unit**: a JVM test in `android/app/src/test` or a Rust test of the bridge.
- **Manual**: on a real phone, from the device checklist (`device-checklist.md`), because the
  emulator cannot do it.

All end-to-end scenarios and instrumented tests run in CI on every push to a pull request
(`.github/workflows/ci-android.yml`, job "Emulator tests"); see "Continuous integration" in
`development.md`.

## Summary

| Kind | Criteria |
|---|---|
| End-to-end, some with instrumented tests for a part (AC-5.4's password channel is instrumented only) | 27 |
| Instrumented or unit tests only | 2 (AC-5.20, AC-5.32) |
| Automated as far as the emulator allows, the rest manual | 6 (AC-5.8, AC-5.10, AC-5.11, AC-5.12, AC-5.24, AC-5.33) |
| Checked elsewhere (release check V10, desktop CI) | 3 (AC-5.34, AC-5.35, AC-5.36) |

## Criteria

| AC | What | Proof on Android |
|---|---|---|
| AC-5.1 | First start: identity, talk mode with nothing chosen, empty server list | End-to-end `IdentityFlowTest`, `ConnectFlowTest` (every scenario's first start checks that nothing is chosen); instrumented `IdentitiesUiFlowTest`, `AppFlowTest` |
| AC-5.2 | Add the server, unknown certificate prompt, channel tree | End-to-end `ConnectFlowTest`; the other person in the tree in `VoiceFlowTest` and the other voice scenarios |
| AC-5.3 | Changed server certificate: strong warning, nothing connects without consent | End-to-end `CertificateFlowTest` (the test server is restarted with a second certificate); instrumented `AppFlowTest.aChangedCertificateWarnsStronglyAndNotTrustingItEndsInAnErrorWithRetry` |
| AC-5.4 | Joining another channel; password-protected channel | Joining: end-to-end `ConnectFlowTest`, the other person sees it in `ModerationFlowTest` and `ActivityFlowTest`. Password channel: instrumented `AppFlowTest.aLockedChannelAsksForItsPasswordAndARefusalShowsABanner` only, because the stock test server has no channel with a password and setting one up would change the server's configuration |
| AC-5.5 | Voice activity in both directions, talking ring | End-to-end `VoiceFlowTest` (the other person hears the phone, the phone plays the other person); talking ring: instrumented `VoiceUiFlowTest` (own) and unit `ConnectedStateTest` (others) |
| AC-5.6 | Push-to-talk, stops within 200 ms | End-to-end `PushToTalkFlowTest` (CI allows 300 ms because the shared runner adds stalls; see the workflow) |
| AC-5.7 | Mute, deafen and leave from the voice bar and the voice view | End-to-end `MuteDeafenFlowTest`; instrumented `VoiceUiFlowTest` |
| AC-5.8 | Voice keeps working while locked (30 min); push-to-talk silent while locked | End-to-end `BackgroundVoiceFlowTest` (3 min and 1 min locked). Manual: the full 30 minutes on a real phone |
| AC-5.9 | The session is a call, its controls work, no recent call | End-to-end `CallNotificationFlowTest`; instrumented `CallSessionTest` |
| AC-5.10 | A real phone call mutes and deafens, its end restores | End-to-end `PhoneCallFlowTest` with the emulator's simulated phone network. Manual: a real call |
| AC-5.11 | Loudspeaker or earpiece question, Bluetooth headset, output sheet | End-to-end `AudioOutputFlowTest` (what the emulator's single loudspeaker allows); instrumented `OutputUiFlowTest`. Manual: earpiece and real headsets, which the emulator does not have |
| AC-5.12 | Headset button toggles the microphone | End-to-end `AudioOutputFlowTest` (the key a headset sends, in the background and with the screen off). Manual: a real headset and the feedback sound |
| AC-5.13 | Wi-Fi to mobile data reconnects into the same channel and mute state | End-to-end `NetworkFlowTest` |
| AC-5.14 | Person sheet: volume and "Mute for me" only on the phone | End-to-end `PersonSheetFlowTest` (the phone plays the other person more quietly and then not at all; a third person keeps hearing them); instrumented `AppFlowTest` |
| AC-5.15 | Whisper reaches only the chosen person | End-to-end `WhisperFlowTest`; instrumented `VoiceUiFlowTest` |
| AC-5.16 | Chat: text, link, image, GIF link both ways, mention highlighted | End-to-end `ChatFlowTest`; instrumented `ChatUiFlowTest` |
| AC-5.17 | Script and markup from the server shown harmlessly | End-to-end `ChatFlowTest` (`e2e/raw-chat.py` sends raw markup); instrumented `ChatUiFlowTest` |
| AC-5.18 | Private message notification, "Show sender only", nothing after disconnecting | End-to-end `MessagesFlowTest`; instrumented `MessagesUiFlowTest`, `MessageNotifierTest` |
| AC-5.19 | Chat history after restarting the app | End-to-end `ChatFlowTest.theHistoryIsStillThereAfterTheAppWasKilledAndStartedAgain` |
| AC-5.20 | `.p12` import, the server recognises the same user | Instrumented `IdentitiesUiFlowTest.anIdentityFileIsImportedWithItsPasswordThenMadeTheDefaultAndTheOldOneDeleted`; that the server sees the same certificate is shown end-to-end for the phone's own identity (`IdentityFlowTest`). An end-to-end import is missing: it needs the system's file picker, which the tests cannot drive reliably |
| AC-5.21 | "Send to your phone": scan, code, wrong codes, expiry | End-to-end `TransferFlowTest` (the QR texts come through the debug build's test source instead of the camera); instrumented `IdentitiesUiFlowTest` |
| AC-5.22 | Screen share tile, full screen, landscape, mini window, chat usable | End-to-end `StreamFlowTest`; instrumented `StreamsUiFlowTest` |
| AC-5.23 | Mobile data: tap-to-watch loads nothing before the tap; autoplay setting | End-to-end `StreamFlowTest` (tap-to-watch, the sharer counts no viewer before the tap); autoplay: instrumented `StreamsUiFlowTest`, `SettingsUiFlowTest` |
| AC-5.24 | Camera sharing after the preview, front and back, "paused" in the background | End-to-end `CameraFlowTest`: a test pattern camera goes through the app's camera input, the engine's encoder and a direct connection to `e2e/stream-peer --viewer`, which sees nothing from the preview, the front and then the back pattern, no picture while the app is in the background and pictures again after it returns; instrumented `CameraShareUiFlowTest` and `CameraXSourceTest` (the real camera is released when the app leaves the screen). Not met: the desktop cannot show "paused" – the engine has no paused signal for cameras, so it shows the last picture. Manual: a real phone against the desktop app |
| AC-5.25 | Comment and avatar reach the desktop | End-to-end `ProfileThemeFlowTest` (`e2e/profile-watch.py` sees both, the avatar without EXIF data) |
| AC-5.26 | Desktop theme code, dark and light, System follows the phone | End-to-end `ProfileThemeFlowTest`; instrumented `ProfileThemesUiFlowTest` |
| AC-5.27 | Admin: move, server mute, kick, ban; no moderation for a normal user | End-to-end `ModerationFlowTest` (move, kick, ban as the test server's SuperUser); server mute and the normal user: instrumented `ModerationUiFlowTest` |
| AC-5.28 | Activity log with join, leave, move, kick and filters | End-to-end `ActivityFlowTest` (join, move, leave and filters); kicks: instrumented `ActivityUiFlowTest`, because the bridge reports a kick of somebody else as leaving |
| AC-5.29 | `mumble://` link shows the target and connects after confirmation | End-to-end `LinkFlowTest` (the system opens the link in the running app); instrumented `AppFlowTest.aLinkShowsItsTargetAndConnectsOnlyAfterConfirmationAlsoWhenConnectedElsewhere` |
| AC-5.30 | Disconnect when alone: warning, "Stay connected", disconnect | End-to-end `IdleDisconnectFlowTest` (shortened times, also in Doze); instrumented `SettingsUiFlowTest`, `IdleNotifierTest` |
| AC-5.31 | Tablet: two columns in portrait and landscape; a stream fills the right column with the chat strip below | End-to-end `TabletFlowTest` (the emulator's screen is made a tablet's, 800 x 1280 dp, with `wm size` and `wm density` and put back afterwards: channels and chat side by side, a message sent, the share of `e2e/stream-peer` playing in the right column with the chat strip below and the channels still visible); instrumented `TabletUiFlowTest` (landscape and portrait at forced tablet sizes, the full screen button and back, the mini window, Messages in the right column, and resizing between phone and tablet keeping the tab, the draft and the stream); unit `ConnectedLayoutTest`; screenshots `android-tablet-*` in landscape and portrait |
| AC-5.32 | A desktop file transfer is only a hint line | Unit tests of the session and chat state (`SessionReducerTest`, `ChatStateTest`, `ConnectedStateTest`). An end-to-end scenario is missing: the helpers on the host cannot start a desktop file transfer yet |
| AC-5.33 | 200 % font size and TalkBack | Every screenshot screen (phone, tablet portrait and landscape, dark and light) runs Google's Accessibility Test Framework in the JVM tests (`checkAccessibility` in `Screenshots.kt`): missing labels, touch targets under 48 dp, text and image contrast, duplicate or redundant labels and traversal order fail the build. Screenshots at 200 % font size (`android-large-text-*`: server list, server form, channels with the voice bar, voice view, chat, Messages) are compared on every run. Talking announcements: `TalkingAnnouncerTest`, `TalkingAnnouncementsTest`. Instrumented checks at twice the font size (`IdentitiesUiFlowTest`, `PublicServersUiFlowTest`) and screen-reader labels in the instrumented tests. Manual: the TalkBack pass on the device checklist (section 10) |
| AC-5.34 | Update over the old app keeps data; update notice | Update notice: instrumented `AboutUiFlowTest`. Installing a newer signed APK over the app: release check V10 and the device checklist, because it needs two signed release builds |
| AC-5.35 | Tagged release produces the signed APK with checksum | Release check V10 (the release workflow, not the app) |
| AC-5.36 | Desktop regression | The desktop build and its end-to-end tests in the Mumcord repository's CI |
| AC-5.37 | Local network permission: explanation, system prompt, error with the way to settings | End-to-end `LocalNetworkFlowTest`; instrumented `AppFlowTest` |
| AC-5.38 | Public servers from a local list: filters, ping, add, refresh keeps the list | End-to-end `PublicServersFlowTest` (the list is served inside the emulator, never the real list service); instrumented `PublicServersUiFlowTest` |

## iOS so far

The iOS half is checked in the simulator against the fake engine; the end-to-end scenarios
against the local test server are open for iOS (task 10.4).

| Criterion | iOS tests |
|---|---|
| AC-5.22 | UI flow `StreamsFlowUITests` (tile, full screen, landscape button, mini window, stop; the picture's colours are checked on screen), unit `StreamsControllerTests`. Not yet: a real share from the desktop app |
| AC-5.23 | Unit `StreamsStateTests`, `StreamsControllerTests` (nothing is watched before the tap on mobile data, also for a camera; "Start streams automatically" starts a visible tile); UI flow `StreamsFlowUITests` (no picture before the tap) |
| AC-5.24 | Unit `CameraShareControllerTests` (the preview sends nothing, front and back, the camera released in the background and back with the same camera on return while sharing goes on, cancel and stop, a refused start, a camera that stops by itself, leaving the channel or the server); UI flow `CameraShareFlowUITests` (camera question, preview with the drawn camera, switch, start, viewers, the app in the background and back, stop). Not yet: the real camera, and the desktop seeing it, on a real iPhone |
| AC-5.25 | Unit `ProfileThemesTests` (comment and picture saved, only what changed, a failed save), `AvatarImageTests` (the picture upright and without location or camera data); UI flow `ProfileThemesFlowUITests`. Not yet: the desktop seeing both (end-to-end, task 10.4) and the real camera on a real iPhone |
| AC-5.26 | Unit `AppThemeTests`, `ProfileThemesTests` (paste, preview, apply, the server's own theme); UI flow `ProfileThemesFlowUITests` (System, Dark and Light and a theme drawn on screen, checked by its colours). Not yet: a code made by the desktop app (the fake engine reads its own codes only, end-to-end task 10.4) |
| FR-5.82 (no criterion of its own) | Script tests `scripts/test_translations.py` (the iOS files, placeholders, plurals, what stays English); unit `StringsPluralTests` (English, German, Polish and Czech plural forms, the count apart from the arguments), `AppLanguagesTests` (the 24 languages in the app, the system's language for the app), `MoreControllerTests` (the system's language saved as the shared setting, following the phone clears it); UI flow `LanguageFlowUITests` (the server list in German, the language row opens the system settings), screenshots `ios-lang-de-*`. Not yet: choosing a language in the real system settings, which the tests cannot drive |
| AC-5.31 | On an iPad simulator (iPad Air 11-inch, the mockups' 1180 x 820 points; test group `tablet`): UI flow `TabletFlowUITests` (channels and chat side by side in landscape, no tab bar, the voice bar under the channels; a stream in the right column with the chat strip below, its tile gone from the tree, the full screen button and "Leave full screen"; portrait keeps both columns; the mini window button and back; Messages in the right column with the stream as the mini window, whose expand button returns to Channels; a sheet at most 480 points wide in the middle; a window as narrow as half a Split View laid out like a phone, and turning the iPad between that and two columns keeping the Chat tab, the draft and the stream); unit `ConnectedLayoutTests` (the 600 x 480 point rule for iPhones, iPads and narrow windows, the Chat tab as part of Channels, the chat counting as read beside the channels, where a stream is shown, the strip's two newest messages), `StreamsControllerTests` (the whole screen and back to the column, the sharer's channel); screenshots `ios-tablet-*` in landscape and portrait. Not yet: a real iPad, real Split View and Stage Manager windows (the tests narrow the window with the debug option `-mumcordSplitWidth`), the end-to-end half with a real share |
| AC-5.38 | Unit `PublicServersStateTests` (search by name, address and country, the country filter, the rows and their saved server), `PublicPingSchedulerTests` (only rows on screen, four at a time, once per load, nothing while stopped), `PublicServersControllerTests` (nothing before the browser opens, the list read in pages, a failed first load and "Try again", a failed refresh keeps the list, adding with the identity's name, connecting through the normal flow); UI flow `PublicServersFlowUITests` against a made-up list in the fake engine (nothing requested before opening, search and country narrow the list, ping lines, add and connect, "Try again", a failed refresh keeps the list, no pings after leaving the browser or while in the background). Not yet: the end-to-end scenario with a locally served list and the local test server (task 10.4) |

## Deviation from the task plan

Task 10.4 names a separate workflow `.github/workflows/e2e.yml`. The end-to-end run stays in the
"Emulator tests" job of `ci-android.yml` instead: that job already builds the app and starts the
emulator for the instrumented tests, so a separate workflow would build and boot everything a
second time. The run is part of every pull request's checks either way.
