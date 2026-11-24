// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The frame of the app while connected: the selected tab's screen, the banners, the whisper
/// bar, the voice bar and the bottom tab bar, or the full voice view over all of it. `chat` is
/// the Chat tab, which reads its own state, as the Messages tab does through `model`.
///
/// An opened stream covers all of it full screen, or floats above the tab's screen as the mini
/// window, where the chat below stays usable.
struct ConnectedShell<Chat: View>: View {
    let state: ConnectedUiState
    let actions: ConnectedActions
    let streams: StreamsUiState
    let streamActions: StreamActions
    let onDisconnect: () -> Void
    /// Unread messages of the current channel's chat, shown on the Chat tab.
    let chatBadge: Int
    /// For the Messages tab and the More tab, which read their own state.
    let model: AppModel
    @ViewBuilder let chat: () -> Chat

    var body: some View {
        if let opened = streams.opened, streams.open?.view == .full {
            StreamFullScreen(
                stream: opened,
                landscape: streams.landscape,
                mute: state.voice?.mute,
                onToggleMute: actions.toggleMute,
                onMicrophone: actions.requestMicrophone,
                actions: streamActions
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
            screen.environment(\.keyboardTop, area.frame(in: .global).maxY)
        }
    }

    private var screen: some View {
        MumcordScreen {
            tabScreen
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .overlay(alignment: .bottomTrailing) {
                    if let opened = streams.opened {
                        // Above the chat's input bar, which stays usable.
                        StreamMiniWindow(stream: opened, actions: streamActions)
                            .padding(.trailing, miniWindowMargin)
                            .padding(.bottom, miniWindowBottom)
                    }
                }
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
            MumcordTabBar(
                tabs: [
                    MumcordTab(icon: MumcordIcons.list, label: Strings.tabChannels),
                    MumcordTab(
                        icon: MumcordIcons.hash,
                        label: Strings.tabChat,
                        badge: chatBadge > 0 ? String(chatBadge) : nil,
                        accessibilityLabel: chatBadge > 0 ? Strings.chatTabUnread(chatBadge) : nil
                    ),
                    MessagesTabItem.make(model.messagesBadge),
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
