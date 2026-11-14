// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The frame of the app while connected: the selected tab's screen, the banners, and the bottom
/// tab bar. Chat and Messages are filled by their own tasks.
struct ConnectedShell: View {
    let state: ConnectedUiState
    let actions: ConnectedActions
    let onDisconnect: () -> Void

    var body: some View {
        MumcordScreen {
            tabScreen
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            ConnectedBanners(reconnecting: state.reconnecting, denied: state.denied, onDismissDenied: actions.dismissDenied)
            // The voice bar (FR-5.22) goes here, between the banners and the tab bar.
            MumcordTabBar(
                tabs: [
                    MumcordTab(icon: MumcordIcons.list, label: Strings.tabChannels),
                    MumcordTab(icon: MumcordIcons.hash, label: Strings.tabChat),
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
    }

    @ViewBuilder private var tabScreen: some View {
        switch state.tab {
        case .channels:
            ChannelsScreen(state: state, actions: actions)
        case .chat:
            EmptyTabScreen(title: Strings.tabChat, text: Strings.tabChatEmpty)
        case .messages:
            EmptyTabScreen(title: Strings.tabMessages, text: Strings.tabMessagesEmpty)
        case .more:
            MoreScreen(serverName: state.serverName, onDisconnect: onDisconnect)
        }
    }
}

/// A lost connection and the latest refusal by the server, visible on every tab.
private struct ConnectedBanners: View {
    let reconnecting: Reconnecting?
    let denied: DeniedBanner?
    let onDismissDenied: () -> Void

    var body: some View {
        if reconnecting != nil || denied != nil {
            VStack(spacing: MumcordSpace.s2) {
                if let reconnecting {
                    MumcordBanner(
                        text: Strings.channelsReconnecting(reconnecting.attempt, reconnecting.maxAttempts),
                        icon: MumcordIcons.alert,
                        tone: .warning
                    )
                }
                if let denied {
                    Button(action: onDismissDenied) {
                        MumcordBanner(
                            // The server's own words are shown as plain characters.
                            text: denied.detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? Strings.deniedText
                                : denied.detail,
                            title: denied.channelName.map(Strings.deniedTitleChannel) ?? Strings.deniedTitle,
                            icon: MumcordIcons.alert,
                            tone: .danger
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
