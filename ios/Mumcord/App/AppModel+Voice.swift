// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import Observation

/// The microphone level for the level meter, 0 to 100.
@MainActor
@Observable
final class InputLevel {
    var percent = 0
}

/// What the user does with voice: the full voice view, mute and deafen, the talk mode, hold to
/// talk, and whisper and shout.
extension AppModel {
    func openVoice() {
        connectedLocal.voiceOpen = true
    }

    /// Closes the full voice view; its hold-to-talk button goes with it.
    func closeVoice() {
        releaseTalk()
        connectedLocal.voiceOpen = false
    }

    /// Mutes or unmutes the user. Unmuting also ends a deafen (the engine does that).
    func toggleMute() {
        guard let person = selfPerson else { return }
        Task { await store.setSelfMute(!(person.selfMuted || person.selfDeafened)) }
    }

    /// Deafens the user, which also mutes them, or ends it.
    func toggleDeafen() {
        guard let person = selfPerson else { return }
        Task { await store.setSelfDeaf(!person.selfDeafened) }
    }

    /// Switches the talk mode at once and for good. A held button of the old mode is let go.
    func switchTalkMode(_ mode: TalkMode) {
        if store.state.talkMode == mode { return }
        releaseTalk()
        Task { await store.changeTalkMode(mode) }
    }

    /// The hold-to-talk button went down or up. It only talks in push-to-talk mode.
    func setTalkHeld(_ held: Bool) {
        guard held else {
            releaseTalk()
            return
        }
        if store.state.talkMode != .pushToTalk || connectedLocal.talkHeld { return }
        connectedLocal.talkHeld = true
        store.setPushToTalkPressed(true)
    }

    private func releaseTalk() {
        guard connectedLocal.talkHeld else { return }
        connectedLocal.talkHeld = false
        store.setPushToTalkPressed(false)
    }

    /// Lets go of every hold button. Called when the app leaves the screen, so that nothing
    /// keeps talking unseen. (Should the process die instead, the engine dies with it, and the
    /// bridge lets go of both buttons at the start of every connection.)
    func releaseHolds() {
        releaseTalk()
        releaseWhisper()
    }

    /// Shows the whisper bar for a person or a channel; nothing is sent until it is held.
    func openWhisper(_ target: WhisperTarget) {
        releaseWhisper()
        connectedLocal.whisperTarget = target
        connectedLocal.personSession = nil
        connectedLocal.volumeDraft = nil
        connectedLocal.menuChannelId = nil
    }

    /// The hold-to-whisper button went down or up.
    func setWhisperHeld(_ held: Bool) {
        guard held else {
            releaseWhisper()
            return
        }
        guard let target = connectedLocal.whisperTarget, !connectedLocal.whisperHeld else { return }
        connectedLocal.whisperHeld = true
        Task {
            let started = await store.startWhisper(target)
            if !connectedLocal.whisperHeld {
                // Let go while the engine was still starting: the release came too early for it.
                store.stopWhisper()
            } else if !started {
                connectedLocal.whisperHeld = false
            }
        }
    }

    private func releaseWhisper() {
        guard connectedLocal.whisperHeld else { return }
        connectedLocal.whisperHeld = false
        store.stopWhisper()
    }

    /// Puts the whisper bar away, which ends the whisper.
    func closeWhisper() {
        releaseWhisper()
        connectedLocal.whisperTarget = nil
    }

    private var selfPerson: Person? {
        store.state.server.flatMap { $0.people[$0.selfSession] }
    }
}
