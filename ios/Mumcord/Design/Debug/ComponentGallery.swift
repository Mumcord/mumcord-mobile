// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import SwiftUI

/// Every base component with sample content, one group under the other. Only part of debug
/// builds: it is the content of the debug-only component gallery screen.
struct ComponentGallery: View {
    var body: some View {
        VStack(alignment: .leading, spacing: MumcordSpace.s3) {
            group("App bar") { AppBarSample() }
            group("Tiles, badge, dots, icon") { BasicsSample() }
            group("Buttons") { ButtonsSample() }
            group("Channel tree, people, avatars") { ChannelTreeSample() }
            group("Stream tile") { StreamTileSample() }
            group("Lists") { ListsSample() }
            group("Chat") { ChatSample() }
            group("Toggle, slider, chips, segmented control") { ControlsSample() }
            group("Hint banner, bottom sheet") { OverlaysSample() }
            group("Hold-to-talk button") { TalkButtonSample() }
            group("Voice bar") { VoiceBarSample() }
            group("Tab bar") { TabBarSample() }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, MumcordSpace.s5)
    }

    private func group<Sample: View>(_ title: String, @ViewBuilder sample: () -> Sample) -> some View {
        VStack(alignment: .leading, spacing: MumcordSpace.s3) {
            MumcordSectionHeader(title: title)
            sample()
        }
    }
}

private struct AppBarSample: View {
    var body: some View {
        MumcordAppBar(title: "Servers", large: true) {
            MumcordIconButton(icon: MumcordIcons.search, label: "Search servers", action: {})
            MumcordIconButton(icon: MumcordIcons.settings, label: "Settings", action: {})
        }
        MumcordAppBar(title: "Settings", border: true, onBack: {}, backLabel: "Back")
        MumcordAppBar(
            title: "Nordlicht",
            leading: { MumcordTile(initials: "NO", brand: true, small: true) },
            subtitle: {
                MumcordDot()
                MumcordAppBarSubtitle(text: "5 online · 24 ms")
            },
            actions: {
                MumcordIconButton(icon: MumcordIcons.search, label: "Search channels and people", action: {})
                MumcordIconButton(icon: MumcordIcons.more, label: "Server menu", action: {})
            }
        )
    }
}

private struct BasicsSample: View {
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        HStack(spacing: MumcordSpace.s3) {
            MumcordTile(initials: "NO", brand: true)
            MumcordTile(initials: "NO", small: true)
            MumcordBadge(text: "2", ringColor: theme.colors.bgServers)
            MumcordDot()
            MumcordDot(off: true)
            MumcordIcon(icon: MumcordIcons.mic, label: nil, tint: theme.colors.text)
        }
        .padding(.horizontal, MumcordSpace.s4)
    }
}

private struct ButtonsSample: View {
    var body: some View {
        Group {
            MumcordButton(text: "Connect", fullWidth: true, action: {})
            HStack(spacing: MumcordSpace.s2) {
                MumcordButton(text: "Cancel", style: .secondary, action: {})
                MumcordButton(text: "Remove", style: .danger, icon: MumcordIcons.trash, action: {})
                MumcordButton(text: "Skip", style: .ghost, action: {})
            }
            HStack(spacing: MumcordSpace.s2) {
                MumcordButton(text: "Small", small: true, action: {})
                MumcordButton(text: "Disabled", action: {}).disabled(true)
            }
            HStack(spacing: MumcordSpace.s1) {
                ForEach(MumcordIconButtonStyle.allCases, id: \.self) { style in
                    MumcordIconButton(icon: MumcordIcons.mic, label: String(describing: style), style: style, action: {})
                }
            }
            MumcordIconButton(icon: MumcordIcons.phoneOff, label: "Leave", style: .danger, large: true, action: {})
        }
        .padding(.horizontal, MumcordSpace.s4)
    }
}

private struct ChannelTreeSample: View {
    var body: some View {
        MumcordTree {
            MumcordChannelRow(
                name: "Lobby",
                icon: MumcordIcons.volume,
                expanded: true,
                toggleLabel: "Collapse Lobby",
                memberCount: 2,
                current: true,
                meta: "you are here",
                action: {}
            )
            MumcordChannelBody {
                MumcordPersonRow(name: "Anna", initials: "A", colorSlot: 4, action: {})
                MumcordPersonRow(name: "Ben", initials: "B", colorSlot: 8, talking: true, talkingLabel: "Talking", action: {})
                MumcordPersonRow(
                    name: "Jonas",
                    initials: "J",
                    colorSlot: 3,
                    locallyMuted: true,
                    note: "(muted for you)",
                    status: [MumcordPersonStatus(icon: MumcordIcons.micOff, label: "Muted", tone: .off)],
                    action: {}
                )
            }
            MumcordChannelRow(name: "Gaming", expanded: false, toggleLabel: "Expand Gaming", memberCount: 2, action: {})
            MumcordChannelRow(name: "Music", empty: true, lockedLabel: "Password protected", action: {})
        }
        HStack(spacing: MumcordSpace.s3) {
            ForEach(Array(MumcordAvatarSize.allCases.enumerated()), id: \.offset) { index, size in
                MumcordAvatar(initials: "L", colorSlot: index + 1, size: size)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, MumcordSpace.s4)
    }
}

private struct StreamTileSample: View {
    var body: some View {
        MumcordStreamTile(
            label: "Mira's screen",
            liveLabel: "LIVE",
            viewerCount: 1,
            accessibilityLabel: "Mira's screen, live, 1 viewer",
            action: {}
        )
        .frame(width: 232)
        .padding(.horizontal, MumcordSpace.s4)
        MumcordStreamTile(
            label: "Mira's screen",
            liveLabel: "LIVE",
            viewerCount: 3,
            accessibilityLabel: "Mira's screen, live, 3 viewers",
            selected: true,
            action: {}
        )
        .frame(width: 232)
        .padding(.horizontal, MumcordSpace.s4)
    }
}

private struct ListsSample: View {
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordSectionHeader(title: "Voice", action: "Edit")
        MumcordList {
            MumcordListRow(title: "Talk mode", icon: MumcordIcons.mic, action: {}) {
                MumcordListRowValue(text: "Hold to talk")
                MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: theme.colors.textMuted, size: 18)
            }
            MumcordListRow(
                title: "Noise suppression",
                subtitle: "Removes background noise",
                icon: MumcordIcons.activity,
                divider: true,
                selected: true
            )
            MumcordListRow(
                title: "Nordlicht",
                subtitle: "nordlicht.example · 5 online",
                divider: true,
                tall: true,
                action: {},
                leading: { MumcordTile(initials: "NO", brand: true) }
            )
            MumcordListRow(title: "Remove server", icon: MumcordIcons.trash, divider: true, danger: true, action: {})
        }
    }
}

private struct ChatSample: View {
    var body: some View {
        MumcordMessageRow(
            author: "Anna",
            time: "20:14",
            text: "Anyone up for a round tonight? I can host after nine.",
            initials: "A",
            colorSlot: 4
        )
        .padding(.horizontal, MumcordSpace.s4)
        MumcordInputBar(
            text: .constant(""),
            placeholder: "Message Lobby…",
            leading: {
                MumcordIconButton(icon: MumcordIcons.image, label: "Add image", action: {})
                MumcordIconButton(icon: MumcordIcons.gif, label: "Add GIF", action: {})
            },
            trailing: {
                MumcordIconButton(icon: MumcordIcons.send, label: "Send", style: .accent, action: {})
            }
        )
    }
}

private struct ControlsSample: View {
    var body: some View {
        HStack(spacing: MumcordSpace.s3) {
            MumcordToggle(checked: false, label: "Noise suppression", onCheckedChange: { _ in })
            MumcordToggle(checked: true, label: "Echo cancellation", onCheckedChange: { _ in })
        }
        .padding(.horizontal, MumcordSpace.s4)
        MumcordSlider(value: 0.6, label: "Volume", onValueChange: { _ in })
            .padding(.horizontal, MumcordSpace.s4)
        MumcordChipRow(options: ["All", "Joins", "Messages", "Moderation"], selectedIndex: 0, onSelect: { _ in })
        MumcordChip(text: "Favourites", selected: false, icon: MumcordIcons.star, action: {})
            .padding(.horizontal, MumcordSpace.s4)
        MumcordSegmentedControl(options: ["System", "Dark", "Light"], selectedIndex: 1, onSelect: { _ in })
            .padding(.horizontal, MumcordSpace.s4)
    }
}

private struct OverlaysSample: View {
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordBanner(text: "Press and hold the button while you speak.", title: "Hold to talk is on.")
        MumcordBanner(text: "The connection is unstable.", icon: MumcordIcons.wifi, tone: .warning)
        MumcordBanner(text: "The server certificate has changed.", icon: MumcordIcons.alert, tone: .danger)
        MumcordSheetFrame(title: "Audio output") {
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                MumcordListRow(title: "Speaker", icon: MumcordIcons.speaker, selected: true)
                MumcordListRow(title: "Bluetooth headset", icon: MumcordIcons.bluetooth, divider: true)
            }
        }
    }
}

private struct TalkButtonSample: View {
    var body: some View {
        Group {
            MumcordTalkButton(label: "Hold to talk", active: false, stateLabel: "Not talking", onPressedChange: { _ in })
            MumcordTalkButton(label: "Talking", active: true, stateLabel: "Talking", onPressedChange: { _ in })
            MumcordTalkButton(label: "Hold to talk", active: false, stateLabel: "Not talking", wide: true, onPressedChange: { _ in })
        }
        .padding(MumcordSpace.s5)
    }
}

private struct VoiceBarSample: View {
    private let labels = MumcordVoiceBarLabels(
        open: "Open voice controls",
        talking: "Talking",
        notTalking: "Not talking",
        mute: "Mute microphone",
        unmute: "Unmute microphone",
        deafen: "Deafen",
        undeafen: "Undeafen",
        leave: "Leave server"
    )

    var body: some View {
        bar(active: false)
        bar(active: true)
    }

    private func bar(active: Bool) -> some View {
        MumcordVoiceBar(
            channelName: "Lobby",
            status: "Nordlicht · Voice connected",
            talking: active,
            muted: active,
            deafened: active,
            labels: labels,
            onOpen: {},
            onToggleMute: {},
            onToggleDeafen: {},
            onLeave: {}
        )
    }
}

private struct TabBarSample: View {
    var body: some View {
        MumcordTabBar(tabs: DesignDemo.tabs, selectedIndex: 0, onSelect: { _ in })
            // In the gallery the bar is not at the bottom edge of the screen.
            .environment(\.mumcordBottomInset, 0)
    }
}
#endif
