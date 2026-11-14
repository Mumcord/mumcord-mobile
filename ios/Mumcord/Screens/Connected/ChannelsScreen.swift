// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// One step of nesting: the indent of a channel's people and sub-channels (`.channel-body`).
private let indent: CGFloat = 28

/// The Channels tab: server header, optional search field, the channel tree and the hints.
struct ChannelsScreen: View {
    let state: ConnectedUiState
    let actions: ConnectedActions

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(
                title: state.serverName,
                leading: { MumcordTile(initials: initials(state.serverName), brand: true, small: true) },
                subtitle: {
                    MumcordDot()
                    MumcordAppBarSubtitle(text: Strings.channelsOnline(state.peopleOnline))
                },
                actions: {
                    if state.searching {
                        MumcordIconButton(icon: MumcordIcons.x, label: Strings.channelsSearchClose, action: actions.closeSearch)
                    } else {
                        MumcordIconButton(icon: MumcordIcons.search, label: Strings.channelsSearch, action: actions.openSearch)
                    }
                }
            )
            if state.searching {
                MumcordTextField(
                    value: state.query,
                    onValueChange: actions.changeQuery,
                    label: Strings.channelsSearch,
                    submitLabel: .search,
                    autoFocus: true
                )
                .padding(.horizontal, MumcordSpace.s4)
                .padding(.bottom, MumcordSpace.s2)
                .accessibilityIdentifier("channel-search")
            }
            if state.searching && state.rows.isEmpty {
                Paragraph(Strings.channelsSearchEmpty)
                    .padding(MumcordSpace.s6)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ChannelList(rows: state.rows, hints: state.hints, actions: actions)
            }
        }
    }
}

private struct ChannelList: View {
    let rows: [ChannelListRow]
    let hints: [UnsupportedHint]
    let actions: ConnectedActions

    var body: some View {
        ScrollView {
            // Rows are values with stable ids: when one person starts or stops talking, only
            // that person's row is drawn again.
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(rows) { row in
                    switch row {
                    case .channel(let item):
                        ChannelLine(item: item, actions: actions)
                            .padding(.leading, indent * CGFloat(item.depth))
                    case .person(let item):
                        PersonLine(item: item, onOpen: actions.openPerson)
                            .padding(.leading, indent * CGFloat(item.depth))
                    }
                }
                ForEach(hints) { hint in
                    MumcordHintLine(text: hint.text)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("unsupported-hint")
                }
            }
            .padding(EdgeInsets(top: MumcordSpace.s1, leading: MumcordSpace.s2, bottom: MumcordSpace.s3, trailing: MumcordSpace.s2))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// Names come from the server and its users: they are only ever drawn as plain characters.
private struct ChannelLine: View {
    let item: ChannelItem
    let actions: ConnectedActions

    var body: some View {
        MumcordChannelRow(
            name: item.name,
            icon: item.current ? MumcordIcons.volume : MumcordIcons.hash,
            expanded: item.expanded,
            toggleLabel: item.expanded == true ? Strings.channelsCollapse(item.name) : Strings.channelsExpand(item.name),
            memberCount: item.peopleCount > 0 ? item.peopleCount : nil,
            current: item.current,
            empty: item.peopleCount == 0,
            meta: item.current ? Strings.channelsHere : nil,
            lockedLabel: item.locked ? Strings.channelsLocked : nil,
            spokenLabel: spokenLabel,
            identifier: "channel-row",
            onToggleExpanded: { actions.toggleCollapsed(item.id) },
            onLongPress: { actions.openChannelMenu(item.id) },
            longPressLabel: Strings.channelsMenu,
            action: { actions.join(item.id) }
        )
    }

    private var spokenLabel: String {
        Strings.channelsChannelDescription(item.name, Strings.channelsPeopleCount(item.peopleCount))
            + (item.current ? Strings.channelsChannelHereSuffix : "")
            + (item.locked ? Strings.channelsChannelLockedSuffix : "")
    }
}

private struct PersonLine: View {
    let item: PersonItem
    let onOpen: (UInt32) -> Void

    var body: some View {
        MumcordPersonRow(
            name: item.name,
            initials: item.name.initials(1),
            colorSlot: avatarSlot(item.name),
            talking: item.talking,
            talkingLabel: Strings.personTalking,
            locallyMuted: item.locallyMuted,
            note: item.isSelf ? Strings.channelsSelfMarker : nil,
            status: item.state.statusIcons,
            action: { onOpen(item.session) }
        )
        .accessibilityIdentifier("person-row")
    }
}

extension PersonFlags {
    /// The state icons in the order of the mockups: what is shared first, then what is switched off.
    var statusIcons: [MumcordPersonStatus] {
        var icons: [MumcordPersonStatus] = []
        if streaming {
            icons.append(MumcordPersonStatus(icon: MumcordIcons.monitor, label: Strings.personStreaming, tone: .live))
        }
        if camera {
            icons.append(MumcordPersonStatus(icon: MumcordIcons.camera, label: Strings.personCamera, tone: .live))
        }
        if serverMuted {
            icons.append(MumcordPersonStatus(icon: MumcordIcons.micOff, label: Strings.personServerMuted, tone: .off))
        } else if muted {
            icons.append(MumcordPersonStatus(icon: MumcordIcons.micOff, label: Strings.personMuted, tone: .off))
        }
        if deafened {
            icons.append(MumcordPersonStatus(icon: MumcordIcons.headphonesOff, label: Strings.personDeafened, tone: .off))
        }
        return icons
    }
}

extension UnsupportedHint {
    /// The sentence of the hint; the name in it is untrusted text.
    var text: String {
        switch kind {
        case .fileTransfer: Strings.hintFileTransfer(personName)
        case .watchTogether: Strings.hintWatchTogether(personName)
        case .pluginContent: Strings.hintPluginContent(personName)
        }
    }
}
