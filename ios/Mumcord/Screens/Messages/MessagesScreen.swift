// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// Room under the list so that its last row can be scrolled clear of the floating button.
private let fabClearance: CGFloat = 120

/// The Messages tab while no conversation is open: the conversation list of mockup
/// `09-messages`, the message requests, or the people to start a conversation with.
struct MessagesScreen: View {
    let state: MessagesUiState
    let actions: MessagesController
    let clock: ChatClock
    let onNotificationSettings: () -> Void

    var body: some View {
        switch state.page {
        case .list:
            ConversationList(state: state, actions: actions, clock: clock, onNotificationSettings: onNotificationSettings)
        case .requests:
            RequestsPage(state: state, actions: actions)
        case .picker:
            PickerPage(state: state, actions: actions)
        }
    }
}

private struct ConversationList: View {
    let state: MessagesUiState
    let actions: MessagesController
    let clock: ChatClock
    let onNotificationSettings: () -> Void

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0) {
                MumcordAppBar(title: Strings.tabMessages, large: true) {
                    if state.searching {
                        MumcordIconButton(icon: MumcordIcons.x, label: Strings.messagesSearchClose, action: actions.closeSearch)
                    } else if state.hasConversations {
                        MumcordIconButton(icon: MumcordIcons.search, label: Strings.messagesSearch, action: actions.openSearch)
                    }
                }
                if state.searching {
                    SearchField(
                        query: state.query,
                        onChange: actions.changeQuery,
                        label: Strings.messagesSearch,
                        identifier: "messages-search"
                    )
                }
                ScrollView {
                    VStack(spacing: 0) {
                        if state.notificationsOff {
                            NotificationsOffBanner(action: onNotificationSettings)
                                .padding(.bottom, MumcordSpace.s2)
                        }
                        if !state.requests.isEmpty && !state.searching {
                            RequestsRow(count: state.requests.count, action: actions.openRequests)
                        }
                        if !state.rows.isEmpty {
                            MumcordSectionHeader(title: Strings.messagesSection)
                            MumcordList {
                                ForEach(Array(state.rows.enumerated()), id: \.element.chat) { index, row in
                                    ConversationLine(row: row, divider: index > 0, clock: clock) {
                                        actions.openConversation(row.chat, name: row.name, session: row.session)
                                    }
                                }
                            }
                        } else if state.searching {
                            CenteredNote(text: Strings.messagesSearchEmpty)
                        } else {
                            CenteredNote(text: Strings.messagesEmpty)
                                .accessibilityIdentifier("messages-empty")
                        }
                    }
                    .padding(.bottom, fabClearance)
                }
            }
            MumcordFab(icon: MumcordIcons.edit, label: Strings.messagesNew, action: actions.openPicker)
                .accessibilityIdentifier("messages-new")
                .padding(.trailing, MumcordSpace.s4)
                .padding(.bottom, MumcordSpace.s4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("messages-screen")
    }
}

/// The calm line for a phone that may not show the app's notifications; it leads to the settings.
private struct NotificationsOffBanner: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MumcordBanner(
                text: Strings.messagesNotificationsOffText,
                title: Strings.messagesNotificationsOffTitle,
                icon: MumcordIcons.alert
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(Strings.messagesNotificationsOffAction)
        .accessibilityIdentifier("messages-notifications-off")
    }
}

private struct RequestsRow: View {
    let count: Int
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordList {
            MumcordListRow(
                title: Strings.messagesRequests(count),
                subtitle: Strings.messagesRequestsSub,
                icon: MumcordIcons.user,
                action: action,
                trailing: {
                    // The count is in the title; the badge only repeats it for the eye.
                    MumcordBadge(text: String(count)).accessibilityHidden(true)
                    MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: theme.colors.textMuted)
                }
            )
            .accessibilityIdentifier("messages-requests")
        }
    }
}

/// One conversation. Names and previews come from other people: they are only ever drawn as
/// plain characters.
private struct ConversationLine: View {
    let row: ConversationRow
    let divider: Bool
    let clock: ChatClock
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let name = row.name.isEmpty ? Strings.messagesUnknownPerson : row.name
        let preview = previewText(row.preview)
        let time = listTime(row.sentAtMs, clock)
        let colors = theme.colors
        // One sentence for screen readers instead of the row's separate pieces.
        let pieces: [String?] = [
            name,
            row.online ? Strings.messagesOnline : nil,
            row.unread > 0 ? Strings.messagesRowUnread(row.unread) : nil,
            preview.isEmpty ? nil : preview,
            time,
        ]
        MumcordListRow(
            title: name,
            subtitle: preview.isEmpty ? nil : preview,
            divider: divider,
            tall: true,
            action: action,
            leading: { ConversationAvatar(name: name, online: row.online) },
            trailing: {
                VStack(alignment: .trailing, spacing: 6) {
                    LineText(time, style: row.unread > 0 ? .smallStrong : .small, color: row.unread > 0 ? colors.accentText : colors.textMuted)
                    if row.unread > 0 {
                        MumcordBadge(text: String(row.unread))
                    } else {
                        Color.clear.frame(width: 1, height: 20)
                    }
                }
            }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(pieces.compactMap { $0 }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("conversation-row")
    }
}

/// The avatar of a conversation with the green dot of a person who is on the server (`.pdot`).
private struct ConversationAvatar: View {
    let name: String
    let online: Bool
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordAvatar(initials: name.initials(1), colorSlot: avatarSlot(name), size: .large)
            .overlay(alignment: .bottomTrailing) {
                if online {
                    Circle()
                        .fill(theme.colors.success)
                        .frame(width: 14, height: 14)
                        .overlay(Circle().stroke(theme.colors.bgElevated, lineWidth: 2))
                        .offset(x: 1, y: 1)
                }
            }
    }
}

private func previewText(_ preview: ConversationPreview) -> String {
    switch preview {
    case .text(let text): text
    case .picture: Strings.messagesPreviewPicture
    case .nothing: ""
    }
}

/// The time of a message of today, "Yesterday", or the date of an older one.
private func listTime(_ sentAtMs: Int64, _ clock: ChatClock) -> String {
    let date = Date(timeIntervalSince1970: Double(sentAtMs) / 1000)
    let parts = clock.calendar.dateComponents([.year, .month, .day], from: date)
    let day = ChatDay(year: parts.year ?? 0, month: parts.month ?? 0, day: parts.day ?? 0)
    switch dayLabel(day, today: clock.today, calendar: clock.calendar) {
    case .today: return clock.time(sentAtMs)
    case .yesterday: return Strings.chatDayYesterday
    case .date(let day): return clock.date(day)
    }
}

private struct CenteredNote: View {
    let text: String

    var body: some View {
        Paragraph(text)
            .padding(MumcordSpace.s6)
            .frame(maxWidth: .infinity)
    }
}

private struct SearchField: View {
    let query: String
    let onChange: (String) -> Void
    let label: String
    let identifier: String

    var body: some View {
        MumcordTextField(value: query, onValueChange: onChange, label: label, submitLabel: .search, autoFocus: true)
            .accessibilityIdentifier(identifier)
            .padding(.horizontal, MumcordSpace.s4)
            .padding(.bottom, MumcordSpace.s2)
    }
}

// MARK: Message requests

private struct RequestsPage: View {
    let state: MessagesUiState
    let actions: MessagesController

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.requestsTitle, onBack: actions.back, backLabel: Strings.requestsBack)
            ScrollView {
                VStack(alignment: .leading, spacing: MumcordSpace.s3) {
                    Paragraph(Strings.requestsHint).padding(.horizontal, MumcordSpace.s4)
                    ForEach(state.requests, id: \.id) { request in
                        RequestCard(request: request, actions: actions)
                    }
                }
                .padding(.bottom, MumcordSpace.s4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("requests-screen")
    }
}

private struct RequestCard: View {
    let request: RequestRow
    let actions: MessagesController

    var body: some View {
        let name = request.name.isEmpty ? Strings.messagesUnknownPerson : request.name
        let held = previewText(request.text)
        let text = held.isEmpty && request.chatRequest ? Strings.requestsChatRequestEmpty : held
        MumcordList {
            MumcordListRow(
                title: name,
                subtitle: text.isEmpty ? nil : text,
                tall: true,
                leading: { ConversationAvatar(name: name, online: false) },
                trailing: {
                    MumcordListRowValue(
                        text: request.chatRequest
                            ? Strings.requestsChatRequest
                            : Strings.requestsMoreMessages(request.messageCount)
                    )
                }
            )
            HStack(spacing: MumcordSpace.s2) {
                MumcordButton(text: Strings.requestsDecline, style: .secondary, fullWidth: true) {
                    actions.declineRequest(request.id)
                }
                .accessibilityLabel(Strings.requestsDeclineDescription(name))
                .accessibilityIdentifier("request-decline")
                MumcordButton(text: Strings.requestsAccept, fullWidth: true) {
                    actions.acceptRequest(request.id)
                }
                .accessibilityLabel(Strings.requestsAcceptDescription(name))
                .accessibilityIdentifier("request-accept")
            }
            .padding(.horizontal, MumcordSpace.s4)
            .padding(.bottom, MumcordSpace.s3)
        }
    }
}

// MARK: Starting a conversation

private struct PickerPage: View {
    let state: MessagesUiState
    let actions: MessagesController

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.pickerTitle, onBack: actions.back, backLabel: Strings.requestsBack)
            SearchField(
                query: state.pickerQuery,
                onChange: actions.changePickerQuery,
                label: Strings.pickerSearch,
                identifier: "picker-search"
            )
            if state.pickerPeople.isEmpty {
                let blank = state.pickerQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                CenteredNote(text: blank ? Strings.pickerEmpty : Strings.pickerNoMatch)
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    MumcordList {
                        ForEach(Array(state.pickerPeople.enumerated()), id: \.element.session) { index, person in
                            MumcordListRow(
                                title: person.name,
                                divider: index > 0,
                                action: { actions.messagePerson(person.session) },
                                leading: { ConversationAvatar(name: person.name, online: false) }
                            )
                            .accessibilityIdentifier("picker-person")
                        }
                    }
                    .padding(.bottom, MumcordSpace.s4)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("picker-screen")
    }
}

// MARK: Blocked people (More tab)

/// The people the user blocked, each with "Unblock". Reached from the More tab.
struct BlockedPeopleScreen: View {
    let state: MessagesUiState
    let actions: MessagesController

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.blockedTitle, onBack: actions.back, backLabel: Strings.blockedBack)
            if state.blocked.isEmpty {
                CenteredNote(text: Strings.blockedEmpty)
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    MumcordList {
                        ForEach(Array(state.blocked.enumerated()), id: \.element.id) { index, person in
                            let name = person.name.isEmpty ? Strings.messagesUnknownPerson : person.name
                            MumcordListRow(
                                title: name,
                                subtitle: person.serverLabel.isEmpty ? nil : person.serverLabel,
                                divider: index > 0,
                                tall: true,
                                leading: { ConversationAvatar(name: name, online: false) },
                                trailing: {
                                    MumcordButton(
                                        text: Strings.personUnblock,
                                        style: .secondary,
                                        small: true,
                                        minHeight: MumcordSize.touchVisual
                                    ) {
                                        actions.unblock(person.id)
                                    }
                                    .accessibilityLabel(Strings.blockedUnblockDescription(name))
                                    .accessibilityIdentifier("blocked-unblock")
                                }
                            )
                        }
                    }
                    .padding(.bottom, MumcordSpace.s4)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("blocked-screen")
    }
}
