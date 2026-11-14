// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.ui.connected.ConnectedUiState
import app.mumcord.mobile.ui.link.LinkConfirmState
import app.mumcord.mobile.ui.servers.ConnectFailure
import app.mumcord.mobile.ui.servers.LocalNetworkHint
import app.mumcord.mobile.ui.servers.PingState
import app.mumcord.mobile.ui.servers.ServerFormState
import app.mumcord.mobile.ui.servers.ServerRow
import app.mumcord.mobile.ui.servers.serverRows

/** The full-screen destinations. Which one shows follows from the state, see [AppUiState.screen]. */
enum class AppScreen { TalkModeChoice, ServerList, ServerForm, Channels }

/** Where to connect: a saved server, a password for this attempt only, a channel from a link. */
data class ConnectTarget(
    val serverId: Long,
    val password: String? = null,
    val channelPath: List<String> = emptyList(),
)

/** A question in a bottom sheet over the current screen. At most one at a time. */
sealed interface Overlay {
    /** One server at a time: connecting to [target] ends the connection to [currentName]. */
    data class ConfirmSwitch(val target: ConnectTarget, val currentName: String, val targetName: String) : Overlay

    data class ConfirmRemove(val server: SavedServer) : Overlay

    /** After a wrong or missing server password: type it and try again. */
    data class EnterPassword(val target: ConnectTarget, val serverName: String, val password: String = "") : Overlay

    data class Link(val state: LinkConfirmState) : Overlay

    data object InvalidLink : Overlay

    /** The system closed the app in the middle of a session with [serverName]; offers to reconnect. */
    data class SessionEnded(val serverId: Long, val serverName: String) : Overlay

    /** Before the system asks for local network access: why [serverName] needs it. */
    data class LocalNetwork(val target: ConnectTarget, val serverName: String) : Overlay
}

data class AppUiState(
    val setupDone: Boolean,
    /** The card picked on the talk mode choice; nothing is picked for the user. */
    val talkModeChoice: TalkMode? = null,
    val setupSaving: Boolean = false,
    val setupError: ConnectionError? = null,
    val session: SessionState = SessionState(),
    val pings: Map<Long, PingState> = emptyMap(),
    /** The server list shows Edit and Remove on every row instead of Connect. */
    val editing: Boolean = false,
    /** The open add or edit form, or null. */
    val form: ServerFormState? = null,
    val overlay: Overlay? = null,
    /** The server whose address is being looked at before its connection starts. */
    val checkingServerId: Long? = null,
    /** The server that was not connected to because local network access is refused. */
    val localNetworkDeniedServerId: Long? = null,
    /** The server with a private address that is tried without local network access. */
    val localNetworkSuspectServerId: Long? = null,
    /** The connected screens, or null while no server is shown. */
    val connected: ConnectedUiState? = null,
) {
    val screen: AppScreen
        get() = when {
            !setupDone -> AppScreen.TalkModeChoice
            connected != null -> AppScreen.Channels
            form != null -> AppScreen.ServerForm
            else -> AppScreen.ServerList
        }

    val serverRows: List<ServerRow>
        get() = serverRows(session, pings, checkingServerId)

    /** The failed connection shown above the server list, or null. */
    val failure: ConnectFailure?
        get() {
            val deniedName = session.servers.firstOrNull { it.id == localNetworkDeniedServerId }?.name
            val failed = session.phase as? ConnectionPhase.Failed
            return when {
                deniedName != null ->
                    ConnectFailure(ConnectionError.ServerUnreachable, deniedName, LocalNetworkHint.Denied)
                failed != null -> ConnectFailure(
                    error = failed.error,
                    serverName = activeServerName,
                    localNetwork = LocalNetworkHint.Suspected.takeIf {
                        failed.error == ConnectionError.ServerUnreachable &&
                            session.activeServerId == localNetworkSuspectServerId
                    },
                )
                else -> null
            }
        }

    /** The name of the server of the current connection, attempt or failure. */
    val activeServerName: String?
        get() = session.servers.firstOrNull { it.id == session.activeServerId }?.name
}
