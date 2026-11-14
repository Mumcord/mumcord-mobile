// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelTreeTest {
    private val root = channel(0u, "Root")

    @Test
    fun noRootChannelGivesNoTree() {
        val orphan = channel(4u, "Orphan", parentId = 9u)
        assertNull(buildChannelTree(1u, listOf(orphan), emptyList()))
    }

    @Test
    fun childrenAreOrderedByPositionThenNameIgnoringCase() {
        val channels = listOf(
            root,
            channel(1u, "zebra", parentId = 0u),
            channel(2u, "Alpha", parentId = 0u),
            channel(3u, "Last by name, first by position", parentId = 0u, position = -1),
            channel(4u, "beta", parentId = 0u),
        )
        val tree = checkNotNull(buildChannelTree(1u, channels, emptyList()))
        assertEquals(listOf(3u, 2u, 4u, 1u), tree.children.map { it.id })
    }

    @Test
    fun peopleAreGroupedPerChannelSortedByNameAndTheOwnUserIsMarked() {
        val channels = listOf(root, channel(1u, "Lobby", parentId = 0u))
        val people = listOf(
            person(10u, "mallory", channelId = 1u),
            person(11u, "Alice", channelId = 1u),
            person(12u, "Bob", channelId = 0u),
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
            channel(1u, "Lobby", parentId = 0u),
            channel(5u, "Loop A", parentId = 6u),
            channel(6u, "Loop B", parentId = 5u),
            channel(7u, "Orphan", parentId = 99u),
        )
        val people = listOf(person(10u, "Lost", channelId = 7u))
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
            channel(1u, "Games", parentId = 0u),
            channel(2u, "Chess", parentId = 1u),
        )
        val people = listOf(
            person(10u, "Alice", channelId = 0u),
            person(11u, "Bob", channelId = 2u),
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
