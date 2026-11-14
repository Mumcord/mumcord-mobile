// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.MumcordEngineInterface
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.parseMumbleLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The result of a command that the screen which started it reports itself (a form, a sheet). */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val error: ConnectionError) : Outcome<Nothing>
}

/**
 * Owns the engine, the server list and the one connection. The only place that talks to the
 * bridge: screens read [state] and call the commands through a view model.
 *
 * @param scope lives as long as the process; its dispatcher folds the events, so it must not
 *   be the main thread.
 * @param engineDispatcher where the engine is created (it reads files and the secure storage).
 * @param log receives short technical messages; never names, addresses, passwords or other
 *   personal data.
 * @param linkParser reads a `mumble://` link; a seam because the real one needs the native
 *   library.
 */
class SessionRepository(
    private val engineFactory: EngineFactory,
    scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val log: (String) -> Unit,
    private val linkParser: (String) -> MumbleLink = ::parseMumbleLink,
) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    // The engine calls back on its own thread, which must never wait: the callback only queues
    // the event, and a single reader keeps them in order.
    private val events = Channel<BridgeEvent>(Channel.UNLIMITED)
    private val engineLock = Mutex()
    private var engine: MumcordEngineInterface? = null

    init {
        scope.launch {
            for (event in events) {
                mutableState.update { it.fold(event) }
            }
        }
    }

    /** Reads the saved servers once; later changes arrive as events. A failure can be retried. */
    suspend fun loadServers() {
        if (mutableState.value.serversLoaded) return
        when (val servers = call("load servers") { it.snapshot().servers }) {
            // An event that arrived in between is newer than the snapshot.
            is Outcome.Ok -> mutableState.update {
                if (it.serversLoaded) it else it.copy(servers = servers.value, serversLoaded = true)
            }
            is Outcome.Failed -> mutableState.update { it.copy(phase = ConnectionPhase.Failed(servers.error)) }
        }
    }

    suspend fun addServer(input: ServerInput): Outcome<SavedServer> =
        call("add server") { it.addServer(input) }

    suspend fun updateServer(serverId: Long, input: ServerInput): Outcome<SavedServer> =
        call("update server") { it.updateServer(serverId, input) }

    suspend fun removeServer(serverId: Long): Outcome<Unit> =
        call("remove server") { it.removeServer(serverId) }

    /** People online and latency of a saved server, or null when it does not answer. */
    suspend fun pingServer(serverId: Long, refresh: Boolean): ServerPing? =
        when (val ping = call("ping server") { it.pingServer(serverId, refresh) }) {
            is Outcome.Ok -> ping.value
            is Outcome.Failed -> null
        }

    suspend fun setTalkMode(mode: TalkMode): Outcome<Unit> =
        call("set talk mode") { it.setTalkMode(mode) }

    /** The target of a `mumble://` link. Nothing is saved or connected by reading it. */
    fun parseLink(url: String): Outcome<MumbleLink> =
        try {
            Outcome.Ok(linkParser(url))
        } catch (e: BridgeException) {
            failed("parse link", e.toConnectionError(), e)
        } catch (e: LinkageError) {
            failed("parse link", ConnectionError.Internal, e)
        }

    // Follow-up task 2.7: before connecting to a server on the local network (a private
    // address), ask for the ACCESS_LOCAL_NETWORK permission with an explanation. Without it
    // Android 17 drops the connection silently and the attempt ends as ServerUnreachable.
    /**
     * Connects to a saved server. [password] replaces the saved one for this attempt only;
     * a non-empty [channelPath] (from a link) is joined after connecting. The caller makes sure
     * that no other connection is alive (one server at a time).
     */
    suspend fun connectServer(serverId: Long, password: String?, channelPath: List<String>) {
        // Shown at once: creating the engine on first use takes a moment.
        mutableState.update {
            it.copy(
                phase = ConnectionPhase.Connecting,
                certificatePrompt = null,
                server = null,
                activeServerId = serverId,
            )
        }
        val result = call("connect") { it.connectServer(serverId, password, channelPath) }
        if (result is Outcome.Failed) {
            mutableState.update {
                it.copy(phase = ConnectionPhase.Failed(result.error), certificatePrompt = null, server = null)
            }
        }
    }

    suspend fun answerCertificatePrompt(fingerprint: String, trust: Boolean) {
        mutableState.update { it.copy(certificatePrompt = null) }
        val result = call("answer certificate prompt") { it.answerCertificatePrompt(fingerprint, trust) }
        if (result is Outcome.Failed) {
            mutableState.update { it.copy(phase = ConnectionPhase.Failed(result.error), server = null) }
        }
    }

    suspend fun disconnect() {
        val result = call("disconnect") { it.disconnect() }
        if (result is Outcome.Failed) {
            mutableState.update {
                it.copy(phase = ConnectionPhase.Failed(result.error), certificatePrompt = null, server = null)
            }
        }
    }

    /** Puts a shown error away. */
    fun dismissFailure() {
        mutableState.update {
            if (it.phase is ConnectionPhase.Failed) it.copy(phase = ConnectionPhase.Idle, activeServerId = null) else it
        }
    }

    private suspend fun <T> call(name: String, command: suspend (MumcordEngineInterface) -> T): Outcome<T> =
        try {
            Outcome.Ok(command(engine()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            failed(name, e.toConnectionError(), e)
        } catch (e: RuntimeException) {
            failed(name, ConnectionError.Internal, e)
        } catch (e: LinkageError) {
            // The native library is missing or does not fit this device.
            failed(name, ConnectionError.Internal, e)
        }

    private fun failed(command: String, error: ConnectionError, cause: Throwable): Outcome.Failed {
        // Only the kind of failure: the detail text can contain the server address.
        log("$command failed: $error (${cause.javaClass.simpleName})")
        return Outcome.Failed(error)
    }

    /** Created on first use and kept for the life of the process; a failed start is retried. */
    private suspend fun engine(): MumcordEngineInterface = engineLock.withLock {
        engine ?: withContext(engineDispatcher) {
            engineFactory.create(
                object : EngineListener {
                    override fun onEvent(event: BridgeEvent) {
                        events.trySend(event)
                    }
                },
            )
        }.also { engine = it }
    }
}
