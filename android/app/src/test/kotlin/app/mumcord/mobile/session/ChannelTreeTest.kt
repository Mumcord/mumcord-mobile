// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelTreeTest {
    @Test
    fun withoutARootThereIsNoTree() {
        assertNull(buildChannelTree(listOf(channel(1u, "Lobby", parentId = 0u)), emptyList()))
    }

    @Test
    fun channelsAreOrderedByPositionThenNameAndPeopleByName() {
        val root = buildChannelTree(
            channels = listOf(
                channel(0u, "Root"),
                channel(1u, "beta", parentId = 0u, position = 1),
                channel(2u, "Alpha", parentId = 0u, position = 1),
                channel(3u, "Zulu", parentId = 0u, position = 0),
            ),
            people = listOf(person(2u, "bob"), person(1u, "Alice"), person(3u, "Carl", channelId = 3u)),
        )!!
        assertEquals(listOf("Zulu", "Alpha", "beta"), root.children.map { it.channel.name })
        assertEquals(listOf("Alice", "bob"), root.people.map { it.name })
        assertEquals(listOf("Carl"), root.children.first().people.map { it.name })
    }

    @Test
    fun channelsThatDoNotHangOnTheRootAndTheirPeopleAreLeftOut() {
        val root = buildChannelTree(
            channels = listOf(
                channel(0u, "Root"),
                // Two channels that name each other as parent, and one whose parent is gone.
                channel(5u, "Loop A", parentId = 6u),
                channel(6u, "Loop B", parentId = 5u),
                channel(7u, "Orphan", parentId = 99u),
            ),
            people = listOf(person(1u, "Lost", channelId = 7u)),
        )!!
        assertEquals(emptyList<ChannelNode>(), root.children)
        assertEquals(emptyList<String>(), root.people.map { it.name })
    }
}
