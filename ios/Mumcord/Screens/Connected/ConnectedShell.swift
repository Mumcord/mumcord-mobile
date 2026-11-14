// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The frame of the app while connected: the selected tab's screen, the banners, the whisper
/// bar, the voice bar and the bottom tab bar, or the full voice view over all of it. `chat` is
/// the Chat tab, which reads its own state; Messages is filled by its own task.
struct ConnectedShell<Chat: View>: View {
    let state: ConnectedUiState
    let actions: ConnectedActions
    let onDisconnect: () -> Void
    /// Unread messages of the current channel's chat, shown on the Chat tab.
    let chatBadge: Int
    @ViewBuilder let chat: () -> Chat

    var body: some View {
        if let voice = state.voice, voice.open {
            VoiceScreen(voice: voice, state: state, actions: actions, onLeave: onDisconnect)
        } else {
            tabs
        }
    }

    private var tabs: some View {
        MumcordScreen {
            tabScreen
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            ConnectedBanners(state: state, actions: actions)
            if let voice = state.voice {
                if let whisper = voice.whisper {
                    WhisperBar(whisper: whisper, onHeldChange: actions.setWhisperHeld, onClose: actions.closeWhisper)
                }
                VoiceBar(voice: voice, actions: actions, onLeave: onDisconnect)
            }
            MumcordTabBar(
                tabs: [
                    MumcordTab(icon: MumcordIcons.list, label: Strings.tabChannels),
                    MumcordTab(
                        icon: MumcordIcons.hash,
                        label: Strings.tabChat,
                        badge: chatBadge > 0 ? String(chatBadge) : nil,
                        accessibilityLabel: chatBadge > 0 ? Strings.chatTabUnread(chatBadge) : nil
                    ),
                    MumcordTab(icon: MumcordIcons.messages, label: Strings.tabMessages),
                    MumcordTab(icon: MumcordIcons.more, label: Strings.tabMore),
                ],
                selectedIndex: state.tab.rawValue,
                onSelect: { index in
                    if let tab = ConnectedTab(rawValue: index) {
                        actions.selectTab(tab)
                    }
                }
            )
        }
        // The tab bar stays at the bottom while the search field has the keyboard open.
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }

    @ViewBuilder private var tabScreen: some View {
        switch state.tab {
        case .channels:
            ChannelsScreen(state: state, actions: actions)
        case .chat:
            chat()
        case .messages:
            EmptyTabScreen(title: Strings.tabMessages, text: Strings.tabMessagesEmpty)
        case .more:
            MoreScreen(serverName: state.serverName, onDisconnect: onDisconnect)
        }
    }
}

/// A lost connection, the microphone that may not be used and the latest refusal by the server,
/// visible on every tab and in the full voice view.
struct ConnectedBanners: View {
    let state: ConnectedUiState
    let actions: ConnectedActions
    var horizontalMargin: CGFloat = MumcordSpace.s4

    var body: some View {
        let reconnecting = state.reconnecting
        let denied = state.denied
        if reconnecting != nil || denied != nil || state.microphone != .none {
            VStack(spacing: MumcordSpace.s2) {
                if let reconnecting {
                    MumcordBanner(
                        text: Strings.channelsReconnecting(reconnecting.attempt, reconnecting.maxAttempts),
                        icon: MumcordIcons.alert,
                        tone: .warning,
                        horizontalMargin: horizontalMargin
                    )
                }
                if state.microphone != .none {
                    MicrophoneBanner(
                        blocked: state.microphone == .blocked,
                        horizontalMargin: horizontalMargin,
                        action: actions.requestMicrophone
                    )
                }
                if let denied {
                    Button(action: actions.dismissDenied) {
                        MumcordBanner(
                            // The server's own words are shown as plain characters.
                            text: denied.detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? Strings.deniedText
                                : denied.detail,
                            title: denied.channelName.map(Strings.deniedTitleChannel) ?? Strings.deniedTitle,
                            icon: MumcordIcons.alert,
                            tone: .danger,
                            horizontalMargin: horizontalMargin
                        )
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(Strings.deniedDismiss)
                    .accessibilityIdentifier("denied-banner")
                }
            }
            .padding(.bottom, MumcordSpace.s2)
        }
    }
}

/// A tab without content yet: its name and one neutral sentence.
private struct EmptyTabScreen: View {
    let title: String
    let text: String

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(title: title, large: true)
            Paragraph(text)
                .padding(MumcordSpace.s6)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("empty-tab")
    }
}

private struct MoreScreen: View {
    let serverName: String
    let onDisconnect: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.tabMore, large: true)
            MumcordList {
                MumcordListRow(
                    title: Strings.moreDisconnect(serverName),
                    icon: MumcordIcons.phoneOff,
                    danger: true,
                    action: onDisconnect
                )
                .accessibilityIdentifier("more-disconnect")
            }
            Spacer(minLength: 0)
        }
    }
}
