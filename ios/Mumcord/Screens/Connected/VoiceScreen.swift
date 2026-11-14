// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// A swipe down on the top of the voice view of at least this distance closes it.
private let closeSwipe: CGFloat = 24
/// Height of the hold-to-talk button and of the level meter that takes its place.
private let talkAreaHeight: CGFloat = 96
private let sheetShape = UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28)
/// The talk modes in the order of the switch.
private let talkModes: [TalkMode] = [.voiceActivity, .pushToTalk]

/// The full voice view (mockup `03-voice-expanded`): the people of the own channel, the large
/// mute, deafen and leave buttons, the talk mode switch and, in thumb reach, the hold-to-talk
/// button, or the microphone level in voice activity mode. It covers the whole screen; the
/// chevron, a swipe down on it and VoiceOver's escape gesture close it. The banners of the
/// connected screens stay visible above the buttons.
struct VoiceScreen: View {
    let voice: VoiceUiState
    let state: ConnectedUiState
    let actions: ConnectedActions
    let onLeave: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        MumcordScreen {
            VStack(alignment: .leading, spacing: MumcordSpace.s3) {
                CollapseHandle(onClose: actions.closeVoice)
                VStack(alignment: .leading, spacing: 0) {
                    // The mockup's title is one step larger than the app bar title.
                    LineText(voice.channelName, style: TextToken.title.with(size: 24), color: colors.text)
                        .accessibilityAddTraits(.isHeader)
                    LineText(voice.statusLine, style: .small, color: colors.textMuted)
                }
                VoiceTiles(rows: voice.tileRows)
                ConnectedBanners(state: state, actions: actions, horizontalMargin: 0)
                if let whisper = voice.whisper {
                    WhisperBar(whisper: whisper, onHeldChange: actions.setWhisperHeld, onClose: actions.closeWhisper)
                }
                VoiceActions(voice: voice, actions: actions, onLeave: onLeave)
                MumcordSegmentedPill(
                    options: [Strings.talkModeVoiceActivity, Strings.talkModePushToTalk],
                    selectedIndex: talkModes.firstIndex(of: voice.talkMode) ?? 0,
                    label: Strings.voiceTalkMode,
                    onSelect: { actions.selectTalkMode(talkModes[$0]) }
                )
                .accessibilityIdentifier("voice-talk-mode")
                if voice.talkMode == .pushToTalk {
                    MumcordTalkButton(
                        label: voice.talkHeld ? Strings.voiceTalking : Strings.voiceHoldToTalk,
                        active: voice.talkHeld,
                        stateLabel: voice.talkHeld ? Strings.voiceTalking : Strings.voiceNotTalking,
                        wide: true,
                        tall: true,
                        onPressedChange: withHapticTick(actions.setTalkHeld)
                    )
                    .accessibilityIdentifier("voice-hold")
                } else {
                    InputLevelMeter(level: actions.inputLevel)
                }
            }
            .padding(EdgeInsets(top: MumcordSpace.s2, leading: MumcordSpace.s4, bottom: MumcordSpace.s2, trailing: MumcordSpace.s4))
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background { sheetShape.fill(colors.bgChat).ignoresSafeArea(edges: .bottom) }
            .overlay { sheetShape.strokeBorder(colors.border, lineWidth: 1).ignoresSafeArea(edges: .bottom) }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Strings.voiceView)
            .accessibilityIdentifier("voice-view")
            .accessibilityAction(.escape, actions.closeVoice)
        }
    }
}

/// The chevron at the top (`.vsheet__collapse`); a tap or a swipe down closes the view.
private struct CollapseHandle: View {
    let onClose: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        Button(action: onClose) {
            MumcordIcon(icon: MumcordIcons.chevronDown, label: nil, tint: theme.colors.textSecondary)
                // Drawn 28 pt high as in the mockup; the touch area is the whole strip.
                .frame(maxWidth: .infinity, minHeight: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Strings.voiceCollapse)
        .accessibilityIdentifier("voice-collapse")
        .simultaneousGesture(
            DragGesture(minimumDistance: closeSwipe).onEnded { drag in
                if drag.translation.height >= closeSwipe { onClose() }
            }
        )
    }
}

/// The people in rows of three, each row centred (`.vgrid`); it scrolls when there are many.
private struct VoiceTiles: View {
    let rows: [[VoiceTile]]

    var body: some View {
        GeometryReader { geometry in
            // Share of the width one person takes, so that three stand in a row.
            let tileWidth = geometry.size.width * 0.31
            ScrollView {
                VStack(spacing: MumcordSpace.s3) {
                    ForEach(rows.indices, id: \.self) { index in
                        HStack(alignment: .top, spacing: MumcordSpace.s2) {
                            ForEach(rows[index]) { tile in
                                MumcordVoiceTile(
                                    name: tile.isSelf ? Strings.voicePersonYou(tile.name) : tile.name,
                                    initials: tile.name.initials(1),
                                    colorSlot: avatarSlot(tile.name),
                                    talking: tile.talking,
                                    talkingLabel: Strings.personTalking,
                                    mutedLabel: tile.muted ? Strings.personMuted : nil
                                )
                                .frame(width: tileWidth)
                                .accessibilityIdentifier("voice-tile")
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                // Room for the talking ring, which is drawn outside the avatar.
                .padding(.vertical, MumcordSpace.s2)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Mute, deafen and leave as large round buttons with their names (`.vactions`).
private struct VoiceActions: View {
    let voice: VoiceUiState
    let actions: ConnectedActions
    let onLeave: () -> Void

    var body: some View {
        // Each action is 88 pt wide, which leaves the mockup's 24 pt between the 64 pt buttons
        // and room for a label of two lines.
        HStack(alignment: .top, spacing: 0) {
            MumcordVoiceAction(
                icon: voice.mute == .unmuted ? MumcordIcons.mic : MumcordIcons.micOff,
                label: muteLabel,
                spokenLabel: voice.mute.actionLabel,
                style: voice.mute == .unmuted ? .tonal : .off,
                action: voice.mute == .noMicrophone ? actions.requestMicrophone : actions.toggleMute
            )
            .disabled(voice.mute == .serverMuted)
            .frame(width: 88)
            .accessibilityIdentifier("voice-mute")
            MumcordVoiceAction(
                icon: voice.deafened ? MumcordIcons.headphonesOff : MumcordIcons.headphones,
                label: voice.deafened ? Strings.voiceUndeafen : Strings.voiceDeafen,
                spokenLabel: voice.deafened ? Strings.voiceUndeafen : Strings.voiceDeafen,
                style: voice.deafened ? .off : .tonal,
                action: actions.toggleDeafen
            )
            .frame(width: 88)
            .accessibilityIdentifier("voice-deafen")
            MumcordVoiceAction(
                icon: MumcordIcons.phoneOff,
                label: Strings.voiceLeave,
                spokenLabel: Strings.voiceLeaveServer,
                style: .danger,
                action: onLeave
            )
            .frame(width: 88)
            .accessibilityIdentifier("voice-leave")
        }
        .frame(maxWidth: .infinity)
    }

    private var muteLabel: String {
        switch voice.mute {
        case .unmuted: Strings.voiceMute
        case .muted: Strings.voiceUnmute
        case .serverMuted: Strings.voiceMutedByServer
        case .noMicrophone: Strings.voiceNoMicrophone
        }
    }
}

/// The microphone level, where the hold-to-talk button is in push-to-talk mode. Only this view
/// reads the level, so its up to 20 changes a second redraw the bar and nothing else.
private struct InputLevelMeter: View {
    let level: InputLevel
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: MumcordSpace.s2) {
            ParagraphText(Strings.voiceLevelHint, style: .small, color: theme.colors.textMuted)
            MumcordLevelMeter(level: Double(level.percent) / 100, label: Strings.voiceLevel)
                .accessibilityIdentifier("voice-level")
        }
        .padding(.horizontal, MumcordSpace.s2)
        .frame(maxWidth: .infinity, minHeight: talkAreaHeight, alignment: .leading)
    }
}
