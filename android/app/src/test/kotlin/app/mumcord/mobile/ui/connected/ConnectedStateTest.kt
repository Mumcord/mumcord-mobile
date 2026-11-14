// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChannelPasswordState
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.MOCKUP_MUSIC
import app.mumcord.mobile.session.MOCKUP_ROOT
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.UnsupportedHint
import app.mumcord.mobile.session.UnsupportedKind
import app.mumcord.mobile.session.buildChannelTree
import app.mumcord.mobile.session.channel
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.person
import app.mumcord.mobile.session.withHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedStateTest {
    private val server = mockupServer()
    private val root = buildChannelTree(server.channels, server.people)
    private val session = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(server),
    ).fold(SessionState()) { state, event -> state.fold(event) }

    private fun rows(collapsed: Set<UInt> = emptySet(), query: String = "") =
        channelRows(root, MOCKUP_LOBBY, collapsed, query)

    private fun List<ChannelListRow>.names() = map {
        when (it) {
            is ChannelListRow.ChannelItem -> "#${it.name}"
            is ChannelListRow.PersonItem -> it.name
        }
    }

    private fun List<ChannelListRow>.channel(id: UInt) =
        filterIsInstance<ChannelListRow.ChannelItem>().first { it.id == id }

    @Test
    fun theRootComesFirstThenChannelsByPositionWithTheirPeopleByName() {
        assertEquals(
            listOf("#Nordlicht", "#Lobby", "Anna", "Ben", "Lea", "#Gaming", "Jonas", "Mira", "#Music", "#AFK"),
            rows().names(),
        )
    }

    @Test
    fun theRootAndItsChannelsShareTheFirstLevelAndDeeperOnesAreIndented() {
        val deep = buildChannelTree(
            server.channels + channel(9u, "Raid", parentId = MOCKUP_GAMING),
            server.people + person(20u, "Zoe", channelId = 9u),
        )
        val rows = channelRows(deep, MOCKUP_LOBBY, emptySet())
        assertEquals(0, rows.channel(MOCKUP_ROOT).depth)
        assertEquals(0, rows.channel(MOCKUP_GAMING).depth)
        assertEquals(1, rows.channel(9u).depth)
        assertEquals(1, rows.filterIsInstance<ChannelListRow.PersonItem>().first { it.name == "Mira" }.depth)
        assertEquals(2, rows.filterIsInstance<ChannelListRow.PersonItem>().first { it.name == "Zoe" }.depth)
    }

    @Test
    fun channelsCarryCountOwnChannelLockAndWhetherTheyFold() {
        val rows = rows()
        val lobby = rows.channel(MOCKUP_LOBBY)
        assertEquals(3, lobby.peopleCount)
        assertTrue(lobby.current)
        assertEquals(true, lobby.expanded)
        val music = rows.channel(MOCKUP_MUSIC)
        assertEquals(0, music.peopleCount)
        assertTrue(music.locked)
        assertNull("An empty channel has nothing to fold", music.expanded)
        assertEquals(false, rows.channel(MOCKUP_GAMING).current)
    }

    @Test
    fun peopleCarryTheirStateIcons() {
        val people = rows().filterIsInstance<ChannelListRow.PersonItem>().associateBy { it.name }
        assertTrue(people.getValue("Ben").talking)
        assertTrue(people.getValue("Lea").isSelf)
        assertEquals(PersonFlags(streaming = true), people.getValue("Mira").state)
        assertEquals(PersonFlags(muted = true, deafened = true), people.getValue("Jonas").state)
    }

    @Test
    fun aCollapsedChannelHidesItsPeopleAndSubChannelsButKeepsItsCount() {
        val rows = rows(collapsed = setOf(MOCKUP_LOBBY))
        assertEquals(listOf("#Nordlicht", "#Lobby", "#Gaming", "Jonas", "Mira", "#Music", "#AFK"), rows.names())
        assertEquals(false, rows.channel(MOCKUP_LOBBY).expanded)
        assertEquals(3, rows.channel(MOCKUP_LOBBY).peopleCount)
        assertEquals(listOf("#Nordlicht"), rows(collapsed = setOf(MOCKUP_ROOT)).names())
    }

    @Test
    fun searchFindsPeopleAndChannelsIgnoringCaseAndKeepsTheirParents() {
        assertEquals(listOf("#Nordlicht", "#Gaming", "Mira"), rows(query = "mIR").names())
        assertEquals(listOf("#Nordlicht", "#Music"), rows(query = " music ").names())
        assertEquals(emptyList<String>(), rows(query = "nobody").names())
    }

    @Test
    fun searchIgnoresCollapsedChannelsAndOffersNoFolding() {
        val rows = rows(collapsed = setOf(MOCKUP_GAMING), query = "jonas")
        assertEquals(listOf("#Nordlicht", "#Gaming", "Jonas"), rows.names())
        assertNull(rows.channel(MOCKUP_GAMING).expanded)
        assertEquals("The count is of the channel, not of the matches", 2, rows.channel(MOCKUP_GAMING).peopleCount)
    }

    @Test
    fun noServerMeansNoConnectedScreens() {
        assertNull(connectedUiState(SessionState(), ConnectedLocal(), "Nordlicht"))
    }

    @Test
    fun theHeaderNamesTheServerAndCountsEverybodyOnline() {
        val state = connectedUiState(session, ConnectedLocal(), "Nordlicht")!!
        assertEquals("Nordlicht", state.serverName)
        assertEquals(5, state.peopleOnline)
        assertNull(state.sheet)
        assertNull(state.denied)
    }

    @Test
    fun theSearchTextOnlyFiltersWhileSearchIsOpen() {
        val closed = connectedUiState(session, ConnectedLocal(query = "mira"), "Nordlicht")!!
        assertEquals(10, closed.rows.size)
        val open = connectedUiState(session, ConnectedLocal(searching = true, query = "mira"), "Nordlicht")!!
        assertEquals(3, open.rows.size)
    }

    @Test
    fun thePersonSheetShowsThePersonWithTheSliderPositionWhileItMoves() {
        val local = ConnectedLocal(personSession = MOCKUP_JONAS)
        assertEquals(
            ConnectedSheet.PersonSheet(
                session = MOCKUP_JONAS,
                name = "Jonas",
                channelName = "Gaming",
                volumePercent = 100,
                locallyMuted = false,
                state = PersonFlags(muted = true, deafened = true),
            ),
            connectedUiState(session, local, "Nordlicht")!!.sheet,
        )
        val moving = connectedUiState(session, local.copy(volumeDraft = 150), "Nordlicht")!!.sheet
        assertEquals(150, (moving as ConnectedSheet.PersonSheet).volumePercent)
    }

    @Test
    fun thePersonSheetClosesWhenThePersonLeaves() {
        val left = session.fold(BridgeEvent.PersonRemoved(MOCKUP_JONAS))
        assertNull(connectedUiState(left, ConnectedLocal(personSession = MOCKUP_JONAS), "Nordlicht")!!.sheet)
    }

    @Test
    fun theChannelMenuOffersJoinExceptForTheOwnChannelAndFoldingWhenThereIsSomethingToFold() {
        fun menu(id: UInt, collapsed: Set<UInt> = emptySet()) =
            connectedUiState(session, ConnectedLocal(menuChannelId = id, collapsed = collapsed), "Nordlicht")!!.sheet
        assertEquals(ConnectedSheet.ChannelMenu(MOCKUP_LOBBY, "Lobby", expanded = true, canJoin = false), menu(MOCKUP_LOBBY))
        assertEquals(
            ConnectedSheet.ChannelMenu(MOCKUP_GAMING, "Gaming", expanded = false, canJoin = true),
            menu(MOCKUP_GAMING, collapsed = setOf(MOCKUP_GAMING)),
        )
        assertEquals(ConnectedSheet.ChannelMenu(MOCKUP_MUSIC, "Music", expanded = null, canJoin = true), menu(MOCKUP_MUSIC))
    }

    @Test
    fun aPasswordQuestionComesBeforeEveryOtherSheetAndSaysWhenThePasswordWasWrong() {
        val asked = session.fold(BridgeEvent.ChannelPasswordChanged(MOCKUP_MUSIC, ChannelPasswordState.WRONG))
        val local = ConnectedLocal(personSession = MOCKUP_JONAS, password = "abc")
        assertEquals(
            ConnectedSheet.ChannelPassword(channelName = "Music", wrong = true, password = "abc"),
            connectedUiState(asked, local, "Nordlicht")!!.sheet,
        )
    }

    @Test
    fun aRefusalNamesTheChannelAndKeepsTheServersWords() {
        val refused = session.fold(BridgeEvent.ActionDenied(MOCKUP_MUSIC, "Permission denied."))
        assertEquals(DeniedBanner("Music", "Permission denied."), connectedUiState(refused, ConnectedLocal(), "Nordlicht")!!.denied)
        val general = session.fold(BridgeEvent.ActionDenied(null, ""))
        assertEquals(DeniedBanner(null, ""), connectedUiState(general, ConnectedLocal(), "Nordlicht")!!.denied)
    }

    @Test
    fun hintsOfTheSessionReachTheScreen() {
        val hint = UnsupportedHint(1, "Jonas", UnsupportedKind.FileTransfer)
        assertEquals(listOf(hint), connectedUiState(session.withHint(hint), ConnectedLocal(), "Nordlicht")!!.hints)
    }

    @Test
    fun initialsAndAvatarColourFollowTheName() {
        assertEquals("NO", "Nordlicht".initials(2))
        assertEquals("J", "jonas".initials(1))
        assertEquals("?", "<>".initials(1))
        assertEquals(avatarSlot("Jonas"), avatarSlot("Jonas"))
        assertTrue(avatarSlot("Jonas") in 1..8)
    }
}
