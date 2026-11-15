// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

/// A swipe up on the voice bar of at least this distance opens the full voice view.
private let openSwipe: CGFloat = 24

extension VoiceUiState {
    /// The line under the channel name: which server, and whether voice runs.
    var statusLine: String {
        connected ? Strings.voiceStatusConnected(serverName) : Strings.voiceStatusReconnecting(serverName)
    }
}

extension MuteControl {
    /// What VoiceOver reads for the mute button, which also says why it cannot be used.
    var actionLabel: String {
        switch self {
        case .unmuted: Strings.voiceMuteMicrophone
        case .muted: Strings.voiceUnmuteMicrophone
        case .serverMuted: Strings.voiceMutedByServer
        case .noMicrophone: Strings.voiceNoMicrophoneAction
        }
    }
}

/// The slim voice bar above the tabs (mockup `02-channels`). A tap or a swipe up opens the full
/// voice view. Without the microphone, the mute button leads to the permission instead.
struct VoiceBar: View {
    let voice: VoiceUiState
    let actions: ConnectedActions
    let onLeave: () -> Void

    var body: some View {
        let muteLabel = voice.mute.actionLabel
        MumcordVoiceBar(
            channelName: voice.channelName,
            status: voice.statusLine,
            talking: voice.talking,
            muted: voice.mute != .unmuted,
            deafened: voice.deafened,
            labels: MumcordVoiceBarLabels(
                open: Strings.voiceOpen,
                talking: Strings.voiceTalking,
                notTalking: Strings.voiceNotTalking,
                mute: muteLabel,
                unmute: muteLabel,
                deafen: Strings.voiceDeafen,
                undeafen: Strings.voiceUndeafen,
                leave: Strings.voiceLeaveServer
            ),
            onOpen: actions.openVoice,
            onToggleMute: voice.mute == .noMicrophone ? actions.requestMicrophone : actions.toggleMute,
            onToggleDeafen: actions.toggleDeafen,
            onLeave: onLeave,
            muteEnabled: voice.mute != .serverMuted
        )
        .simultaneousGesture(
            DragGesture(minimumDistance: openSwipe).onEnded { drag in
                if drag.translation.height <= -openSwipe { actions.openVoice() }
            }
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("voice-bar")
    }
}

/// The whisper bar above the voice bar: who the whisper or shout goes to, as the label of the
/// button that sends it while it is held, and a button that puts the bar away.
struct WhisperBar: View {
    let whisper: WhisperBarState
    let onHeldChange: (Bool) -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: MumcordSpace.s2) {
            MumcordTalkButton(
                label: whisper.shout ? Strings.whisperToChannel(whisper.name) : Strings.whisperToPerson(whisper.name),
                active: whisper.held,
                stateLabel: whisper.held ? Strings.whisperActive : Strings.whisperInactive,
                wide: true,
                icon: MumcordIcons.users,
                onPressedChange: withHapticTick(onHeldChange)
            )
            .accessibilityIdentifier("whisper-hold")
            MumcordIconButton(icon: MumcordIcons.x, label: Strings.whisperClose, style: .tonal, action: onClose)
                .accessibilityIdentifier("whisper-close")
        }
        .padding(EdgeInsets(top: 0, leading: MumcordSpace.s2, bottom: MumcordSpace.s2, trailing: MumcordSpace.s2))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("whisper-bar")
    }
}

/// `onPressedChange` with a short tick of the phone when the button goes down.
@MainActor
func withHapticTick(_ onPressedChange: @escaping (Bool) -> Void) -> (Bool) -> Void {
    { pressed in
        if pressed { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
        onPressedChange(pressed)
    }
}
