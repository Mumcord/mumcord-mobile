# Changelog

## [Unreleased]

### Added

- Repository skeleton: directory layout, Rust workspace, pinned engine reference, contributor rules
- Bridge between the Mumcord engine and the apps: connect, certificate question, disconnect, live view of channels and people
- Android app: connects to a Mumble server, asks before trusting an unknown or changed server certificate, shows the channel tree with people, disconnects
- Android app: Channels tab with collapsible channel tree, talking and state icons, join by tap, channel passwords, person sheet (volume and mute for you), search, and the bottom tabs with Disconnect under More
- Android: secrets are kept encrypted with a key in the phone's secure hardware and are excluded from backups
- iOS app project with Keychain storage (this device only)
- Android: Mumcord look in dark and light (colours, fonts, icons and base components shared with the desktop app), following the system setting and the system font size
- Bridge: saved servers, server ping, connect by saved server, channel passwords, per-person volume and mute, talking and stream state, `mumble://` link parsing, talk mode setting
- Android: first-start talk mode choice, server list with people online and latency, add, edit and remove servers, certificate warnings, connection errors with retry, one server at a time, and `mumble://` links that connect only after confirmation
- Android: connecting to a server on your local network explains why access to nearby devices is needed before Android asks for it (Android 17 and later); if it is refused, the error says so and leads to the system settings
- iOS: first-start talk mode choice, server list, add, edit and remove servers, certificate warnings, connection errors with retry, `mumble://` links with confirmation, and the Channels tab with channel tree, join, channel passwords, person sheet, search and Disconnect under More – the same as on Android
- Bridge: voice controls (push-to-talk, mute, deafen, whisper and shout, level meter, noise suppression and gain switches) and a real-time audio path for the apps' microphone and speaker
- Android: voice is audible. The phone's speaker and microphone run in voice-call mode with the system's echo cancellation; the microphone is asked for when you can first talk, with a one-sentence reason, and listening works without it
- Android: voice bar on every tab (talk indicator, mute, deafen, leave) and the full voice view with the people in your channel, the talk mode switch, hold-to-talk or the microphone level, and whisper to a person or shout to a channel with a hold button
- Bridge: channel chat, private messages, history, images, GIF favourites, mentions, replies, message requests and blocking, with server content delivered only as a safe structured form
- Android: a voice session keeps running in the background and with a locked screen, is shown to the phone as a call with a notification for Mute, Deafen and Leave (never in the recent calls), mutes and deafens during a phone call and restores your state afterwards, and tells you at the next start if the system closed the app while you were connected
