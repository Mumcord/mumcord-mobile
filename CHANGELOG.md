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
