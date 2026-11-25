# Manual device checklist

Some things only a real phone can show: real sound, real headsets, real phone calls, a real camera,
real battery use. The automated tests run in an emulator and a simulator and cannot judge these.
This list covers them. Copy it for every release and fill in the header and the result columns.

## When to run it

Before every APK release and before every TestFlight build goes to testers. A check that fails
blocks the release until it is fixed or the product manager accepts it in writing.

## Which devices

At least:

- one Android phone with about Android 12 (the oldest we support),
- one Android phone with the current Android version,
- one iPhone with iOS 17 (the oldest we support),
- one iPhone with the current iOS version,
- one tablet (Android tablet or iPad), if available.

Also have: a Bluetooth headset (ideally a car kit or earbuds with a button), wired headphones with
a button, a second phone or SIM to place a real call, a desktop Mumcord as the second person, and
your own test server. Never use a public server for connection checks. The one exception is the
public server browser check, which only reads the public list.

## How to use it

Run the checks on each device and platform. Tick a box only when the pass condition is fully true.
If it is not, write what happened next to the item and add a note under "Problems found". Items
marked **(Android)** or **(iPhone)** apply to that platform only. "Desktop" means the second person
on Mumcord for desktop in the same channel.

## Release record

| Field | Entry |
| --- | --- |
| Release version (app and engine) | |
| Platform (APK / TestFlight build number) | |
| Tester | |
| Date | |
| Device 1 (model, OS version) | |
| Device 2 (model, OS version) | |
| Device 3 (model, OS version) | |
| Device 4 (model, OS version) | |
| Tablet (model, OS version) | |
| Headsets used | |
| Network (Wi-Fi, mobile data provider) | |

## 1. Voice and sound

- [ ] Speak while voice activity is on. Desktop hears you clearly and sees the talking ring; you hear the desktop person and see their ring. [AC-5.5]
- [ ] Hold the phone to your ear and also use the loudspeaker while the desktop person talks and you talk back, with the desktop person listening. Pass: no echo of the desktop voice comes back to them, and your own voice is not distorted. [FR-5.25]
- [ ] Switch noise suppression off and on during a call with background noise (fan, street). Pass: with it on the noise is clearly lower; with it off it comes back. [FR-5.25]
- [ ] Push-to-talk: nothing is heard on desktop until you hold "Hold to talk"; after you let go the sound stops almost at once. [AC-5.6, FR-5.21]
- [ ] Mute, deafen and leave work from the voice bar on every tab and from the full voice view. Desktop shows the mute state. [AC-5.7, FR-5.22, FR-5.23]
- [ ] Whisper to the desktop person: only they hear you while the button is held; a third person in the channel hears nothing. [AC-5.15, FR-5.24]
- [ ] Person sheet: lower the volume of the desktop person and "Mute for me". Only your phone changes; others still hear them. [AC-5.14]
- [ ] Microphone indicator: when you can talk, the phone shows its microphone dot or indicator (Android green dot, iPhone orange dot). When muted or on push-to-talk without holding, check what it shows and note it. Pass: the indicator matches the real microphone state and disappears after you leave. [FR-5.25]
- [ ] Start the app fresh, deny the microphone question. Pass: you can still listen, and the app says why talking is not possible. [FR-5.25]

## 2. Background, lock screen and system call

- [ ] Connect, set voice activity, lock the screen and talk both ways for 30 minutes. Pass: sound works in both directions the whole time with no dropouts. [AC-5.8, FR-5.30]
- [ ] Push-to-talk mode, screen locked. Pass: the phone sends nothing while locked, and still plays what others say, with no gaps. [AC-5.8, FR-5.21]
- [ ] Press the Home button or swipe to another app while someone is talking. Pass: the sound continues; note any audible gap longer than a second. [FR-5.30]
- [ ] Leave the app in the background for several minutes and use other apps. Pass: the session is still connected when you come back. [FR-5.30]
- [ ] While connected, check the phone shows the session as a call. Android: call-style notification with Mute, Deafen and Leave. iPhone: call indicator (green bar or pill). Pass: each control works and tapping opens the app. [AC-5.9, FR-5.31, FR-5.32]
- [ ] After the session, open the phone's recent calls list. Pass: no Mumcord entry appears (check on iPhone Recents too). [AC-5.9, FR-5.31]
- [ ] (Android) Notice the label of the red button on the notification. Pass: it says "Hang up" and it disconnects you. [FR-5.32]
- [ ] (iPhone) On the call screen, mute and the End button. Pass: mute toggles the microphone; End leaves the channel; there is no deafen button. [FR-5.32]
- [ ] Force the app to be ended by the system (many apps open, or swipe it away in the app switcher and reopen). Pass: the next start says the session ended and offers to reconnect, and no data is lost. [FR-5.37, section 9 Robustness]
- [ ] Alarm, navigation prompt or another audio app interrupts you. Pass: after it ends, Mumcord plays again without you doing anything. [FR-5.36]

## 3. Real phone calls

- [ ] Use a second phone to call this phone while you are in a channel. Accept the call. Pass: desktop shows you muted and deafened, and you are still in the channel. [AC-5.10, FR-5.33]
- [ ] End the call. Pass: your previous mute and deafen state is back and you are still in the channel. [AC-5.10, FR-5.33]
- [ ] Start with yourself muted, then take a call and end it. Pass: you are still muted afterwards. [FR-5.33]
- [ ] Decline an incoming call. Pass: the session is not interrupted. [FR-5.33]
- [ ] (iPhone) While in a channel, take a second call and choose "End & Accept" on the first. Pass: the session ends cleanly or pauses as described by the app, and nothing hangs. If behaviour is unclear, note it. [FR-5.33]
- [ ] (Android) Same with a call waiting. Pass: after the calls end, the session works again. [FR-5.33]

## 4. Headsets and audio output

- [ ] Start a first connection without any headset. Pass: the app asks "Loudspeaker or earpiece?" and remembers the answer next time. [AC-5.11, FR-5.34]
- [ ] Choose earpiece, then use the output sheet in the voice view to switch to the loudspeaker and back. Pass: the sound moves each time and the sheet lists every output that is connected. [AC-5.11, FR-5.34]
- [ ] Connect a Bluetooth headset during a session. Pass: audio moves to the headset by itself; the output sheet can switch between all outputs. Note any audible gap during the switch. [AC-5.11, FR-5.34]
- [ ] Disconnect the Bluetooth headset (switch it off). Pass: sound returns to the phone and does not stay silent. [FR-5.34]
- [ ] Plug in wired headphones. Pass: audio uses them automatically. [FR-5.34]
- [ ] Press the headset button once on a Bluetooth headset. Pass: the microphone toggles, you hear a short feedback sound, desktop shows the mute state. [AC-5.12, FR-5.35]
- [ ] (Android) Press the headset button again with the screen locked. Pass: it toggles mute again, and does not leave the channel. [AC-5.12, FR-5.35]
- [ ] (Android) Press the hang-up button of a car kit. Pass: it mutes you and does not leave the channel. [FR-5.35]
- [ ] (iPhone) Press the End button on the call screen with a headset. Pass: it always leaves the channel. [FR-5.32, FR-5.35]
- [ ] (iPhone) Press the button of AirPods once. Pass: it mutes. Press the button of wired EarPods once. Pass: it leaves the channel. Note any difference. [FR-5.35]
- [ ] Listen for the feedback sound of the headset button in a noisy place and through the loudspeaker. Pass: it is audible but not loud or harsh. [FR-5.35]

## 5. Network

- [ ] While in a channel and muted, switch the phone from Wi-Fi to mobile data. Pass: the app reconnects by itself into the same channel with the same mute state. [AC-5.13, FR-5.40]
- [ ] Switch back from mobile data to Wi-Fi. Pass: same result. [FR-5.40]
- [ ] Turn on "Reduced voice quality" and talk on mobile data. Pass: voice stays understandable at the lower quality; desktop can still follow you. [FR-5.26, FR-5.41]
- [ ] On mobile data with default settings, a desktop screen share shows "tap to watch" and loads nothing before the tap; with "Start streams automatically" on it starts by itself. [AC-5.23, FR-5.41]
- [ ] Turn on "Disconnect when I'm alone in a channel for 30 minutes". Be alone in a channel with the screen locked and the phone idle for the real 30 minutes (Android: let it fall into Doze). Pass: a notification "Disconnecting in 1 minute" with "Stay connected" appears; without a tap the app disconnects and leaves a notice. Repeat once and tap "Stay connected": the session stays. [AC-5.30, FR-5.42]

## 6. Notifications

- [ ] Send a private message from desktop while the phone shows another tab. Pass: a system notification appears; tapping it opens that conversation. [AC-5.18, FR-5.54]
- [ ] Send a private message while the phone is locked. Pass: the notification is on the lock screen and, after unlocking, tapping it opens the chat. [AC-5.18, FR-5.54]
- [ ] Send several messages from the same person, then from two people. Pass: they are grouped sensibly and each opens the right chat. [FR-5.54]
- [ ] Mention the phone user in channel chat while another tab is open. Pass: a notification appears. [FR-5.54]
- [ ] Turn on "Show sender only", then send a message. Pass: the notification shows the sender and no message text. [AC-5.18, FR-5.55]
- [ ] Set the phone's lock-screen privacy to hide content. Pass: the lock screen hides the text, following the system rule. [FR-5.55]
- [ ] Turn on Do Not Disturb (Android) or a Focus mode (iPhone). Pass: the notification follows the phone's rule (silent or hidden), the app badge still counts, and there is no separate Mumcord switch. [FR-5.56]
- [ ] Disconnect, then send a private message from desktop. Pass: nothing appears. [AC-5.18, FR-5.54]
- [ ] Refuse notifications in the system settings. Pass: the session notification on Android is still shown. [FR-5.32]

## 7. Identities, transfer and sharing

- [ ] Desktop "Send to your phone": pick one identity and the server list. On the phone scan all codes with the real camera in normal room light and also in dim light. Pass: scanning works without struggle, focus is stable, progress "Code 3 of 7" updates. [AC-5.21, FR-5.64, FR-5.65]
- [ ] Type the 8-digit code. Pass: the identity and the servers arrive after you confirm; saved server passwords do not. [AC-5.21, FR-5.66]
- [ ] Type a wrong code three times. Pass: each time a clear error, then you must scan again. [AC-5.21, FR-5.65]
- [ ] Wait more than 2 minutes with the desktop dialog open, then try to finish. Pass: the codes are refused. [AC-5.21, FR-5.64]
- [ ] Import a `.p12` file from the phone's file picker and enter its password. Pass: the test server knows you as the same registered user. [AC-5.20, FR-5.62]
- [ ] Export an identity with the share sheet and send it to a real app (mail, notes, cloud drive). Pass: the file arrives and opens on desktop with its password. [FR-5.63]
- [ ] Share an image from the photo library and take one with the camera into the chat. Pass: both arrive correctly on desktop. [FR-5.51]
- [ ] (iPhone) More → Edit profile → Change photo: take a picture with the real camera, holding the phone upright, then one held sideways, then choose a photo with a location from the photo library; save each time. Pass: the desktop shows each picture upright, and the saved avatar carries no location or camera data. [AC-5.25, FR-5.80]
- [ ] Open a `mumble://` link to your own test server from another app (notes or browser). Pass: the app shows the target and connects only after you confirm. [AC-5.29]

## 8. Public servers

- [ ] Before opening "Browse public servers", watch that nothing is requested. This is the only check that may contact the public list; it only reads the list. [AC-5.38, section 9 Privacy]
- [ ] Open the browser with a real connection. Pass: the real list loads with name, address and country; filtering by name and by country narrows it; people online and latency show where available. [AC-5.38]
- [ ] Switch to airplane mode and refresh. Pass: the last list stays and a clear message appears. [AC-5.38]
- [ ] Add one entry to your server list, but do not connect to a public server. Pass: it appears in the list. [AC-5.38]

## 9. Camera and streams

- [ ] Share the phone camera after the preview sheet. Pass: desktop sees it; switching front and back works. [AC-5.24]
- [ ] Send the app to the background while sharing the camera. Pass: desktop shows "paused" and voice continues. [AC-5.24]
- [ ] (iPhone) Share the camera with the phone upright, then turned sideways, with the front and with the back camera. Pass: the preview and the desktop's picture are upright each time, and the front camera's preview is mirrored while the desktop's picture is not, for either camera (text held up to the camera reads correctly on the desktop). [AC-5.24, FR-5.74]
- [ ] (iPhone) Hold the phone sideways, open the preview and switch between front and back camera a few times, then do the same while sharing. Pass: the preview is upright right after each switch, without turning the phone. [FR-5.74]
- [ ] (iPhone) Watch the camera indicator (green dot). Pass: it is off before the camera button is tapped, on from the preview, and off again after Cancel, after Stop and while the app is in the background. [FR-5.74, FR-5.75]
- [ ] (iPhone) While sharing, take a phone call, then open another app that uses the camera, and come back each time. Pass: voice behaves as in section 4, sharing stays on and the picture comes back by itself; if the camera cannot come back, the app says "Camera stopped." [FR-5.75]
- [ ] (iPad) While sharing, open a second app in Split View or Slide Over, then close it again. Pass: the picture stops while both apps are on screen and comes back by itself when Mumcord has the screen alone; the app does not say "Camera stopped." The rules behind it are tested in `CameraSessionWatchTests`; only a device shows that iOS sends the interruption and its end. [FR-5.75]
- [ ] (iPhone) The first tap on the camera button. Pass: Mumcord explains the camera first, then iOS asks; after "Don't Allow" the sheet says at once that the camera can be allowed in the system settings, and "Open settings" leads there. [FR-5.74]
- [ ] Watch a desktop screen share: full screen, rotate to landscape, go back to the floating mini window above the chat. Pass: sound and picture stay smooth, and the chat is usable. [AC-5.22]
- [ ] Tablet: two columns in portrait and landscape; a stream fills the right column with the chat strip below. [AC-5.31]

## 10. Looks, accessibility and app icon

- [ ] Look at the app icon on the home screen and in the app list. Pass: it is the Mumcord icon, sharp and not cut off. [section 10]
- [ ] (Android 13 and later) Turn on themed icons. Pass: the Mumcord icon takes the theme colour and looks right. [section 10]
- [ ] Look at the notification icon in the status bar (Android). Pass: it shows the Mumcord glyph and is readable. [FR-5.32]
- [ ] Set the system font size to the largest setting (200 %). Connect, join a channel, mute and send a message. Pass: nothing is clipped or unlabelled. [AC-5.33]
- [ ] With TalkBack (Android) or VoiceOver (iPhone), do the same. Pass: every control has a clear spoken label and can be reached. [AC-5.33]
- [ ] (Android) TalkBack pass, with TalkBack on and swiping right through each screen: server list, add server, connect, channel tree, voice bar, full voice view, chat, Messages, More and Settings. Pass: the screen title is read first and is a heading (TalkBack's heading navigation jumps to it); the order follows what you see from top to bottom (on a tablet: navigation rail, then the left column with the voice bar, then the right column); no element is silent or read as "button" only. [AC-5.33]
- [ ] (Android) With TalkBack on, mute, deafen and switch to push-to-talk in the voice bar and the voice view, then double-tap and hold "Hold to talk". Pass: each button says its new state ("Muted", "Deafened", "Talking" while held). [AC-5.33]
- [ ] (Android) With TalkBack on, let one, then two desktop people start talking in your channel within a few seconds. Pass: TalkBack says "Anna is talking", then "Anna and Ben are talking" at most once every five seconds, not when they stop, and the same person again only after half a minute; your own talking is never announced. [AC-5.33]
- [ ] (Android) With TalkBack on, open the menu of a channel in the channel tree (double-tap and hold, or the TalkBack menu "Actions"), and the actions of a chat message and of a theme. Pass: the same actions as the long-press menu are offered. [AC-5.33]
- [ ] (Android) With TalkBack on, open a bottom sheet (for example the person sheet or "Leave server?") and swipe through it. Pass: the sheet's title is read first and you stay in the sheet; note it as a problem if swiping reaches the dimmed screen behind it. [AC-5.33]
- [ ] (Android) Turn on Settings, Accessibility, "Remove animations". Open and close sheets and the voice view. Pass: they appear without motion and nothing is shown only through movement. [AC-5.33]
- [ ] (iPhone) VoiceOver pass, with VoiceOver on (Settings, Accessibility, VoiceOver) and swiping right through each screen: server list, add server, connect, channel tree, voice bar, full voice view, chat, Messages, More and Settings. Pass: the screen title is read first and is a heading (the rotor's "Headings" jumps to it); the order follows what you see from top to bottom (on an iPad: navigation rail, then the left column with the voice bar, then the right column); no element is silent or read as "button" only; the dots, server tiles, avatar initials and the screen's backdrop are not read on their own. [AC-5.33]
- [ ] (iPhone) With VoiceOver on, mute, deafen and switch to push-to-talk in the voice bar and the voice view, then double-tap and hold "Hold to talk". Pass: each button says its new state ("Muted", "Deafened", "Talking" while held), and the person sheet's volume says the percentage shown. [AC-5.33]
- [ ] (iPhone) With VoiceOver on, let one, then two desktop people start talking in your channel within a few seconds. Pass: VoiceOver says "Anna is talking", then "Anna and Ben are talking" at most once every five seconds, after what it is saying instead of cutting it off, not when they stop, and the same person again only after half a minute; your own talking is never announced. [AC-5.33]
- [ ] (iPhone) With VoiceOver on, open the actions of a channel in the channel tree (swipe up or down on it), and of a chat message and a theme. Pass: the same actions as the long press are offered. [AC-5.33]
- [ ] (iPhone) With VoiceOver on, open a sheet (for example the person sheet or "Leave server?") and swipe through it. Pass: VoiceOver moves to the sheet's title, which is read first, and you stay in the sheet; the two-finger scrub closes a sheet that can be put away. Note it as a problem if swiping reaches the dimmed screen behind it. [AC-5.33]
- [ ] (iPhone) Turn on Settings, Accessibility, Motion, "Reduce Motion". Open and close sheets and the voice view, and type in the chat. Pass: the app adds no motion of its own (the chat's input bar jumps above the keyboard instead of gliding), and nothing is shown only through movement. [AC-5.33]
- [ ] (iPhone) Set Settings, Accessibility, Display & Text Size, Larger Text to the largest size. Pass: the app stays at its largest size (235 %, the third of the larger accessibility steps) and the checks of the 200 % item above pass. [AC-5.33]
- [ ] Switch the phone between dark and light and set a desktop theme code. Pass: colours match the desktop theme in both modes. [AC-5.26]
- [ ] (iPhone) With a theme other than Mumcord, switch Themes between System, Dark and Light while the phone is in dark mode, then in light mode, and open the keyboard, the share sheet and the photo library each time. Pass: the status bar stays readable on the app's colours, and with System everything follows the phone when its dark mode changes while the app is open. [AC-5.26, FR-5.81]
- [ ] (Android) Install the new APK over the old one. Pass: all data stays. With an older version installed and a newer release published, the update notice appears and opens the download. [AC-5.34]

## 11. Battery and data measurements

Run these on each reference phone, once per release. Use a full charge near 100 %, keep other apps
closed, stay on the same Wi-Fi, and have the desktop person in the same channel not talking. All
measurements below are for one hour with the screen off unless stated.

| Metric | Target (spec section 9) | How to measure | Result |
| --- | --- | --- | --- |
| Battery in background on Wi-Fi, screen off, one hour of voice session (people talking about a quarter of the time) | At most 6 % of a typical 4,500 mAh battery, so at most about 270 mAh | Android: charge to 100 %, reset statistics (`adb shell dumpsys batterystats --reset`), run one hour, then read the Mumcord line in Settings, Battery, Battery usage, or run `adb shell dumpsys batterystats --charged app.mumcord.mobile` and read "Estimated power use (mAh)". iPhone: Settings, Battery, scroll to the app list, "Last 24 hours", read Mumcord's percentage and its "Background Activity" time. Also note the total battery drop. | |
| Battery while idle (connected, nobody talking, no stream), one hour | No periodic work beyond the protocol keep-alive; usage should be clearly lower than the talking test [FR-5.43] | Same method as above with nobody talking. | |
| Data used by voice at default quality, one hour | Below 40 MB per hour | Android: Settings, Network, Data usage (App data usage), reset or note the Mumcord figure before and after, on mobile data. iPhone: Settings, Mobile Service, scroll to Mumcord; reset statistics first ("Reset Statistics" at the bottom) and read it after. | |
| Data used with "Reduced voice quality" on, one hour on mobile data | Lower than the default figure (voice limited to 24 kbit/s, about 11 MB per hour for voice itself) [FR-5.26] | Same method as the data row, mobile data only. | |
| Start: tap on the app icon to a usable server list | Under 2 seconds | Open the app from a closed state ten times with a stopwatch or a screen recording; note the slowest. | |

Write down the reference device for each row (model, OS version, battery size) in the result.

## Problems found

| Check (section and item) | Device | What happened | Release blocker? |
| --- | --- | --- | --- |
| | | | |
