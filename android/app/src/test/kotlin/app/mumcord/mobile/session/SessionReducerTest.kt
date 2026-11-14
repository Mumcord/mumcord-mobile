// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    private val server = ServerState(
        selfSession = 10u,
        channels = listOf(
            Channel(id = 0u, parentId = null, name = "Root", position = 0),
            Channel(id = 1u, parentId = 0u, name = "Lobby", position = 0),
        ),
        people = listOf(Person(session = 10u, channelId = 0u, name = "Me")),
    )

    private fun fold(vararg events: BridgeEvent): SessionState =
        events.fold(SessionState()) { state, event -> state.fold(event) }

    private val connected = arrayOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connecting),
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(server),
    )

    private fun names(state: SessionState): List<String> = state.server?.rows.orEmpty().map {
        when (it) {
            is TreeRow.ChannelRow -> it.name
            is TreeRow.PersonRow -> it.name
        }
    }

    @Test
    fun aNewSessionIsIdleAndShowsNoServer() {
        val state = SessionState()
        assertEquals(ConnectionPhase.Idle, state.phase)
        assertFalse(state.isBusy)
        assertFalse(state.showsServer)
    }

    @Test
    fun connectingIsBusyButShowsNoServerYet() {
        val state = fold(BridgeEvent.ConnectionChanged(ConnectionState.Connecting))
        assertEquals(ConnectionPhase.Connecting, state.phase)
        assertTrue(state.isBusy)
        assertFalse(state.showsServer)
    }

    @Test
    fun anUnknownCertificateBecomesAPromptWithoutPreviousFingerprint() {
        val state = fold(
            BridgeEvent.ConnectionChanged(ConnectionState.Connecting),
            BridgeEvent.CertificatePromptRequired(CertificatePrompt("AA:BB", null)),
        )
        assertEquals(CertificatePromptState("AA:BB", null), state.certificatePrompt)
        assertFalse(checkNotNull(state.certificatePrompt).isChanged)
    }

    @Test
    fun aChangedCertificateKeepsThePreviousFingerprint() {
        val state = fold(BridgeEvent.CertificatePromptRequired(CertificatePrompt("CC:DD", "AA:BB")))
        assertEquals(CertificatePromptState("CC:DD", "AA:BB"), state.certificatePrompt)
        assertTrue(checkNotNull(state.certificatePrompt).isChanged)
    }

    @Test
    fun connectedClearsThePromptAndSyncShowsTheServer() {
        val state = fold(
            BridgeEvent.ConnectionChanged(ConnectionState.Connecting),
            BridgeEvent.CertificatePromptRequired(CertificatePrompt("AA:BB", null)),
            BridgeEvent.ConnectionChanged(ConnectionState.Connected),
            BridgeEvent.ServerSynced(server),
        )
        assertNull(state.certificatePrompt)
        assertTrue(state.showsServer)
        assertEquals(listOf("Root", "Me", "Lobby"), names(state))
    }

    @Test
    fun connectedWithoutSyncDoesNotShowTheServer() {
        val state = fold(BridgeEvent.ConnectionChanged(ConnectionState.Connected))
        assertTrue(state.isBusy)
        assertFalse(state.showsServer)
    }

    @Test
    fun channelsChangedAddsAndRenamesChannels() {
        val state = fold(
            *connected,
            BridgeEvent.ChannelsChanged(
                listOf(
                    Channel(id = 1u, parentId = 0u, name = "Lounge", position = 0),
                    Channel(id = 2u, parentId = 0u, name = "Games", position = 0),
                ),
            ),
        )
        assertEquals(listOf("Root", "Me", "Games", "Lounge"), names(state))
    }

    @Test
    fun channelRemovedDropsTheChannelAndItsSubChannels() {
        val state = fold(
            *connected,
            BridgeEvent.ChannelsChanged(listOf(Channel(id = 2u, parentId = 1u, name = "Sub", position = 0))),
            BridgeEvent.ChannelRemoved(1u),
        )
        assertEquals(listOf("Root", "Me"), names(state))
    }

    @Test
    fun personChangedAddsAPersonAndMovesThemBetweenChannels() {
        val joined = fold(*connected, BridgeEvent.PersonChanged(Person(11u, 0u, "Alice")))
        assertEquals(listOf("Root", "Alice", "Me", "Lobby"), names(joined))

        val moved = joined.fold(BridgeEvent.PersonChanged(Person(11u, 1u, "Alice")))
        assertEquals(listOf("Root", "Me", "Lobby", "Alice"), names(moved))
    }

    @Test
    fun personRemovedDropsThePerson() {
        val state = fold(
            *connected,
            BridgeEvent.PersonChanged(Person(11u, 1u, "Alice")),
            BridgeEvent.PersonRemoved(11u),
        )
        assertEquals(listOf("Root", "Me", "Lobby"), names(state))
    }

    @Test
    fun changesBeforeTheServerIsSyncedAreIgnored() {
        val state = fold(
            BridgeEvent.PersonChanged(Person(11u, 1u, "Alice")),
            BridgeEvent.ChannelRemoved(1u),
        )
        assertNull(state.server)
    }

    @Test
    fun reconnectingKeepsTheLastKnownServerVisible() {
        val state = fold(*connected, BridgeEvent.ConnectionChanged(ConnectionState.Reconnecting(2u, 5u, 4u)))
        assertEquals(ConnectionPhase.Reconnecting(attempt = 2, maxAttempts = 5), state.phase)
        assertTrue(state.showsServer)
        assertTrue(state.isBusy)
    }

    @Test
    fun disconnectingOnRequestGoesBackToIdleAndForgetsTheServer() {
        val state = fold(*connected, BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null)))
        assertEquals(SessionState(), state)
    }

    @Test
    fun disconnectingWithAnErrorFailsAndForgetsServerAndPrompt() {
        val state = fold(
            *connected,
            BridgeEvent.CertificatePromptRequired(CertificatePrompt("AA:BB", null)),
            BridgeEvent.ConnectionChanged(
                ConnectionState.Disconnected(BridgeException.ServerUnreachable("refused")),
            ),
        )
        assertEquals(SessionState(phase = ConnectionPhase.Failed(ConnectionError.ServerUnreachable)), state)
        assertFalse(state.isBusy)
    }

    @Test
    fun everyEngineErrorMapsToItsOwnConnectionError() {
        val mapping = mapOf(
            BridgeException.NotConnected("d") to ConnectionError.NotConnected,
            BridgeException.ServerUnreachable("d") to ConnectionError.ServerUnreachable,
            BridgeException.WrongPassword("d") to ConnectionError.WrongPassword,
            BridgeException.NameInUse("d") to ConnectionError.NameInUse,
            BridgeException.CertificateRejected("d") to ConnectionError.CertificateRejected,
            BridgeException.CertificateChanged("d") to ConnectionError.CertificateChanged,
            BridgeException.ServerTooOld("d") to ConnectionError.ServerTooOld,
            BridgeException.InvalidInput("d") to ConnectionError.InvalidInput,
            BridgeException.SecureStorageUnavailable("d") to ConnectionError.SecureStorageUnavailable,
            BridgeException.Io("d") to ConnectionError.Io,
            BridgeException.Internal("d") to ConnectionError.Internal,
        )
        assertEquals(ConnectionError.entries.toSet(), mapping.values.toSet())
        mapping.forEach { (exception, error) -> assertEquals(error, exception.toConnectionError()) }
    }
}
