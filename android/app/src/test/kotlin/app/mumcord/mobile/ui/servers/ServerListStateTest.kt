// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.savedServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerListStateTest {
    private val servers = listOf(
        savedServer(1, "Nordlicht"),
        savedServer(2, "Lantern Guild", address = "lanternguild.example", port = 40000u),
    )

    @Test
    fun rowsShowNameAddressPingAndHighlightTheFirstServer() {
        val rows = serverRows(
            SessionState(servers = servers, serversLoaded = true),
            mapOf(1L to PingState.Online(peopleOnline = 5, latencyMs = 24), 2L to PingState.Offline),
        )

        assertEquals(
            listOf(
                ServerRow(1, "Nordlicht", "N", "nordlicht.example", PingState.Online(5, 24), connecting = false, highlighted = true),
                ServerRow(2, "Lantern Guild", "LG", "lanternguild.example:40000", PingState.Offline, connecting = false, highlighted = false),
            ),
            rows,
        )
    }

    @Test
    fun aServerThatWasNotAskedYetHasNoPingState() {
        val rows = serverRows(SessionState(servers = servers, serversLoaded = true), emptyMap())

        assertEquals(listOf(null, null), rows.map { it.ping })
    }

    @Test
    fun onlyTheServerOfALiveConnectionIsConnecting() {
        fun connecting(phase: ConnectionPhase) =
            serverRows(SessionState(phase = phase, servers = servers, activeServerId = 2), emptyMap()).map { it.connecting }

        assertEquals(listOf(false, true), connecting(ConnectionPhase.Connecting))
        assertEquals(listOf(false, false), connecting(ConnectionPhase.Failed(ConnectionError.Banned)))
        assertEquals(listOf(false, false), connecting(ConnectionPhase.Idle))
    }

    @Test
    fun aPingAnswerBecomesOnlineAndSilenceBecomesOffline() {
        assertEquals(PingState.Online(12, 31), ServerPing(latencyMs = 31u, peopleOnline = 12u, maxPeople = 100u).toPingState())
        assertEquals(PingState.Offline, null.toPingState())
    }

    @Test
    fun initialsAreTheFirstLettersOfTheFirstTwoWords() {
        assertEquals("N", initials("Nordlicht"))
        assertEquals("LG", initials("  lantern   guild hall "))
        assertEquals("ÄÖ", initials("ärger österreich"))
        assertEquals("😀", initials("😀smile"))
        assertEquals("?", initials("   "))
    }
}
