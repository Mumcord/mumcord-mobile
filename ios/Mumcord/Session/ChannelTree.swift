// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// A channel with the people in it and its sub-channels, both in display order.
struct ChannelNode: Equatable {
    var channel: Channel
    var people: [Person]
    var children: [ChannelNode]
}

private func channelOrder(_ a: Channel, _ b: Channel) -> Bool {
    if a.position != b.position { return a.position < b.position }
    let names = a.name.caseInsensitiveCompare(b.name)
    if names != .orderedSame { return names == .orderedAscending }
    return a.id < b.id
}

private func personOrder(_ a: Person, _ b: Person) -> Bool {
    let names = a.name.caseInsensitiveCompare(b.name)
    if names != .orderedSame { return names == .orderedAscending }
    return a.session < b.session
}

/// Builds the tree from the root channel (the one without a parent) downwards. Channels are
/// ordered by position, then name; people by name. Channels that cannot be reached from the
/// root (their parent is unknown or was removed) and the people in them are left out, so
/// inconsistent server data can never produce a loop.
func buildChannelTree(channels: some Collection<Channel>, people: some Collection<Person>) -> ChannelNode? {
    guard let root = channels.filter({ $0.parentId == nil }).min(by: { $0.id < $1.id }) else { return nil }
    var childrenByParent: [UInt32: [Channel]] = [:]
    for channel in channels {
        if let parent = channel.parentId { childrenByParent[parent, default: []].append(channel) }
    }
    let peopleByChannel = Dictionary(grouping: people, by: \.channelId)

    func node(_ channel: Channel) -> ChannelNode {
        ChannelNode(
            channel: channel,
            people: (peopleByChannel[channel.id] ?? []).sorted(by: personOrder),
            children: (childrenByParent[channel.id] ?? []).sorted(by: channelOrder).map(node)
        )
    }
    return node(root)
}
