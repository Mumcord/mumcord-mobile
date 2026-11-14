// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChannelPasswordState
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.ConnectionState
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
            channel(0u, "Root"),
            channel(1u, "Lobby", parentId = 0u),
        ),
        people = listOf(person(10u, "Me", channelId = 0u)),
    )

    private fun fold(vararg events: BridgeEvent): SessionState =
        events.fold(SessionState()) { state, event -> state.fold(event) }

    private val connected = arrayOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connecting),
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(server),
    )

    private fun names(state: SessionState): List<String> {
        fun ChannelNode.names(): List<String> = listOf(channel.name) + people.map { it.name } + children.flatMap { it.names() }
        return state.server?.root?.names().orEmpty()
    }

    @Test
    fun aChannelPasswordQuestionIsAskedMarkedWrongAndEnds() {
        val asked = fold(*connected, BridgeEvent.ChannelPasswordChanged(1u, ChannelPasswordState.REQUIRED))
        assertEquals(ChannelPasswordPrompt(1u, wrong = false), asked.channelPassword)
        val wrong = asked.fold(BridgeEvent.ChannelPasswordChanged(1u, ChannelPasswordState.WRONG))
        assertEquals(ChannelPasswordPrompt(1u, wrong = true), wrong.channelPassword)
        assertNull(wrong.fold(BridgeEvent.ChannelPasswordChanged(1u, ChannelPasswordState.ENDED)).channelPassword)
    }

    @Test
    fun aRefusalIsKeptAndTheEndOfTheConnectionForgetsQuestionsRefusalsAndHints() {
        val state = fold(
            *connected,
            BridgeEvent.ChannelPasswordChanged(1u, ChannelPasswordState.REQUIRED),
            BridgeEvent.ActionDenied(1u, "Permission denied."),
        ).withHint(UnsupportedHint(1, "Jonas", UnsupportedKind.FileTransfer))
        assertEquals(DeniedAction(1u, "Permission denied."), state.denied)

        val ended = state.fold(BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null)))
        assertNull(ended.channelPassword)
        assertNull(ended.denied)
        assertEquals(emptyList<UnsupportedHint>(), ended.hints)
    }

    @Test
    fun hintsKeepOnlyTheNewestFew() {
        val state = (1L..5L).fold(SessionState()) { state, id ->
            state.withHint(UnsupportedHint(id, "Jonas", UnsupportedKind.FileTransfer))
        }
        assertEquals(listOf(3L, 4L, 5L), state.hints.map { it.id })
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
                    channel(1u, "Lounge", parentId = 0u),
                    channel(2u, "Games", parentId = 0u),
                ),
            ),
        )
        assertEquals(listOf("Root", "Me", "Games", "Lounge"), names(state))
    }

    @Test
    fun channelRemovedDropsTheChannelAndItsSubChannels() {
        val state = fold(
            *connected,
            BridgeEvent.ChannelsChanged(listOf(channel(2u, "Sub", parentId = 1u))),
            BridgeEvent.ChannelRemoved(1u),
        )
        assertEquals(listOf("Root", "Me"), names(state))
    }

    @Test
    fun personChangedAddsAPersonAndMovesThemBetweenChannels() {
        val joined = fold(*connected, BridgeEvent.PersonChanged(person(11u, "Alice", channelId = 0u)))
        assertEquals(listOf("Root", "Alice", "Me", "Lobby"), names(joined))

        val moved = joined.fold(BridgeEvent.PersonChanged(person(11u, "Alice", channelId = 1u)))
        assertEquals(listOf("Root", "Me", "Lobby", "Alice"), names(moved))
    }

    @Test
    fun personRemovedDropsThePerson() {
        val state = fold(
            *connected,
            BridgeEvent.PersonChanged(person(11u, "Alice", channelId = 1u)),
            BridgeEvent.PersonRemoved(11u),
        )
        assertEquals(listOf("Root", "Me", "Lobby"), names(state))
    }

    @Test
    fun changesBeforeTheServerIsSyncedAreIgnored() {
        val state = fold(
            BridgeEvent.PersonChanged(person(11u, "Alice", channelId = 1u)),
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
    fun theServerListSurvivesTheEndOfAConnection() {
        val servers = listOf(savedServer(1, "Nordlicht"), savedServer(2, "Tidepool"))
        val state = fold(
            BridgeEvent.ServersChanged(servers),
            *connected,
            BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null)),
        )
        assertEquals(SessionState(servers = servers, serversLoaded = true), state)
    }

    @Test
    fun theFailedServerIsRememberedForTheRetryAndForgottenAfterAPlainDisconnect() {
        val connecting = SessionState(phase = ConnectionPhase.Connecting, activeServerId = 2)

        val failed = connecting.fold(
            BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(BridgeException.NameInUse("taken"))),
        )
        assertEquals(2L, failed.activeServerId)
        assertEquals(ConnectionPhase.Failed(ConnectionError.NameInUse), failed.phase)

        val ended = connecting.fold(BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null)))
        assertNull(ended.activeServerId)
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
            BridgeException.Banned("d") to ConnectionError.Banned,
            BridgeException.NotFound("d") to ConnectionError.NotFound,
            BridgeException.PermissionDenied("d") to ConnectionError.PermissionDenied,
            BridgeException.InvalidInput("d") to ConnectionError.InvalidInput,
            BridgeException.SecureStorageUnavailable("d") to ConnectionError.SecureStorageUnavailable,
            BridgeException.Io("d") to ConnectionError.Io,
            BridgeException.Internal("d") to ConnectionError.Internal,
        )
        assertEquals(ConnectionError.entries.toSet(), mapping.values.toSet())
        mapping.forEach { (exception, error) -> assertEquals(error, exception.toConnectionError()) }
    }
}
