// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI
import UIKit

/// How times and days are written: the phone's calendar and time zone, today's date and its
/// 12 or 24 hour setting.
struct ChatClock {
    let calendar: Calendar
    let today: ChatDay
    private let timeFormat: DateFormatter
    private let dateFormat: DateFormatter

    init(calendar: Calendar = .current, now: Date = Date(), locale: Locale = .current) {
        self.calendar = calendar
        today = ChatDay(now, calendar: calendar)
        timeFormat = DateFormatter()
        timeFormat.locale = locale
        timeFormat.timeZone = calendar.timeZone
        timeFormat.dateStyle = .none
        timeFormat.timeStyle = .short
        dateFormat = DateFormatter()
        dateFormat.locale = locale
        dateFormat.timeZone = calendar.timeZone
        dateFormat.dateStyle = .long
        dateFormat.timeStyle = .none
    }

    func time(_ epochMs: Int64) -> String {
        timeFormat.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000))
    }

    func date(_ day: ChatDay) -> String {
        day.date(calendar: calendar).map(dateFormat.string(from:)) ?? ""
    }
}

private let rowGap: CGFloat = 12
private let mentionListMaxHeight: CGFloat = 216
private let mentionRowsWithoutScrolling = 3
private let loadOlderWithinRows = 6
private let noticeSeconds: UInt64 = 4
/// How close to its newest end the list counts as showing the newest message.
private let atBottomSlack: CGFloat = 12

/// The chat of one channel or person: header, the messages with the newest at the bottom, and
/// the input bar with the reply bar and the `@` suggestions above it.
struct ChatScreen: View {
    let state: ChatUiState
    let actions: ChatController
    let loader: ChatImageLoader
    let clock: ChatClock
    @Environment(\.mumcordTheme) private var theme
    /// The top edge of the keyboard on the screen; beyond the screen while there is none.
    @State private var keyboardTop = CGFloat.infinity
    /// The bottom edge of this screen before it makes room for the keyboard.
    @State private var bottomEdge: CGFloat = 0

    var body: some View {
        // The bars under the chat (voice bar, tab bar) already take part of the keyboard's
        // height, so only what is left of it is padded.
        let lift = max(0, bottomEdge - keyboardTop)
        VStack(spacing: 0) {
            MumcordAppBar(
                title: Strings.chatTitle(state.title),
                border: true,
                leading: { EmptyView() },
                subtitle: {
                    MumcordDot()
                    MumcordAppBarSubtitle(text: Strings.chatSubtitle(state.people, state.serverName))
                },
                actions: { EmptyView() }
            )
            ZStack {
                content
                if state.newMessages > 0 {
                    MumcordPill(
                        text: Strings.chatNewMessages(state.newMessages),
                        icon: MumcordIcons.chevronDown,
                        action: actions.scrollToNewest
                    )
                    .accessibilityIdentifier("chat-new-messages")
                    .padding(.bottom, MumcordSpace.s1)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                }
                if let notice = state.notice {
                    NoticeBanner(notice: notice, onDismiss: actions.dismissNotice)
                        .frame(maxHeight: .infinity, alignment: .top)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(theme.colors.bgChat)
            Composer(state: state, actions: actions)
        }
        .padding(.bottom, lift)
        .animation(.easeOut(duration: 0.25), value: lift)
        .background {
            GeometryReader { proxy in
                Color.clear.preference(key: BottomEdgeKey.self, value: proxy.frame(in: .global).maxY)
            }
        }
        .onPreferenceChange(BottomEdgeKey.self) { bottomEdge = $0 }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
            if let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect {
                keyboardTop = frame.minY
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboardTop = .infinity
        }
        .onChange(of: newestFromOthers?.id) { _, id in
            if id != nil, let newestFromOthers { announce(newestFromOthers) }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-screen")
    }

    @ViewBuilder private var content: some View {
        if state.loading {
            CenteredNote(text: Strings.chatLoading)
        } else if state.loadFailed {
            VStack(spacing: MumcordSpace.s3) {
                Paragraph(Strings.chatLoadFailed)
                MumcordButton(text: Strings.chatRetry, style: .secondary, action: actions.reload)
            }
            .padding(MumcordSpace.s6)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if state.empty {
            CenteredNote(text: Strings.chatEmpty)
                .accessibilityIdentifier("chat-empty")
        } else {
            MessageList(state: state, actions: actions, loader: loader, clock: clock)
        }
    }

    private var newestFromOthers: ChatMessage? {
        for row in state.rows.reversed() {
            if case .message(let message, _) = row, !message.own { return message }
        }
        return nil
    }

    /// Says the newest message of somebody else to a screen reader, politely: it waits until
    /// the reader has finished what it is saying.
    private func announce(_ message: ChatMessage) {
        let text = message.content.plainText()
        var words = AttributedString(Strings.chatAnnounce(message.senderName, text.isEmpty ? Strings.chatImageWord : text))
        words.accessibilitySpeechAnnouncementPriority = .low
        AccessibilityNotification.Announcement(words).post()
    }
}

private struct BottomEdgeKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct CenteredNote: View {
    let text: String

    var body: some View {
        Paragraph(text)
            .multilineTextAlignment(.center)
            .padding(MumcordSpace.s6)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// What the app says about something it just did, for a few seconds; a tap puts it away.
private struct NoticeBanner: View {
    let notice: ChatNotice
    let onDismiss: () -> Void

    var body: some View {
        Button(action: onDismiss) {
            MumcordBanner(
                text: text,
                icon: notice == .gifSaved ? MumcordIcons.star : MumcordIcons.alert,
                tone: notice == .gifSaved ? .info : .warning
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(Strings.chatNoticeDismiss)
        .accessibilityIdentifier("chat-notice")
        .padding(.top, MumcordSpace.s2)
        .task(id: notice) {
            try? await Task.sleep(nanoseconds: noticeSeconds * 1_000_000_000)
            if !Task.isCancelled { onDismiss() }
        }
        .onAppear { AccessibilityNotification.Announcement(text).post() }
    }

    private var text: String {
        switch notice {
        case .gifSaved: Strings.gifSaved
        case .gifNotSaved: Strings.gifNotSaved
        case .imageUnreadable: Strings.chatImageUnreadable
        }
    }
}

// MARK: The list

/// Where the newest end of the list is. Far away while that end is not laid out at all.
private struct NewestEdgeKey: PreferenceKey {
    static let defaultValue = -CGFloat.infinity

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private let newestAnchor = "chat-newest"
private let listSpace = "chat-list-space"

private extension View {
    /// Turns a view upside down. The list is laid out from the bottom by turning it over and
    /// every row back: its start is then the newest message, and older pages are added at the
    /// far end, so reading them in never moves what is on screen.
    func upsideDown() -> some View {
        scaleEffect(x: 1, y: -1, anchor: .center)
    }
}

private struct MessageList: View {
    let state: ChatUiState
    let actions: ChatController
    let loader: ChatImageLoader
    let clock: ChatClock

    var body: some View {
        // Row 0 is the newest.
        let rows = Array(state.rows.reversed())
        GeometryReader { viewport in
            ScrollViewReader { scroller in
                ScrollView {
                    LazyVStack(spacing: rowGap) {
                        Color.clear
                            .frame(height: 1)
                            .id(newestAnchor)
                            .background {
                                GeometryReader { proxy in
                                    Color.clear.preference(
                                        key: NewestEdgeKey.self,
                                        value: proxy.frame(in: .named(listSpace)).minY
                                    )
                                }
                            }
                            .accessibilityHidden(true)
                        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                            rowView(row)
                                .upsideDown()
                                // Screen readers go through the rows from the oldest to the newest.
                                .accessibilitySortPriority(Double(index))
                                .onAppear {
                                    if index >= rows.count - loadOlderWithinRows { actions.loadOlder() }
                                }
                        }
                        if state.loadingOlder {
                            HistoryNote(text: Strings.chatLoadingOlder).upsideDown()
                        } else if state.historyStart {
                            HistoryNote(text: Strings.chatHistoryStart).upsideDown()
                        }
                    }
                    .padding(EdgeInsets(top: MumcordSpace.s3, leading: MumcordSpace.s2, bottom: MumcordSpace.s2, trailing: MumcordSpace.s2))
                    // A chat with few messages starts under the header, as in the mockup: in
                    // the list turned over, that is the far end.
                    .frame(minHeight: viewport.size.height, alignment: .bottom)
                }
                .coordinateSpace(name: listSpace)
                .scrollDismissesKeyboard(.immediately)
                .onPreferenceChange(NewestEdgeKey.self) { edge in
                    actions.setAtBottom(edge >= -atBottomSlack)
                }
                .onChange(of: state.scrollToken) {
                    scroller.scrollTo(newestAnchor, anchor: .top)
                }
            }
        }
        .upsideDown()
        .accessibilityIdentifier("chat-list")
    }

    @ViewBuilder
    private func rowView(_ row: ChatRow) -> some View {
        switch row {
        case .day(let day):
            MumcordDaySeparator(text: dayText(day))
        case .message(let message, let showHeader):
            MessageItem(message: message, showHeader: showHeader, actions: actions, loader: loader, clock: clock)
        case .pending(let send, let showHeader):
            PendingItem(send: send, showHeader: showHeader, selfName: state.selfName, actions: actions)
        case .hint(let hint):
            MumcordChatHint(text: hint.text)
                .accessibilityIdentifier("chat-hint")
        }
    }

    private func dayText(_ day: ChatDay) -> String {
        switch dayLabel(day, today: clock.today, calendar: clock.calendar) {
        case .today: Strings.chatDayToday
        case .yesterday: Strings.chatDayYesterday
        case .date(let day): clock.date(day)
        }
    }
}

private struct HistoryNote: View {
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        ParagraphText(text, style: .caption, color: theme.colors.textMuted)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, MumcordSpace.s2)
    }
}

// MARK: Writing

/// The reply bar, the `@` suggestions, the counter and the input bar.
private struct Composer: View {
    let state: ChatUiState
    let actions: ChatController
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        VStack(spacing: 0) {
            if let reply = state.reply {
                MumcordReplyBar(
                    // Both are untrusted text and shown as plain characters.
                    title: Strings.chatReplyingTo(reply.quote.name),
                    text: reply.quote.snippet.isEmpty ? Strings.chatImageWord : reply.quote.snippet,
                    closeLabel: Strings.chatReplyCancel,
                    onClose: actions.cancelReply
                )
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("chat-reply-bar")
            }
            if !state.mentions.isEmpty {
                MentionList(mentions: state.mentions, onPick: actions.pickMention)
            }
            if let left = state.charactersLeft {
                ParagraphText(
                    left >= 0 ? Strings.chatCharactersLeft(left) : Strings.chatCharactersOver(-left),
                    style: .caption,
                    color: left >= 0 ? colors.textMuted : colors.danger
                )
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.horizontal, MumcordSpace.s4)
                .padding(.vertical, MumcordSpace.s1)
                .background(colors.bgChat)
            }
            MumcordInputBar(
                text: Binding(
                    get: { state.draft },
                    set: { text in
                        // The field does not say where its cursor is; an edit happens at it.
                        if text != state.draft {
                            actions.changeDraft(text, cursor: cursorAfterEdit(old: state.draft, new: text))
                        }
                    }
                ),
                placeholder: Strings.chatPlaceholder(state.title),
                fieldIdentifier: "chat-input",
                leading: {
                    MumcordIconButton(icon: MumcordIcons.image, label: Strings.chatAddImage, action: actions.openImageSources)
                        .accessibilityIdentifier("chat-add-image")
                    MumcordIconButton(icon: MumcordIcons.gif, label: Strings.chatAddGif, action: actions.openGifs)
                        .accessibilityIdentifier("chat-add-gif")
                },
                trailing: {
                    // Drawn as in the mockup also while there is nothing to send; a tap then
                    // does nothing and screen readers hear that it is not available.
                    MumcordIconButton(
                        icon: MumcordIcons.send,
                        label: Strings.chatSend,
                        style: .accent,
                        dimsWhenDisabled: false,
                        action: actions.send
                    )
                    .disabled(!state.canSend)
                    .accessibilityIdentifier("chat-send")
                }
            )
        }
    }
}

private struct MentionList: View {
    let mentions: [MentionCandidate]
    let onPick: (MentionCandidate) -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        // A few names are shown as they are; more of them scroll in a box of fixed height.
        Group {
            if mentions.count > mentionRowsWithoutScrolling {
                ScrollView { list }.frame(height: mentionListMaxHeight)
            } else {
                list
            }
        }
        .padding(.vertical, MumcordSpace.s2)
        .frame(maxWidth: .infinity)
        .background(theme.colors.bgChat)
    }

    private var list: some View {
        MumcordList {
            ForEach(Array(mentions.enumerated()), id: \.offset) { index, candidate in
                MumcordListRow(
                    // The name is untrusted text and shown as plain characters.
                    title: Strings.chatMentionItem(candidate.name),
                    icon: candidate.session == nil ? MumcordIcons.users : MumcordIcons.user,
                    divider: index > 0,
                    action: { onPick(candidate) }
                )
                .accessibilityIdentifier("chat-mention")
            }
        }
    }
}
