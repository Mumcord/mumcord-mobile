// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// Draws the sheet that is open over the connected screens.
struct ConnectedSheetHost: View {
    let sheet: ConnectedSheet
    let actions: ConnectedActions

    var body: some View {
        switch sheet {
        case .channelPassword(let channelName, let wrong, let password):
            ChannelPasswordSheet(channelName: channelName, wrong: wrong, password: password, actions: actions)
        case .microphone:
            MicrophoneSheet(onContinue: actions.requestMicrophone, onDismiss: actions.microphoneNotNow)
        case .person(let person):
            PersonSheet(person: person, actions: actions)
        case .channelMenu(let id, let name, let expanded, let canJoin):
            ChannelMenuSheet(id: id, name: name, expanded: expanded, canJoin: canJoin, actions: actions)
        }
    }
}

private struct ChannelPasswordSheet: View {
    let channelName: String
    let wrong: Bool
    let password: String
    let actions: ConnectedActions

    var body: some View {
        SheetOverlay(title: Strings.channelPasswordTitle, onDismiss: actions.dismissSheet) {
            Paragraph(Strings.channelPasswordText(channelName))
            MumcordTextField(
                value: password,
                onValueChange: actions.changePassword,
                label: Strings.passwordLabel,
                error: wrong ? Strings.channelPasswordWrong : nil,
                password: true,
                submitLabel: .go,
                onSubmit: actions.submitPassword
            )
            MumcordButton(text: Strings.channelJoin, fullWidth: true, action: actions.submitPassword)
                .disabled(password.isEmpty)
                .accessibilityIdentifier("channel-password-join")
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: actions.dismissSheet)
        }
    }
}

/// Mockup `06-person-sheet`: the head, the two settings that apply to the user only, and whisper.
private struct PersonSheet: View {
    let person: PersonSheetState
    let actions: ConnectedActions
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let maxPercent = Double(AppModel.maxVolumePercent)
        SheetOverlay(title: nil, accessibilityTitle: person.name, onDismiss: actions.dismissSheet) {
            MumcordPersonHeader(
                name: person.name,
                initials: person.name.initials(1),
                colorSlot: avatarSlot(person.name),
                channelName: person.channelName,
                status: person.state.statusIcons
            )
            .accessibilityIdentifier("person-sheet")
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                MumcordSliderRow(
                    title: Strings.personVolume,
                    valueText: Strings.personVolumeValue(person.volumePercent),
                    value: Double(person.volumePercent) / maxPercent,
                    icon: MumcordIcons.volume,
                    onValueChange: { fraction in
                        actions.changeVolume(Int((fraction * maxPercent).rounded()))
                    }
                )
                MumcordListRow(
                    title: Strings.personMute,
                    icon: MumcordIcons.volumeX,
                    divider: true,
                    trailing: {
                        MumcordToggle(
                            checked: person.locallyMuted,
                            label: Strings.personMute,
                            onCheckedChange: actions.setLocalMute
                        )
                        .accessibilityIdentifier("person-mute")
                    }
                )
            }
            // A card of its own, as in the mockup, where it follows "Send private message".
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                MumcordListRow(
                    title: Strings.personWhisper(person.name),
                    icon: MumcordIcons.users,
                    action: { actions.openWhisper(.person(session: person.session)) },
                    trailing: {
                        MumcordIcon(
                            icon: MumcordIcons.chevronRight,
                            label: nil,
                            tint: theme.colors.textMuted,
                            size: MumcordSize.iconSm
                        )
                    }
                )
                .accessibilityIdentifier("person-whisper")
            }
        }
    }
}

/// What a long press on a channel offers; both actions are also on the row itself.
private struct ChannelMenuSheet: View {
    let id: UInt32
    let name: String
    let expanded: Bool?
    let canJoin: Bool
    let actions: ConnectedActions
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: name, onDismiss: actions.dismissSheet) {
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                if canJoin {
                    MumcordListRow(
                        title: Strings.channelJoin,
                        icon: MumcordIcons.volume,
                        action: { actions.join(id) }
                    )
                    .accessibilityIdentifier("channel-menu-join")
                }
                if let expanded {
                    MumcordListRow(
                        title: expanded ? Strings.channelCollapse : Strings.channelExpand,
                        icon: expanded ? MumcordIcons.chevronUp : MumcordIcons.chevronDown,
                        divider: canJoin,
                        action: { actions.toggleCollapsed(id) }
                    )
                    .accessibilityIdentifier("channel-menu-fold")
                }
                MumcordListRow(
                    title: Strings.channelShout,
                    icon: MumcordIcons.users,
                    divider: canJoin || expanded != nil,
                    action: { actions.openWhisper(.channel(channelId: id)) }
                )
                .accessibilityIdentifier("channel-menu-shout")
            }
        }
    }
}
