// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.SavedServer

/** Everything the screens need to know about the one connection, as an immutable value. */
data class SessionState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val certificatePrompt: CertificatePromptState? = null,
    val server: ServerModel? = null,
    /** The server list in its saved order; empty until [serversLoaded]. */
    val servers: List<SavedServer> = emptyList(),
    val serversLoaded: Boolean = false,
    /**
     * The saved server of the current connection or attempt. It stays set after a failure, so
     * the error can name the server and "Try again" knows where to connect.
     */
    val activeServerId: Long? = null,
) {
    /** True once the server's channels are known and the connection is still alive. */
    val showsServer: Boolean
        get() = server != null &&
            (phase == ConnectionPhase.Connected || phase is ConnectionPhase.Reconnecting)

    /** True while a connection exists or is being made, so no new one may be started. */
    val isBusy: Boolean
        get() = phase != ConnectionPhase.Idle && phase !is ConnectionPhase.Failed
}

sealed interface ConnectionPhase {
    data object Idle : ConnectionPhase

    data object Connecting : ConnectionPhase

    data object Connected : ConnectionPhase

    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionPhase

    data class Failed(val error: ConnectionError) : ConnectionPhase
}

/**
 * The certificate question waiting for an answer. [previousFingerprint] is set when a server
 * that was trusted before now presents a different certificate.
 */
data class CertificatePromptState(
    val fingerprint: String,
    val previousFingerprint: String?,
) {
    val isChanged: Boolean
        get() = previousFingerprint != null
}

/**
 * The server's channels and people as the engine reported them, plus the rows derived from
 * them. The rows are built when the value is created, which happens off the main thread.
 */
data class ServerModel(
    val selfSession: UInt,
    val channels: Map<UInt, Channel>,
    val people: Map<UInt, Person>,
) {
    val root: ChannelNode? = buildChannelTree(selfSession, channels.values, people.values)
    val rows: List<TreeRow> = root?.flatten().orEmpty()
}
