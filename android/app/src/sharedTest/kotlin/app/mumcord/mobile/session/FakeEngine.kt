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
import app.mumcord.mobile.bridge.ServerState
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
        BridgeSnapshot(connection = connection, certificatePrompt = null, server = null)

    private fun finishConnecting() {
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Connected))
        emit(BridgeEvent.ServerSynced(server))
    }
}
