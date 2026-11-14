// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.BridgeSnapshot
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.ConnectRequest
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.MumcordEngineInterface
import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.bridge.TalkMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for the engine and behaves like it along the connect flow: connecting asks the
 * certificate question (when [prompt] is set), trusting it delivers [server], and every end of
 * a connection is reported as an event.
 */
class FakeEngine(
    private val listener: EngineListener,
    private val server: ServerState,
    private val prompt: CertificatePrompt? = null,
) : MumcordEngineInterface {
    val connectRequests = CopyOnWriteArrayList<ConnectRequest>()
    val certificateAnswers = CopyOnWriteArrayList<Pair<String, Boolean>>()

    /** Thrown by the next [connect] instead of connecting. */
    @Volatile
    var connectFailure: BridgeException? = null

    @Volatile
    private var connection: ConnectionState = ConnectionState.Disconnected(null)

    fun emit(event: BridgeEvent) {
        if (event is BridgeEvent.ConnectionChanged) connection = event.state
        listener.onEvent(event)
    }

    override suspend fun connect(request: ConnectRequest) {
        connectFailure?.let {
            connectFailure = null
            throw it
        }
        connectRequests += request
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Connecting))
        if (prompt != null) {
            emit(BridgeEvent.CertificatePromptRequired(prompt))
        } else {
            finishConnecting()
        }
    }

    override suspend fun answerCertificatePrompt(fingerprint: String, trust: Boolean) {
        certificateAnswers += fingerprint to trust
        if (trust) {
            finishConnecting()
        } else {
            emit(
                BridgeEvent.ConnectionChanged(
                    ConnectionState.Disconnected(BridgeException.CertificateRejected("rejected")),
                ),
            )
        }
    }

    override suspend fun disconnect() {
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null)))
    }

    override fun snapshot(): BridgeSnapshot =
        BridgeSnapshot(
            servers = savedServers.toList(),
            connection = connection,
            connectedServerId = null,
            certificatePrompt = null,
            server = null,
        )

    private val savedServers = CopyOnWriteArrayList<SavedServer>()
    private var nextServerId = 1L

    @Volatile
    private var talkMode = TalkMode.VOICE_ACTIVITY

    override suspend fun addServer(input: ServerInput): SavedServer =
        input.toSaved(nextServerId++).also {
            savedServers += it
            emit(BridgeEvent.ServersChanged(savedServers.toList()))
        }

    override suspend fun updateServer(serverId: Long, input: ServerInput): SavedServer {
        val index = savedServers.indexOfFirst { it.id == serverId }
        if (index < 0) throw BridgeException.NotFound("no server $serverId")
        return input.toSaved(serverId).also {
            savedServers[index] = it
            emit(BridgeEvent.ServersChanged(savedServers.toList()))
        }
    }

    override suspend fun removeServer(serverId: Long) {
        if (!savedServers.removeIf { it.id == serverId }) throw BridgeException.NotFound("no server $serverId")
        emit(BridgeEvent.ServersChanged(savedServers.toList()))
    }

    override suspend fun reorderServers(serverIds: List<Long>) {
        val byId = savedServers.associateBy { it.id }
        savedServers.clear()
        savedServers += serverIds.mapNotNull { byId[it] }
        emit(BridgeEvent.ServersChanged(savedServers.toList()))
    }

    override suspend fun pingServer(serverId: Long, refresh: Boolean): ServerPing? = null

    override suspend fun connectServer(serverId: Long, password: String?, channelPath: List<String>) {
        val saved = savedServers.firstOrNull { it.id == serverId }
            ?: throw BridgeException.NotFound("no server $serverId")
        connect(ConnectRequest(saved.address, saved.port, saved.username, password))
    }

    override suspend fun joinChannel(channelId: UInt, password: String?) = Unit

    override suspend fun setPersonVolume(session: UInt, percent: UShort) = Unit

    override suspend fun setPersonLocalMute(session: UInt, muted: Boolean) = Unit

    override suspend fun talkMode(): TalkMode = talkMode

    override suspend fun setTalkMode(mode: TalkMode) {
        talkMode = mode
    }

    private fun ServerInput.toSaved(id: Long) = SavedServer(
        id = id,
        name = name.ifEmpty { address },
        address = address,
        port = port,
        username = username,
        hasPassword = password is PasswordInput.Set,
        identityId = identityId,
    )

    private fun finishConnecting() {
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Connected))
        emit(BridgeEvent.ServerSynced(server))
    }
}
