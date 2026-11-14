// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChannelPasswordState
import app.mumcord.mobile.bridge.Person
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One call of [FakeEngine.connectServer]. */
data class ConnectCall(val serverId: Long, val password: String?, val channelPath: List<String>)

/**
 * Stands in for the engine and behaves like it: it keeps a server list, answers pings from
 * [pings], and along the connect flow asks the certificate question (when [prompt] is set),
 * delivers [server] once trusted, and reports every end of a connection as an event.
 */
class FakeEngine(
    private val listener: EngineListener,
    private val server: ServerState,
    private val prompt: CertificatePrompt? = null,
    servers: List<SavedServer> = emptyList(),
) : MumcordEngineInterface {
    val connectCalls = CopyOnWriteArrayList<ConnectCall>()
    val certificateAnswers = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val serverInputs = CopyOnWriteArrayList<ServerInput>()
    val pingRequests = CopyOnWriteArrayList<Long>()
    var disconnects = 0
        private set

    /** What each server answers to a ping; a server without an entry does not answer. */
    val pings = ConcurrentHashMap<Long, ServerPing>()

    /** Thrown by the next [connectServer] instead of connecting. */
    @Volatile
    var connectFailure: BridgeException? = null

    /** Thrown by the next [addServer] or [updateServer]. */
    @Volatile
    var saveFailure: BridgeException? = null

    /** Thrown by the next [setTalkMode]. */
    @Volatile
    var talkModeFailure: BridgeException? = null

    /** When set, the server refuses every connection without exactly this password. */
    @Volatile
    var requiredPassword: String? = null

    /** Channels that only let in who joins with exactly this password. */
    val channelPasswords = ConcurrentHashMap<UInt, String>()

    /** Channels the server refuses to let the user into. */
    val deniedChannels: MutableSet<UInt> = ConcurrentHashMap.newKeySet()
    val joinCalls = CopyOnWriteArrayList<Pair<UInt, String?>>()
    val volumeCalls = CopyOnWriteArrayList<Pair<UInt, Int>>()
    private val people = ConcurrentHashMap(server.people.associateBy { it.session })

    @Volatile
    var talkMode = TalkMode.VOICE_ACTIVITY
        private set

    @Volatile
    private var connection: ConnectionState = ConnectionState.Disconnected(null)

    @Volatile
    private var connectedServerId: Long? = null

    private val savedServers = CopyOnWriteArrayList(servers)
    private var nextServerId = (servers.maxOfOrNull { it.id } ?: 0L) + 1

    fun emit(event: BridgeEvent) {
        if (event is BridgeEvent.ConnectionChanged) {
            connection = event.state
            if (event.state is ConnectionState.Disconnected) connectedServerId = null
        }
        listener.onEvent(event)
    }

    override suspend fun connectServer(serverId: Long, password: String?, channelPath: List<String>) {
        connectFailure?.let {
            connectFailure = null
            throw it
        }
        if (savedServers.none { it.id == serverId }) throw BridgeException.NotFound("no server $serverId")
        if (connection !is ConnectionState.Disconnected) throw BridgeException.InvalidInput("already connected")
        connectCalls += ConnectCall(serverId, password, channelPath)
        connectedServerId = serverId
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Connecting))
        when {
            requiredPassword != null && password != requiredPassword -> emit(
                BridgeEvent.ConnectionChanged(
                    ConnectionState.Disconnected(BridgeException.WrongPassword("wrong password")),
                ),
            )
            prompt != null -> emit(BridgeEvent.CertificatePromptRequired(prompt))
            else -> finishConnecting()
        }
    }

    // The call of the first slice, which the app no longer uses.
    override suspend fun connect(request: ConnectRequest): Unit =
        throw BridgeException.Internal("connect is not used by the app")

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
        disconnects++
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null)))
    }

    override fun snapshot(): BridgeSnapshot =
        BridgeSnapshot(
            servers = savedServers.toList(),
            connection = connection,
            connectedServerId = connectedServerId,
            certificatePrompt = null,
            server = null,
        )

    override suspend fun addServer(input: ServerInput): SavedServer {
        failSaveOnce()
        serverInputs += input
        return input.toSaved(nextServerId++, hadPassword = false).also {
            savedServers += it
            emit(BridgeEvent.ServersChanged(savedServers.toList()))
        }
    }

    override suspend fun updateServer(serverId: Long, input: ServerInput): SavedServer {
        failSaveOnce()
        val index = savedServers.indexOfFirst { it.id == serverId }
        if (index < 0) throw BridgeException.NotFound("no server $serverId")
        serverInputs += input
        return input.toSaved(serverId, hadPassword = savedServers[index].hasPassword).also {
            savedServers[index] = it
            emit(BridgeEvent.ServersChanged(savedServers.toList()))
        }
    }

    private fun failSaveOnce() {
        saveFailure?.let {
            saveFailure = null
            throw it
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

    override suspend fun pingServer(serverId: Long, refresh: Boolean): ServerPing? {
        pingRequests += serverId
        return pings[serverId]
    }

    override suspend fun joinChannel(channelId: UInt, password: String?) {
        joinCalls += channelId to password
        val needed = channelPasswords[channelId]
        when {
            channelId in deniedChannels -> emit(BridgeEvent.ActionDenied(channelId, "Permission denied."))
            needed != null && password == null ->
                emit(BridgeEvent.ChannelPasswordChanged(channelId, ChannelPasswordState.REQUIRED))
            needed != null && password != needed ->
                emit(BridgeEvent.ChannelPasswordChanged(channelId, ChannelPasswordState.WRONG))
            else -> {
                if (password != null) emit(BridgeEvent.ChannelPasswordChanged(channelId, ChannelPasswordState.ENDED))
                change(server.selfSession) { it.copy(channelId = channelId) }
            }
        }
    }

    override suspend fun setPersonVolume(session: UInt, percent: UShort) {
        volumeCalls += session to percent.toInt()
        change(session) { it.copy(localVolume = percent) }
    }

    override suspend fun setPersonLocalMute(session: UInt, muted: Boolean) {
        change(session) { it.copy(locallyMuted = muted) }
    }

    /** Changes a person and reports it, as the engine does. */
    fun change(session: UInt, change: (Person) -> Person) {
        val person = people[session] ?: return
        change(person).also {
            people[session] = it
            emit(BridgeEvent.PersonChanged(it))
        }
    }

    override suspend fun talkMode(): TalkMode = talkMode

    override suspend fun setTalkMode(mode: TalkMode) {
        talkModeFailure?.let {
            talkModeFailure = null
            throw it
        }
        talkMode = mode
    }

    private fun ServerInput.toSaved(id: Long, hadPassword: Boolean) = SavedServer(
        id = id,
        name = name.ifEmpty { address },
        address = address,
        port = port,
        username = username,
        hasPassword = when (password) {
            is PasswordInput.Set -> true
            PasswordInput.Clear -> false
            PasswordInput.Keep -> hadPassword
        },
        identityId = identityId,
    )

    private fun finishConnecting() {
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Connected))
        emit(BridgeEvent.ServerSynced(server))
    }
}
