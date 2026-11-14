// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// The session as the phone system shows it, or nil while no server is shown.
func callInfo(_ state: SessionState) -> CallInfo? {
    guard state.showsServer, let server = state.server else { return nil }
    let own = server.people[server.selfSession]
    return CallInfo(
        serverName: state.activeServerName,
        channelName: server.selfChannelId.flatMap { server.channels[$0]?.name },
        muted: own?.selfMuted == true || own?.selfDeafened == true,
        deafened: own?.selfDeafened == true
    )
}

/// What the user is set to on the server, or nil while they are on none.
func selfVoice(_ state: SessionState) -> SelfVoice? {
    guard state.showsServer, let server = state.server, let own = server.people[server.selfSession] else {
        return nil
    }
    return SelfVoice(muted: own.selfMuted || own.selfDeafened, deafened: own.selfDeafened)
}

/// What to remember on disk about the running session, so a cut-off one is noticed (FR-5.37).
enum MarkerStep: Equatable {
    case mark(SessionMarker)
    /// The session ended in an orderly way: the user left, or the app said why it failed.
    case clear
    /// A connection is being made; nothing is known yet.
    case keep
}

func markerStep(_ state: SessionState) -> MarkerStep {
    if state.showsServer, let serverId = state.activeServerId {
        return .mark(SessionMarker(serverId: serverId, serverName: state.activeServerName))
    }
    return state.isBusy ? .keep : .clear
}

private extension SessionState {
    var activeServerName: String {
        servers.first { $0.id == activeServerId }?.name ?? ""
    }
}

/// Mute and leave for everything outside the screens: the system's call screen and the phone
/// call handling. Nothing here can open the microphone in push-to-talk mode: that takes the
/// hold button, which only exists on screen (FR-5.21).
struct SessionVoiceControl: Sendable {
    let store: SessionStore

    func current() -> SelfVoice? {
        selfVoice(store.state)
    }

    /// As the mute button on screen: unmuting also ends a deafen (the engine does that).
    func setMuted(_ muted: Bool) {
        Task { await store.setSelfMute(muted) }
    }

    func leave() {
        Task { await store.disconnect() }
    }

    /// Sets both at once. Deafening includes muting; ending it leaves the mute to be set.
    func set(_ voice: SelfVoice) async {
        if voice.deafened {
            await store.setSelfDeaf(true)
        } else {
            await store.setSelfDeaf(false)
            await store.setSelfMute(voice.muted)
        }
    }
}
