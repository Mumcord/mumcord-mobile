// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.Person

data class ChannelNode(
    val id: UInt,
    val name: String,
    val people: List<PersonEntry>,
    val children: List<ChannelNode>,
)

data class PersonEntry(
    val session: UInt,
    val name: String,
    val isSelf: Boolean,
)

/** One line of the channel list: the tree flattened in display order with its nesting depth. */
sealed interface TreeRow {
    val depth: Int

    data class ChannelRow(
        val id: UInt,
        val name: String,
        val peopleCount: Int,
        override val depth: Int,
    ) : TreeRow

    data class PersonRow(
        val session: UInt,
        val name: String,
        val isSelf: Boolean,
        override val depth: Int,
    ) : TreeRow
}

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
fun buildChannelTree(
    selfSession: UInt,
    channels: Collection<Channel>,
    people: Collection<Person>,
): ChannelNode? {
    val root = channels.filter { it.parentId == null }.minByOrNull { it.id } ?: return null
    val childrenByParent = channels.filter { it.parentId != null }.groupBy { it.parentId }
    val peopleByChannel = people.groupBy { it.channelId }

    fun node(channel: Channel): ChannelNode = ChannelNode(
        id = channel.id,
        name = channel.name,
        people = peopleByChannel[channel.id].orEmpty().sortedWith(personOrder).map {
            PersonEntry(session = it.session, name = it.name, isSelf = it.session == selfSession)
        },
        children = childrenByParent[channel.id].orEmpty().sortedWith(channelOrder).map(::node),
    )
    return node(root)
}

/** People of a channel come first, then its sub-channels, each one level deeper. */
fun ChannelNode.flatten(depth: Int = 0): List<TreeRow> = buildList {
    add(TreeRow.ChannelRow(id = id, name = name, peopleCount = people.size, depth = depth))
    people.forEach {
        add(TreeRow.PersonRow(it.session, it.name, it.isSelf, depth = depth + 1))
    }
    children.forEach { addAll(it.flatten(depth + 1)) }
}
