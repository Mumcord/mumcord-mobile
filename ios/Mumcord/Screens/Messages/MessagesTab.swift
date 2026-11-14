// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// The Messages tab: connects its screens to the model. It reads the messages' own state, so
/// an arriving private message redraws this view and not the channel list.
struct MessagesTab: View {
    let model: AppModel
    @State private var clock = ChatClock()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        Group {
            if let state = model.messagesUiState {
                if state.open == nil {
                    MessagesScreen(
                        state: state,
                        actions: model.messages,
                        clock: clock,
                        onNotificationSettings: model.openSettings
                    )
                } else if let conversation = model.conversationUiState(calendar: clock.calendar) {
                    ChatScreen(
                        state: conversation,
                        actions: model.conversation,
                        loader: model.chatImages,
                        clock: clock,
                        onBack: model.messages.back
                    )
                } else {
                    // The moment the conversation is being opened.
                    theme.colors.bgChat
                }
            }
        }
        // The conversation's chat follows what is open here.
        .onChange(of: model.messages.local.open?.chat, initial: true) { _, _ in
            model.syncChat()
        }
        .onChange(of: scenePhase) { _, phase in
            // The day may have changed while the app was away.
            if phase == .active { clock = ChatClock() }
        }
    }
}

/// The More tab's part that belongs to messages: the blocked people and what notifications show.
struct MoreMessagesSection: View {
    let model: AppModel
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordSectionHeader(title: Strings.moreSectionMessages)
        MumcordList {
            MumcordListRow(
                title: Strings.moreBlocked,
                icon: MumcordIcons.shield,
                action: model.messages.openBlocked,
                trailing: {
                    MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: theme.colors.textMuted)
                }
            )
            .accessibilityIdentifier("more-blocked")
            MumcordListRow(
                title: Strings.moreSenderOnly,
                subtitle: Strings.moreSenderOnlySub,
                icon: MumcordIcons.alert,
                divider: true,
                trailing: {
                    MumcordToggle(
                        checked: model.messages.local.senderOnly,
                        label: Strings.moreSenderOnly,
                        onCheckedChange: model.messages.setSenderOnly
                    )
                    .accessibilityIdentifier("more-sender-only")
                }
            )
        }
        .padding(.bottom, MumcordSpace.s4)
    }
}

/// The list of blocked people over the More tab while it is open, else nothing.
struct MoreBlockedHost<More: View>: View {
    let model: AppModel
    @ViewBuilder let more: () -> More

    var body: some View {
        if let state = model.messagesUiState, state.blockedOpen {
            BlockedPeopleScreen(state: state, actions: model.messages)
        } else {
            more()
        }
    }
}

/// The sheet of messages and blocking when one is open, else the chat's sheets, over the
/// whole screen. There is never more than one.
struct MessagesOverlayHost: View {
    let model: AppModel

    var body: some View {
        if let sheet = model.messagesUiState?.sheet {
            MessagesSheetHost(sheet: sheet, actions: model.messages)
        } else {
            ChatOverlayHost(model: model)
        }
    }
}

/// The rows of the person sheet about private messages, chat requests and blocking.
struct PersonMessagingRows: View {
    let session: UInt32
    let model: AppModel
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let messaging = model.messagesUiState?.person(session)
            ?? PersonMessaging(blocked: false, chatRequest: .unsupported)
        let blocked = messaging.blocked
        let offer = messaging.chatRequest
        MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
            if offer == .offer {
                MumcordListRow(
                    title: Strings.personChatRequest,
                    icon: MumcordIcons.send,
                    action: { model.messages.askChatRequest(session) },
                    trailing: { chevron }
                )
                .accessibilityIdentifier("person-chat-request")
            } else if offer == .pending {
                // A decline is never told, so there is nothing to tap while it is on its way.
                MumcordListRow(title: Strings.personChatRequestPending, icon: MumcordIcons.send)
                    .accessibilityIdentifier("person-chat-request")
            }
            MumcordListRow(
                title: blocked ? Strings.personUnblock : Strings.personBlock,
                icon: MumcordIcons.shield,
                divider: offer == .offer || offer == .pending,
                danger: !blocked,
                action: {
                    if blocked {
                        model.messages.unblockPerson(session)
                    } else {
                        model.messages.askBlock(session)
                    }
                }
            )
            .accessibilityIdentifier("person-block")
        }
    }

    private var chevron: some View {
        MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: theme.colors.textMuted, size: MumcordSize.iconSm)
    }
}
