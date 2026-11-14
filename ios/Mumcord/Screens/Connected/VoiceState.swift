// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// What the mute button shows and does. The server's mute comes first, because the user cannot
/// lift it; then the missing microphone, because unmuting would not help.
enum MuteControl: Equatable {
    case unmuted
    case muted
    /// Muted by the server or not allowed to speak in this channel; the button is disabled.
    case serverMuted
    /// The microphone may not be used; the button leads to the permission.
    case noMicrophone
}

/// A person of the user's own channel in the full voice view. `name` is untrusted text.
struct VoiceTile: Equatable, Identifiable {
    let session: UInt32
    let name: String
    let isSelf: Bool
    let talking: Bool
    let muted: Bool

    var id: UInt32 { session }
}

/// The whisper bar. `name` is the person or channel it goes to; untrusted text.
struct WhisperBarState: Equatable {
    let name: String
    let shout: Bool
    let held: Bool
}

/// The voice bar and the full voice view as a value.
struct VoiceUiState: Equatable {
    let channelName: String
    let serverName: String
    /// False while the connection is being restored.
    let connected: Bool
    /// The user is heard right now.
    let talking: Bool
    let mute: MuteControl
    let deafened: Bool
    let talkMode: TalkMode
    /// The hold-to-talk button is down.
    let talkHeld: Bool
    /// The full voice view covers the screen.
    let open: Bool
    /// The people of the user's channel in rows, see `voiceTileRows`.
    let tileRows: [[VoiceTile]]
    let whisper: WhisperBarState?
}

/// How many people stand next to each other in the full voice view.
let voiceTilesPerRow = 3

func muteControl(self person: Person, microphone: MicrophoneNotice) -> MuteControl {
    if person.serverMuted || person.suppressed { return .serverMuted }
    if microphone == .denied || microphone == .blocked { return .noMicrophone }
    if person.selfMuted || person.selfDeafened { return .muted }
    return .unmuted
}

/// The people of one channel in rows of `voiceTilesPerRow`. They keep the order in which they
/// connected, so nobody jumps when somebody starts talking; the user comes last.
func voiceTileRows(people: some Collection<Person>, channelId: UInt32) -> [[VoiceTile]] {
    let tiles = people
        .filter { $0.channelId == channelId }
        .sorted { first, second in
            first.isSelf != second.isSelf ? second.isSelf : first.session < second.session
        }
        .map { person in
            VoiceTile(
                session: person.session,
                name: person.name,
                isSelf: person.isSelf,
                talking: person.talking,
                muted: person.selfMuted || person.selfDeafened || person.serverMuted || person.suppressed
            )
        }
    return stride(from: 0, to: tiles.count, by: voiceTilesPerRow).map { start in
        Array(tiles[start..<min(start + voiceTilesPerRow, tiles.count)])
    }
}

extension ServerModel {
    /// The name the whisper bar shows, or nil when the person or channel is gone.
    func whisperName(_ target: WhisperTarget) -> String? {
        switch target {
        case .person(let session): people[session]?.name
        case .channel(let channelId): channels[channelId]?.name
        }
    }
}

func voiceUiState(
    session: SessionState,
    server: ServerModel,
    local: ConnectedLocal,
    serverName: String,
    microphone: MicrophoneNotice
) -> VoiceUiState? {
    guard let person = server.people[server.selfSession] else { return nil }
    var whisper: WhisperBarState?
    if let target = local.whisperTarget, let name = server.whisperName(target) {
        var shout = false
        if case .channel = target { shout = true }
        whisper = WhisperBarState(name: name, shout: shout, held: local.whisperHeld)
    }
    return VoiceUiState(
        channelName: server.channels[person.channelId]?.name ?? "",
        serverName: serverName,
        connected: session.phase == .connected,
        talking: person.talking,
        mute: muteControl(self: person, microphone: microphone),
        deafened: person.selfDeafened || person.serverDeafened,
        talkMode: session.talkMode,
        talkHeld: local.talkHeld,
        open: local.voiceOpen,
        tileRows: voiceTileRows(people: server.people.values, channelId: person.channelId),
        whisper: whisper
    )
}
