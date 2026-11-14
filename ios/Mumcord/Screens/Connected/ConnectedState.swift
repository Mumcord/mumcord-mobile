// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The four areas of the connected app, in the order of the bottom tab bar.
enum ConnectedTab: Int, CaseIterable {
    case channels
    case chat
    case messages
    case more
}

/// One line of the channel list: the tree flattened in display order.
enum ChannelListRow: Equatable, Identifiable {
    case channel(ChannelItem)
    case person(PersonItem)

    var id: String {
        switch self {
        case .channel(let item): "channel-\(item.id)"
        case .person(let item): "person-\(item.session)"
        }
    }

    var depth: Int {
        switch self {
        case .channel(let item): item.depth
        case .person(let item): item.depth
        }
    }
}

struct ChannelItem: Equatable {
    let id: UInt32
    let name: String
    let depth: Int
    /// The people directly in the channel, also while a search hides some.
    let peopleCount: Int
    let current: Bool
    /// The channel is restricted, so joining may ask for a password.
    let locked: Bool
    /// True or false for a channel that can be folded, nil when it has nothing to fold or a
    /// search decides what is visible.
    let expanded: Bool?
}

struct PersonItem: Equatable {
    let session: UInt32
    let name: String
    let depth: Int
    let isSelf: Bool
    let talking: Bool
    let locallyMuted: Bool
    let state: PersonFlags
}

/// What the state icons of a person show. Being deafened by the server counts as deafened.
struct PersonFlags: Equatable {
    var muted = false
    var deafened = false
    var serverMuted = false
    var streaming = false
    var camera = false
}

private extension Person {
    var flags: PersonFlags {
        PersonFlags(
            muted: selfMuted,
            deafened: selfDeafened || serverDeafened,
            serverMuted: serverMuted,
            streaming: streaming,
            camera: camera
        )
    }
}

/// The visible lines of the tree. The root channel comes first and its sub-channels follow on
/// the same level, as in the desktop app; deeper channels are indented one step per level.
/// People of a channel come before its sub-channels. A collapsed channel hides both.
///
/// A non-blank `query` filters by name, ignoring case: a person shows when the name matches, a
/// channel when its name matches or something inside it shows. Collapsing is ignored meanwhile,
/// so a match is never hidden.
func channelRows(
    root: ChannelNode?,
    selfChannelId: UInt32?,
    collapsed: Set<UInt32>,
    query: String = ""
) -> [ChannelListRow] {
    guard let root else { return [] }
    let needle = query.trimmed
    let searching = !needle.isEmpty
    let shown: ChannelNode
    if searching {
        guard let filtered = root.filtered(needle) else { return [] }
        shown = filtered
    } else {
        shown = root
    }
    var counts: [UInt32: Int] = [:]
    root.collectCounts(into: &counts)
    var rows: [ChannelListRow] = []

    func add(_ node: ChannelNode, level: Int) {
        let channel = node.channel
        // The root and its sub-channels share the first level.
        let depth = max(level - 1, 0)
        let foldable = !node.people.isEmpty || !node.children.isEmpty
        let open = searching || !collapsed.contains(channel.id)
        rows.append(.channel(ChannelItem(
            id: channel.id,
            name: channel.name,
            depth: depth,
            peopleCount: counts[channel.id] ?? 0,
            current: channel.id == selfChannelId,
            locked: channel.restricted,
            expanded: foldable && !searching ? open : nil
        )))
        guard open else { return }
        for person in node.people {
            rows.append(.person(PersonItem(
                session: person.session,
                name: person.name,
                depth: depth + 1,
                isSelf: person.isSelf,
                talking: person.talking,
                locallyMuted: person.locallyMuted,
                state: person.flags
            )))
        }
        for child in node.children {
            add(child, level: level + 1)
        }
    }
    add(shown, level: 0)
    return rows
}

private extension ChannelNode {
    func collectCounts(into counts: inout [UInt32: Int]) {
        counts[channel.id] = people.count
        for child in children {
            child.collectCounts(into: &counts)
        }
    }

    func filtered(_ needle: String) -> ChannelNode? {
        let matchingPeople = people.filter { $0.name.localizedCaseInsensitiveContains(needle) }
        let matchingChildren = children.compactMap { $0.filtered(needle) }
        let matches = channel.name.localizedCaseInsensitiveContains(needle)
        guard matches || !matchingPeople.isEmpty || !matchingChildren.isEmpty else { return nil }
        return ChannelNode(channel: channel, people: matchingPeople, children: matchingChildren)
    }
}

/// What the user did on the connected screens; everything else follows from the session.
struct ConnectedLocal: Equatable {
    var tab = ConnectedTab.channels
    var collapsed: Set<UInt32> = []
    var searching = false
    var query = ""
    /// The person whose sheet is open.
    var personSession: UInt32?
    /// The slider position while it is moved, before the engine reports the new volume.
    var volumeDraft: Int?
    /// The channel whose menu is open.
    var menuChannelId: UInt32?
    /// The channel password being typed.
    var password = ""
}

/// The sheet over the connected screens. At most one at a time.
enum ConnectedSheet: Equatable {
    case channelPassword(channelName: String, wrong: Bool, password: String)
    case person(PersonSheetState)
    /// `expanded` as in `ChannelItem`; `canJoin` is false for the own channel.
    case channelMenu(id: UInt32, name: String, expanded: Bool?, canJoin: Bool)
}

struct PersonSheetState: Equatable {
    let session: UInt32
    let name: String
    let channelName: String
    let volumePercent: Int
    let locallyMuted: Bool
    let state: PersonFlags
}

/// A refusal by the server; `channelName` and `detail` are untrusted text.
struct DeniedBanner: Equatable {
    let channelName: String?
    let detail: String
}

struct ConnectedUiState: Equatable {
    let tab: ConnectedTab
    let serverName: String
    let peopleOnline: Int
    let rows: [ChannelListRow]
    let searching: Bool
    let query: String
    let reconnecting: Reconnecting?
    let denied: DeniedBanner?
    let hints: [UnsupportedHint]
    let sheet: ConnectedSheet?
}

/// The connected screens as a value, or nil while no server is shown.
func connectedUiState(session: SessionState, local: ConnectedLocal, serverName: String) -> ConnectedUiState? {
    guard session.showsServer, let server = session.server else { return nil }
    let rows = channelRows(
        root: server.root,
        selfChannelId: server.selfChannelId,
        collapsed: local.collapsed,
        query: local.searching ? local.query : ""
    )
    let sheet: ConnectedSheet?
    // The server waits for the password, so this question comes first.
    if let prompt = session.channelPassword {
        sheet = .channelPassword(
            channelName: server.channels[prompt.channelId]?.name ?? "",
            wrong: prompt.wrong,
            password: local.password
        )
    } else if let person = local.personSession.flatMap({ server.people[$0] }) {
        sheet = .person(PersonSheetState(
            session: person.session,
            name: person.name,
            channelName: server.channels[person.channelId]?.name ?? "",
            volumePercent: local.volumeDraft ?? Int(person.localVolume),
            locallyMuted: person.locallyMuted,
            state: person.flags
        ))
    } else if let menuChannel = local.menuChannelId.flatMap({ server.channels[$0] }) {
        var expanded: Bool?
        for case .channel(let item) in rows where item.id == menuChannel.id {
            expanded = item.expanded
        }
        sheet = .channelMenu(
            id: menuChannel.id,
            name: menuChannel.name,
            expanded: expanded,
            canJoin: menuChannel.id != server.selfChannelId
        )
    } else {
        sheet = nil
    }
    var reconnecting: Reconnecting?
    if case .reconnecting(let progress) = session.phase { reconnecting = progress }
    return ConnectedUiState(
        tab: local.tab,
        serverName: serverName,
        peopleOnline: server.people.count,
        rows: rows,
        searching: local.searching,
        query: local.query,
        reconnecting: reconnecting,
        denied: session.denied.map { denied in
            DeniedBanner(channelName: denied.channelId.flatMap { server.channels[$0] }?.name, detail: denied.detail)
        },
        hints: session.hints,
        sheet: sheet
    )
}
