// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.ui.chat.ChatRow
import app.mumcord.mobile.ui.streams.stripMessages
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the connected screens use two columns, and what they then show (AC-5.31). */
class ConnectedLayoutTest {
    @Test
    fun phonesStayInOneColumnAlsoInLandscape() {
        assertEquals(ConnectedLayout.OneColumn, connectedLayout(390.dp, 844.dp))
        // Wide but low: the phone's landscape stream keeps the whole screen.
        assertEquals(ConnectedLayout.OneColumn, connectedLayout(844.dp, 390.dp))
        assertEquals(ConnectedLayout.OneColumn, connectedLayout(915.dp, 412.dp))
    }

    @Test
    fun tabletsUseTwoColumnsInPortraitAndLandscape() {
        assertEquals(ConnectedLayout.TwoColumns, connectedLayout(1180.dp, 820.dp))
        assertEquals(ConnectedLayout.TwoColumns, connectedLayout(820.dp, 1180.dp))
        assertEquals(ConnectedLayout.TwoColumns, connectedLayout(600.dp, 480.dp))
    }

    @Test
    fun aHalfOfASplitScreenBelow600DpIsOneColumn() {
        assertEquals(ConnectedLayout.OneColumn, connectedLayout(599.dp, 820.dp))
        assertEquals(ConnectedLayout.OneColumn, connectedLayout(1180.dp, 479.dp))
    }

    @Test
    fun theChatTabOfThePhoneIsPartOfChannelsInTwoColumns() {
        assertEquals(ConnectedTab.Channels, ConnectedTab.Chat.inTwoColumns())
        assertEquals(ConnectedTab.Messages, ConnectedTab.Messages.inTwoColumns())
        assertEquals(listOf(ConnectedTab.Channels, ConnectedTab.Messages, ConnectedTab.More), RAIL_TABS)
    }

    @Test
    fun theChannelChatIsOnScreenBesideTheChannelsOnlyInTwoColumns() {
        assertFalse(ConnectedLocal(tab = ConnectedTab.Channels).showsChannelChat)
        assertTrue(ConnectedLocal(tab = ConnectedTab.Channels, twoColumns = true).showsChannelChat)
        assertTrue(ConnectedLocal(tab = ConnectedTab.Chat).showsChannelChat)
        assertTrue(ConnectedLocal(tab = ConnectedTab.Chat, twoColumns = true).showsChannelChat)
        assertFalse(ConnectedLocal(tab = ConnectedTab.Messages, twoColumns = true).showsChannelChat)
        assertFalse(ConnectedLocal(tab = ConnectedTab.More, twoColumns = true).showsChannelChat)
    }

    @Test
    fun theStripUnderAStreamShowsTheTwoNewestMessages() {
        val lobby = ChatId.Channel(1u)
        val rows = listOf(
            ChatRow.Day(LocalDate.of(2026, 5, 4)),
            ChatRow.Message(chatMessage(id = "1", chat = lobby, senderName = "Anna"), showHeader = true),
            ChatRow.Message(chatMessage(id = "2", chat = lobby, senderName = "Ben"), showHeader = true),
            ChatRow.Message(chatMessage(id = "3", chat = lobby, senderName = "Mira"), showHeader = true),
        )
        assertEquals(listOf("2", "3"), stripMessages(rows).map { it.id })
        assertEquals(emptyList<String>(), stripMessages(rows.take(1)).map { it.id })
    }
}
