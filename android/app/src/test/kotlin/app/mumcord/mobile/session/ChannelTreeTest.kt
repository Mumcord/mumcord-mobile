// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelTreeTest {
    private val root = Channel(id = 0u, parentId = null, name = "Root", position = 0)

    @Test
    fun noRootChannelGivesNoTree() {
        val orphan = Channel(id = 4u, parentId = 9u, name = "Orphan", position = 0)
        assertNull(buildChannelTree(1u, listOf(orphan), emptyList()))
    }

    @Test
    fun childrenAreOrderedByPositionThenNameIgnoringCase() {
        val channels = listOf(
            root,
            Channel(id = 1u, parentId = 0u, name = "zebra", position = 0),
            Channel(id = 2u, parentId = 0u, name = "Alpha", position = 0),
            Channel(id = 3u, parentId = 0u, name = "Last by name, first by position", position = -1),
            Channel(id = 4u, parentId = 0u, name = "beta", position = 0),
        )
        val tree = checkNotNull(buildChannelTree(1u, channels, emptyList()))
        assertEquals(listOf(3u, 2u, 4u, 1u), tree.children.map { it.id })
    }

    @Test
    fun peopleAreGroupedPerChannelSortedByNameAndTheOwnUserIsMarked() {
        val channels = listOf(root, Channel(id = 1u, parentId = 0u, name = "Lobby", position = 0))
        val people = listOf(
            Person(session = 10u, channelId = 1u, name = "mallory"),
            Person(session = 11u, channelId = 1u, name = "Alice"),
            Person(session = 12u, channelId = 0u, name = "Bob"),
        )
        val tree = checkNotNull(buildChannelTree(11u, channels, people))
        assertEquals(listOf(PersonEntry(12u, "Bob", isSelf = false)), tree.people)
        assertEquals(
            listOf(PersonEntry(11u, "Alice", isSelf = true), PersonEntry(10u, "mallory", isSelf = false)),
            tree.children.single().people,
        )
    }

    @Test
    fun channelsThatDoNotHangOffTheRootAreLeftOut() {
        val channels = listOf(
            root,
            Channel(id = 1u, parentId = 0u, name = "Lobby", position = 0),
            Channel(id = 5u, parentId = 6u, name = "Loop A", position = 0),
            Channel(id = 6u, parentId = 5u, name = "Loop B", position = 0),
            Channel(id = 7u, parentId = 99u, name = "Orphan", position = 0),
        )
        val people = listOf(Person(session = 10u, channelId = 7u, name = "Lost"))
        val rows = checkNotNull(buildChannelTree(1u, channels, people)).flatten()
        assertEquals(
            listOf(
                TreeRow.ChannelRow(id = 0u, name = "Root", peopleCount = 0, depth = 0),
                TreeRow.ChannelRow(id = 1u, name = "Lobby", peopleCount = 0, depth = 1),
            ),
            rows,
        )
    }

    @Test
    fun flattenListsPeopleBeforeSubChannelsWithGrowingDepth() {
        val channels = listOf(
            root,
            Channel(id = 1u, parentId = 0u, name = "Games", position = 0),
            Channel(id = 2u, parentId = 1u, name = "Chess", position = 0),
        )
        val people = listOf(
            Person(session = 10u, channelId = 0u, name = "Alice"),
            Person(session = 11u, channelId = 2u, name = "Bob"),
        )
        val rows = checkNotNull(buildChannelTree(10u, channels, people)).flatten()
        assertEquals(
            listOf(
                TreeRow.ChannelRow(id = 0u, name = "Root", peopleCount = 1, depth = 0),
                TreeRow.PersonRow(session = 10u, name = "Alice", isSelf = true, depth = 1),
                TreeRow.ChannelRow(id = 1u, name = "Games", peopleCount = 0, depth = 1),
                TreeRow.ChannelRow(id = 2u, name = "Chess", peopleCount = 1, depth = 2),
                TreeRow.PersonRow(session = 11u, name = "Bob", isSelf = false, depth = 3),
            ),
            rows,
        )
    }
}
