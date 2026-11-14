# Changelog

## [Unreleased]

### Added

- Repository skeleton: directory layout, Rust workspace, pinned engine reference, contributor rules
- Bridge between the Mumcord engine and the apps: connect, certificate question, disconnect, live view of channels and people
- Android app: connects to a Mumble server, asks before trusting an unknown or changed server certificate, shows the channel tree with people, disconnects
- Android: secrets are kept encrypted with a key in the phone's secure hardware and are excluded from backups
- iOS app project with Keychain storage (this device only)
