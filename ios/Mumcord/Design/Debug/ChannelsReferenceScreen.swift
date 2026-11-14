// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import SwiftUI

/// Static demo data shared by the debug screens (server Nordlicht).
enum DesignDemo {
    static let tabs = [
        MumcordTab(icon: MumcordIcons.list, label: "Channels"),
        MumcordTab(icon: MumcordIcons.hash, label: "Chat"),
        MumcordTab(icon: MumcordIcons.messages, label: "Messages", badge: "2", accessibilityLabel: "Messages, 2 unread"),
        MumcordTab(icon: MumcordIcons.more, label: "More"),
    ]
}

/// Mockup `02-channels` rebuilt from the design system with static demo data (server
/// Nordlicht). It proves the system against the mockup and is not connected to the engine; the
/// real channel screen is built by a later task.
struct ChannelsReferenceScreen: View {
    let onOpenGallery: () -> Void

    var body: some View {
        MumcordScreen {
            MumcordAppBar(
                title: "Nordlicht",
                leading: { MumcordTile(initials: "NO", brand: true, small: true) },
                subtitle: {
                    MumcordDot()
                    MumcordAppBarSubtitle(text: "5 online · 24 ms")
                },
                actions: {
                    MumcordIconButton(icon: MumcordIcons.search, label: "Search channels and people", action: {})
                    MumcordIconButton(icon: MumcordIcons.more, label: "Server menu", action: onOpenGallery)
                }
            )
            ScrollView {
                DemoChannelTree()
            }
            MumcordVoiceBar(
                channelName: "Lobby",
                status: "Nordlicht · Voice connected",
                talking: false,
                muted: false,
                deafened: false,
                labels: MumcordVoiceBarLabels(
                    open: "Open voice controls",
                    talking: "You are talking",
                    notTalking: "You are not talking",
                    mute: "Mute microphone",
                    unmute: "Unmute microphone",
                    deafen: "Deafen",
                    undeafen: "Undeafen",
                    leave: "Leave server"
                ),
                onOpen: {},
                onToggleMute: {},
                onToggleDeafen: {},
                onLeave: {}
            )
            MumcordTabBar(tabs: DesignDemo.tabs, selectedIndex: 0, onSelect: { _ in })
        }
    }
}

private struct DemoChannelTree: View {
    var body: some View {
        MumcordTree {
            MumcordChannelRow(
                name: "Lobby",
                icon: MumcordIcons.volume,
                expanded: true,
                toggleLabel: "Collapse Lobby",
                memberCount: 3,
                current: true,
                meta: "you are here",
                action: {}
            )
            MumcordChannelBody {
                MumcordPersonRow(name: "Anna", initials: "A", colorSlot: 4, action: {})
                MumcordPersonRow(name: "Ben", initials: "B", colorSlot: 8, talking: true, talkingLabel: "Talking", action: {})
                MumcordPersonRow(name: "Lea", initials: "L", colorSlot: 2, note: "(you)", action: {})
            }
            MumcordChannelRow(name: "Gaming", expanded: true, toggleLabel: "Collapse Gaming", memberCount: 2, action: {})
            MumcordChannelBody {
                MumcordPersonRow(
                    name: "Mira",
                    initials: "M",
                    colorSlot: 6,
                    status: [MumcordPersonStatus(icon: MumcordIcons.monitor, label: "Sharing screen", tone: .live)],
                    action: {}
                )
                MumcordStreamTile(
                    label: "Mira's screen",
                    liveLabel: "LIVE",
                    viewerCount: 1,
                    accessibilityLabel: "Mira's screen, live, 1 viewer",
                    action: {}
                )
                .frame(width: 232)
                // Lines up with the names: avatar plus gap of a person row.
                .padding(.leading, 50)
                .padding(.bottom, 6)
                MumcordPersonRow(
                    name: "Jonas",
                    initials: "J",
                    colorSlot: 3,
                    status: [
                        MumcordPersonStatus(icon: MumcordIcons.micOff, label: "Muted", tone: .off),
                        MumcordPersonStatus(icon: MumcordIcons.headphonesOff, label: "Deafened", tone: .off),
                    ],
                    action: {}
                )
            }
            MumcordChannelRow(name: "Music", empty: true, lockedLabel: "Password protected", action: {})
            MumcordChannelRow(name: "AFK", empty: true, action: {})
        }
    }
}
#endif
