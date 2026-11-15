// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.link

import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.session.ConnectionError

/**
 * The target of an opened `mumble://` link, shown for confirmation. Nothing is saved and
 * nothing connects before the user confirms. A password inside a link is never taken over.
 */
data class LinkConfirmState(
    val link: MumbleLink,
    /** The entry of the server list with the same address and port; it is used as it is. */
    val savedServer: SavedServer?,
    /** Typed by the user when neither the link nor a saved entry names one. */
    val typedUsername: String = "",
    val working: Boolean = false,
    val error: ConnectionError? = null,
) {
    /** True when the user has to type the user name. */
    val asksForUsername: Boolean
        get() = savedServer == null && link.username.isNullOrBlank()

    /** The saved entry wins over the link: it is what the user set up for this server. */
    val username: String
        get() = savedServer?.username ?: link.username?.takeIf { it.isNotBlank() } ?: typedUsername.trim()

    val willAddServer: Boolean
        get() = savedServer == null

    val canConnect: Boolean
        get() = username.isNotBlank() && !working

    /** The new entry of the server list, named after its address. */
    fun toInput() = ServerInput(
        name = "",
        address = link.address,
        port = link.port,
        username = username,
        password = PasswordInput.Keep,
        identityId = null,
    )
}

fun linkConfirmState(link: MumbleLink, servers: List<SavedServer>) = LinkConfirmState(
    link = link,
    savedServer = servers.firstOrNull { it.address.equals(link.address, ignoreCase = true) && it.port == link.port },
)
