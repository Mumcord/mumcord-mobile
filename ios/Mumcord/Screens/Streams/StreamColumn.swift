// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// How many of the newest messages the chat strip under a stream shows.
private let stripMessageCount = 2

/// The volume slider on the stage (`.slider` of mockup 22).
private let stageSliderWidth: CGFloat = 140

/// The stage's buttons (`.stage .iconbtn` of mockup 22).
private let stageButtonSize: CGFloat = 40

/// How far the LIVE badge and the viewers sit from the stage's edges.
private let stageBadgeInset: CGFloat = 14

/// The newest messages of `rows` for the chat strip under a stream, oldest first. Messages of
/// blocked people are not in the rows at all.
func stripMessages(_ rows: [ChatRow], count: Int = stripMessageCount) -> [ChatMessage] {
    let messages = rows.compactMap { row -> ChatMessage? in
        if case .message(let message, _) = row { return message }
        return nil
    }
    return Array(messages.suffix(count))
}

/// The opened stream in the right column of two columns (FR-5.71, mockup 22): the sharer and
/// their channel above, the stage with LIVE, viewers, volume and the buttons, and below it a
/// strip with the newest messages of the user's channel chat and its input bar, so the chat
/// stays usable while watching. The strip's arrow and the mini window button make room for the
/// whole chat; the full screen button opens the whole screen.
///
/// `chat` is the user's channel chat; nil for the moment it is not known yet.
struct StreamColumn: View {
    let stream: StreamUi
    let actions: StreamActions
    let chat: ChatUiState?
    let chatActions: ChatController

    var body: some View {
        KeyboardLifted {
            VStack(spacing: 0) {
                MumcordAppBar(
                    title: stream.title,
                    border: true,
                    leading: { MumcordAvatar(initials: stream.name.initials(1), colorSlot: avatarSlot(stream.name)) },
                    subtitle: {
                        if let channel = stream.channel {
                            MumcordAppBarSubtitle(text: channel)
                        }
                    },
                    actions: { EmptyView() }
                )
                // At the top: in portrait the column is narrow and the stage leaves room below it.
                StreamStage(stream: stream, actions: actions)
                    .padding(EdgeInsets(top: MumcordSpace.s4, leading: MumcordSpace.s4, bottom: 0, trailing: MumcordSpace.s4))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                if let chat {
                    ChatStrip(chat: chat, onShowChat: actions.collapse)
                    ChatComposer(state: chat, actions: chatActions)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("stream-column")
    }
}

/// Connects the stream column to the user's channel chat. It reads the chat's own state, so an
/// arriving message redraws this column and not the channels beside it.
struct StreamColumnHost: View {
    let stream: StreamUi
    let actions: StreamActions
    let model: AppModel
    @State private var calendar = Calendar.current

    var body: some View {
        StreamColumn(stream: stream, actions: actions, chat: model.chatUiState(calendar: calendar), chatActions: model.chat)
    }
}

/// The 16:9 stage (`.stage`), as large as the space allows; dark in both themes like the full
/// screen.
private struct StreamStage: View {
    let stream: StreamUi
    let actions: StreamActions
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        ZStack {
            colors.stageBg
            if stream.playing {
                StreamPicture(videoId: stream.videoId)
            }
            if let status = stream.openStatus {
                StageStatus(text: status, retry: retry)
            }
        }
        .overlay(alignment: .topLeading) {
            MumcordLiveBadge(liveLabel: Strings.streamLive)
                .padding(stageBadgeInset)
        }
        .overlay(alignment: .topTrailing) {
            if let viewers = stream.viewers {
                HStack(spacing: MumcordSpace.s1) {
                    MumcordIcon(icon: MumcordIcons.user, label: nil, tint: colors.mediaText, size: MumcordSize.iconSm)
                    LineText(Strings.streamWatching(viewers), style: .captionStrong, color: colors.mediaText)
                }
                .padding(.horizontal, MumcordSpace.s2)
                .padding(.vertical, MumcordSpace.s1)
                .background(colors.mediaScrim, in: RoundedRectangle(cornerRadius: MumcordRadius.sm))
                .accessibilityElement(children: .combine)
                .padding(stageBadgeInset)
            }
        }
        .overlay(alignment: .bottom) {
            StageControls(stream: stream, actions: actions)
        }
        .clipShape(shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .aspectRatio(16 / 9, contentMode: .fit)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(stream.title)
        .accessibilityIdentifier("stream-stage")
    }

    private var retry: (() -> Void)? {
        guard case .unreachable = stream.tile else { return nil }
        let key = stream.key
        return { actions.retry(key) }
    }
}

private struct StageControls: View {
    let stream: StreamUi
    let actions: StreamActions
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: MumcordSpace.s2) {
            // Only a share that sends sound has a volume (FR-5.72). The slider takes what the
            // buttons leave, at most its width of the mockup, so "Stop watching" always fits.
            HStack(spacing: MumcordSpace.s2) {
                if stream.hasSound {
                    MumcordIcon(icon: MumcordIcons.volume, label: nil, tint: colors.mediaText)
                    MumcordSlider(
                        value: Double(stream.volume) / Double(maxStreamVolume),
                        label: Strings.streamVolume,
                        valueText: Strings.personVolumeValue(stream.volume),
                        onMedia: true,
                        onValueChange: { fraction in
                            actions.changeVolume(stream.key, Int((fraction * Double(maxStreamVolume)).rounded()))
                        }
                    )
                    .frame(maxWidth: stageSliderWidth)
                    .accessibilityIdentifier("stream-volume")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            MumcordStageButton(icon: MumcordIcons.monitor, label: Strings.streamCollapse, size: stageButtonSize, action: actions.collapse)
                .accessibilityIdentifier("stream-collapse")
            MumcordStageButton(icon: MumcordIcons.maximize, label: Strings.streamExpand, size: stageButtonSize, action: actions.fillScreen)
                .accessibilityIdentifier("stream-fill-screen")
            MumcordButton(text: Strings.streamStop, style: .danger, small: true, action: actions.close)
                .fixedSize()
                .accessibilityIdentifier("stream-stop")
        }
        .padding(.horizontal, MumcordSpace.s3)
        .padding(.vertical, MumcordSpace.s2)
        .background(LinearGradient(colors: [.clear, colors.stageBg], startPoint: .top, endPoint: .bottom))
    }
}

/// The strip under the stage (`.strip`): the chat's name and its newest messages.
private struct ChatStrip: View {
    let chat: ChatUiState
    let onShowChat: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        VStack(alignment: .leading, spacing: MumcordSpace.s1) {
            HStack(spacing: 6) {
                MumcordIcon(icon: MumcordIcons.hash, label: nil, tint: colors.textSecondary, size: MumcordSize.iconSm)
                // The channel's name is untrusted text and shown as plain characters.
                LineText(Strings.streamChatStrip(chat.title), style: .bodyStrong, color: colors.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
                MumcordIconButton(icon: MumcordIcons.chevronUp, label: Strings.streamChatStripOpen, action: onShowChat)
                    .accessibilityIdentifier("stream-chat-strip-open")
            }
            .frame(minHeight: 36)
            ForEach(stripMessages(chat.rows), id: \.id) { message in
                StripMessage(message: message)
            }
        }
        .padding(.horizontal, MumcordSpace.s3)
        .padding(.vertical, MumcordSpace.s2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.bgElevated, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .padding(.horizontal, MumcordSpace.s4)
        .padding(.vertical, MumcordSpace.s3)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("stream-chat-strip")
    }
}

// The sender's name and the message's text come from the server: plain characters only.
private struct StripMessage: View {
    let message: ChatMessage
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        HStack(spacing: MumcordSpace.s3) {
            MumcordAvatar(initials: message.senderName.initials(1), colorSlot: avatarSlot(message.senderName), size: .small)
            Text(line)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }

    private var line: AttributedString {
        let colors = theme.colors
        let snippet = message.quote.snippet.isEmpty ? Strings.chatImageWord : message.quote.snippet
        var name = AttributedString(message.senderName)
        name.font = Font(MumcordFonts.font(.bodyStrong, scale: scale))
        var text = AttributedString("  " + snippet)
        text.font = Font(MumcordFonts.font(.body, scale: scale))
        var line = name + text
        line.foregroundColor = colors.text
        return line
    }
}
