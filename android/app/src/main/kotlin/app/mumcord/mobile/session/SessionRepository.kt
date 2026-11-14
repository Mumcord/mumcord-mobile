// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ConnectRequest
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.MumcordEngineInterface
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

/**
 * Owns the engine and the one connection. The only place that talks to the bridge: screens
 * read [state] and call the commands through a view model.
 *
 * @param scope lives as long as the process; its dispatcher folds the events, so it must not
 *   be the main thread.
 * @param engineDispatcher where the engine is created (it reads files and the secure storage).
 * @param log receives short technical messages; never names, addresses or other personal data.
 */
class SessionRepository(
    private val engineFactory: EngineFactory,
    scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val log: (String) -> Unit,
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

    // Follow-up task: before connecting to a server on the local network (a private address),
    // ask for the ACCESS_LOCAL_NETWORK permission with an explanation. Without it Android 17
    // drops the connection silently and the attempt ends as ServerUnreachable after the timeout.
    suspend fun connect(address: String, port: UShort, username: String) {
        // Shown at once: creating the engine on first use takes a moment.
        mutableState.update { SessionState(phase = ConnectionPhase.Connecting) }
        runCommand("connect") {
            it.connect(ConnectRequest(address = address, port = port, username = username, password = null))
        }
    }

    suspend fun answerCertificatePrompt(fingerprint: String, trust: Boolean) {
        mutableState.update { it.copy(certificatePrompt = null) }
        runCommand("answer certificate prompt") {
            it.answerCertificatePrompt(fingerprint, trust)
        }
    }

    suspend fun disconnect() {
        runCommand("disconnect") { it.disconnect() }
    }

    private suspend fun runCommand(name: String, command: suspend (MumcordEngineInterface) -> Unit) {
        try {
            command(engine())
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            fail(name, e.toConnectionError(), e)
        } catch (e: RuntimeException) {
            fail(name, ConnectionError.Internal, e)
        } catch (e: LinkageError) {
            // The native library is missing or does not fit this device.
            fail(name, ConnectionError.Internal, e)
        }
    }

    private fun fail(command: String, error: ConnectionError, cause: Throwable) {
        // Only the kind of failure: the detail text can contain the server address.
        log("$command failed: $error (${cause.javaClass.simpleName})")
        mutableState.update { SessionState(phase = ConnectionPhase.Failed(error)) }
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
