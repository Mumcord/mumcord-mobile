// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Width of the left column of two columns (`.pane--side`): the channel tree and the bars.
private let sideColumnWidth: CGFloat = 340

/// The frame of the app while connected. In one column: the selected tab's screen, the banners,
/// the whisper bar, the voice bar and the bottom tab bar. In two columns (an iPad, see
/// `connectedLayout`): the navigation rail, the channel tree with the bars on the left and the
/// chat, an opened stream or the Messages and More screens on the right. The full voice view
/// covers all of it. `chat` is the Chat tab, which reads its own state, as the Messages tab does
/// through `model`.
///
/// An opened stream covers all of it full screen, or floats above the tab's screen as the mini
/// window, where the chat below stays usable; in two columns it fills the right column of
/// Channels. The layout follows `twoColumns`, which the app's root decides again on every
/// rotation and resize; what the user did lives in the model and stays.
struct ConnectedShell<Chat: View>: View {
    let state: ConnectedUiState
    let actions: ConnectedActions
    let streams: StreamsUiState
    let streamActions: StreamActions
    let onDisconnect: () -> Void
    /// Unread messages of the current channel's chat, shown on the Chat tab.
    let chatBadge: Int
    /// The window is laid out in two columns.
    let twoColumns: Bool
    /// For the Messages tab and the More tab, which read their own state.
    let model: AppModel
    @ViewBuilder let chat: () -> Chat
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        content.background {
            // Behind every screen of the connection, so the announcements go on in the voice view,
            // a full screen stream and every tab.
            if let voice = state.voice, voice.connected {
                TalkingAnnouncementsView(tiles: voice.tileRows.flatMap { $0 })
            }
        }
    }

    @ViewBuilder private var content: some View {
        if let opened = streams.opened, streamCoversWindow(streams.open, twoColumns: twoColumns) {
            StreamFullScreen(
                stream: opened,
                landscape: streams.landscape,
                mute: state.voice?.mute,
                onToggleMute: actions.toggleMute,
                onMicrophone: actions.requestMicrophone,
                actions: streamActions,
                // Opened from the right column, the whole screen returns there.
                onLeave: twoColumns ? streamActions.expand : nil
            )
        } else if let voice = state.voice, voice.open {
            VoiceScreen(voice: voice, state: state, actions: actions, onLeave: onDisconnect, camera: model.cameraShare)
        } else {
            tabs
        }
    }

    private var tabs: some View {
        // This frame ends where the keyboard begins, which the chat needs to know: the screen
        // inside it reaches under the keyboard.
        GeometryReader { area in
            Group {
                if twoColumns {
                    columns
                } else {
                    screen
                }
            }
            .environment(\.keyboardTop, area.frame(in: .global).maxY)
        }
    }

    private var screen: some View {
        MumcordScreen {
            tabScreen(state.tab)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .overlay(alignment: .bottomTrailing) {
                    if let opened = streams.opened {
                        // Above the chat's input bar, which stays usable.
                        StreamMiniWindow(stream: opened, actions: streamActions)
                            .padding(.trailing, miniWindowMargin)
                            .padding(.bottom, miniWindowBottom)
                    }
                }
            bars
            MumcordTabBar(
                tabs: [
                    channelsTab,
                    MumcordTab(
                        icon: MumcordIcons.hash,
                        label: Strings.tabChat,
                        badge: chatBadge > 0 ? String(chatBadge) : nil,
                        accessibilityLabel: chatBadge > 0 ? Strings.chatTabUnread(chatBadge) : nil
                    ),
                    MessagesTabItem.make(model.messagesBadge),
                    moreTab,
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

    /// The navigation rail, the channels with the bars, and the right column.
    private var columns: some View {
        let colors = theme.colors
        let tab = state.tab.inTwoColumns
        // The opened stream fills the right column of Channels; elsewhere it floats as the mini window.
        let inColumn = streamInColumn(streams.open, tab: tab) ? streams.opened : nil
        return MumcordScreen {
            HStack(spacing: 0) {
                MumcordNavigationRail(
                    tabs: [channelsTab, MessagesTabItem.make(model.messagesBadge), moreTab],
                    selectedIndex: railTabs.firstIndex(of: tab) ?? 0,
                    onSelect: { actions.selectTab(railTabs[$0]) }
                )
                .accessibilityIdentifier("navigation-rail")
                VStack(spacing: 0) {
                    ChannelsScreen(
                        state: state,
                        actions: actions,
                        streams: streams,
                        streamActions: streamActions,
                        hiddenStream: inColumn?.key
                    )
                    .frame(maxHeight: .infinity, alignment: .top)
                    bars
                }
                .frame(width: sideColumnWidth)
                .overlay(alignment: .trailing) {
                    Rectangle().fill(colors.border).frame(width: 1).ignoresSafeArea()
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("side-column")
                ZStack(alignment: .bottomTrailing) {
                    Group {
                        if let inColumn {
                            StreamColumnHost(stream: inColumn, actions: streamActions, model: model)
                        } else {
                            tabScreen(tab == .channels ? .chat : tab)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    if let opened = streams.opened, inColumn == nil {
                        StreamMiniWindow(
                            stream: opened,
                            actions: streamActions,
                            // Back in the right column of Channels, where the stream has its place.
                            onExpand: {
                                actions.selectTab(.channels)
                                streamActions.expand()
                            }
                        )
                        .padding(.trailing, miniWindowMargin)
                        .padding(.bottom, miniWindowBottom)
                    }
                }
                // No container element of its own here: it would take the place of the chat's and
                // the stream column's, which have the same frame.
                .background { colors.bgChat.ignoresSafeArea() }
            }
        }
        // The voice bar stays at the bottom while the search field or the chat has the keyboard open.
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }

    /// The banners, the whisper bar, the camera bar and the voice bar: above the tab bar in one
    /// column, under the channels in two.
    @ViewBuilder private var bars: some View {
        ConnectedBanners(state: state, actions: actions)
        if let voice = state.voice {
            if let whisper = voice.whisper {
                WhisperBar(whisper: whisper, onHeldChange: actions.setWhisperHeld, onClose: actions.closeWhisper)
            }
            if let camera = model.cameraShare {
                CameraBar(state: camera.uiState, controller: camera)
            }
            VoiceBar(voice: voice, actions: actions, onLeave: onDisconnect)
        }
    }

    private var channelsTab: MumcordTab {
        MumcordTab(icon: MumcordIcons.list, label: Strings.tabChannels)
    }

    private var moreTab: MumcordTab {
        MumcordTab(icon: MumcordIcons.more, label: Strings.tabMore)
    }

    /// One screen of a tab; in two columns the right column shows Chat for Channels.
    @ViewBuilder private func tabScreen(_ tab: ConnectedTab) -> some View {
        switch tab {
        case .channels:
            ChannelsScreen(state: state, actions: actions, streams: streams, streamActions: streamActions)
        case .chat:
            chat()
        case .messages:
            MessagesTab(model: model)
        case .more:
            MoreTab(model: model, onDisconnect: onDisconnect)
        }
    }
}

private struct KeyboardTopKey: EnvironmentKey {
    static let defaultValue = CGFloat.infinity
}

extension EnvironmentValues {
    /// Where the keyboard begins on the screen, or the bottom of the area for content without
    /// one. Set by the connected screens, whose bars stay under the keyboard.
    var keyboardTop: CGFloat {
        get { self[KeyboardTopKey.self] }
        set { self[KeyboardTopKey.self] = newValue }
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

/// The Messages tab's entry in the tab bar, with the number of unread private messages.
private enum MessagesTabItem {
    static func make(_ badge: Int) -> MumcordTab {
        MumcordTab(
            icon: MumcordIcons.messages,
            label: Strings.tabMessages,
            badge: badge > 0 ? String(badge) : nil,
            accessibilityLabel: badge > 0 ? Strings.messagesTabUnread(badge) : nil
        )
    }
}
