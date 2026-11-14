// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.session.SessionState

/** The standard Mumble port; an address is shown without it. */
const val DEFAULT_PORT: UShort = 64738u

/** What a server answered to the last ping. A server that was not asked yet has no state. */
sealed interface PingState {
    data class Online(val peopleOnline: Int, val latencyMs: Int) : PingState
    data object Offline : PingState
}

fun ServerPing?.toPingState(): PingState =
    if (this == null) PingState.Offline else PingState.Online(peopleOnline.toInt(), latencyMs.toInt())

/** One row of the server list, ready to draw. */
data class ServerRow(
    val id: Long,
    val name: String,
    val initials: String,
    val address: String,
    val ping: PingState?,
    /** True from the tap on "Connect" until the connection stands or has ended. */
    val connecting: Boolean,
    /** The first server of the list gets the brand tile and the filled button, as in the mockup. */
    val highlighted: Boolean,
)

/** [checkingServerId] is the server whose connection is being prepared; it already shows as connecting. */
fun serverRows(session: SessionState, pings: Map<Long, PingState>, checkingServerId: Long? = null): List<ServerRow> =
    session.servers.mapIndexed { index, server ->
        ServerRow(
            id = server.id,
            name = server.name,
            initials = initials(server.name),
            address = server.displayAddress(),
            ping = pings[server.id],
            connecting = server.id == checkingServerId || (session.isBusy && session.activeServerId == server.id),
            highlighted = index == 0,
        )
    }

/** Up to two initials for a server tile: the first letters of the first two words (as on desktop). */
fun initials(name: String): String =
    name.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { word -> String(Character.toChars(word.codePointAt(0))) }
        .uppercase()
        .ifEmpty { "?" }

/** Host name or IP address, with the port only when it is not the standard one. */
fun SavedServer.displayAddress(): String = displayAddress(address, port)

fun displayAddress(address: String, port: UShort): String =
    if (port == DEFAULT_PORT) address else "$address:$port"
