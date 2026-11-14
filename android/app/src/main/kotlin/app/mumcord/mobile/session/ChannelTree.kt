// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.Person

/** A channel with the people in it and its sub-channels, both in display order. */
data class ChannelNode(
    val channel: Channel,
    val people: List<Person>,
    val children: List<ChannelNode>,
)

private val channelOrder: Comparator<Channel> =
    compareBy<Channel> { it.position }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.id }

private val personOrder: Comparator<Person> =
    compareBy<Person, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.session }

/**
 * Builds the tree from the root channel (the one without a parent) downwards. Channels are
 * ordered by position, then name; people by name. Channels that cannot be reached from the
 * root (their parent is unknown or was removed) and the people in them are left out, so
 * inconsistent server data can never produce a loop.
 */
fun buildChannelTree(channels: Collection<Channel>, people: Collection<Person>): ChannelNode? {
    val root = channels.filter { it.parentId == null }.minByOrNull { it.id } ?: return null
    val childrenByParent = channels.filter { it.parentId != null }.groupBy { it.parentId }
    val peopleByChannel = people.groupBy { it.channelId }

    fun node(channel: Channel): ChannelNode = ChannelNode(
        channel = channel,
        people = peopleByChannel[channel.id].orEmpty().sortedWith(personOrder),
        children = childrenByParent[channel.id].orEmpty().sortedWith(channelOrder).map(::node),
    )
    return node(root)
}
