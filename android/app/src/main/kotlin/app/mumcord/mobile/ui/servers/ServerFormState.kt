// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.session.ConnectionError

/**
 * The add and edit server form. Kept as text so a half-typed port is not lost. A saved password
 * is never read back: the form only knows that there is one ([hasSavedPassword]).
 */
data class ServerFormState(
    /** The server being edited, or null for a new one. */
    val serverId: Long? = null,
    val name: String = "",
    val address: String = "",
    val port: String = DEFAULT_PORT.toString(),
    val username: String = "",
    val password: String = "",
    val hasSavedPassword: Boolean = false,
    val removeSavedPassword: Boolean = false,
    /** Kept as it is: choosing the identity arrives with the identity screens. */
    val identityId: Long? = null,
    /** Errors are shown once the user has tried to save. */
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val saveError: ConnectionError? = null,
) {
    val isEdit: Boolean
        get() = serverId != null

    /** The port as a number, or null while the text is not a port between 1 and 65535. */
    val portNumber: UShort?
        get() = port.trim().toUShortOrNull()?.takeIf { it > 0u }

    val addressMissing: Boolean
        get() = address.isBlank()

    val usernameMissing: Boolean
        get() = username.isBlank()

    val isValid: Boolean
        get() = !addressMissing && !usernameMissing && portNumber != null

    /** What to save, or null while the form is not valid. */
    fun toInput(): ServerInput? {
        val port = portNumber
        if (!isValid || port == null) return null
        return ServerInput(
            name = name.trim(),
            address = address.trim(),
            port = port,
            username = username.trim(),
            password = when {
                password.isNotEmpty() -> PasswordInput.Set(password)
                removeSavedPassword -> PasswordInput.Clear
                else -> PasswordInput.Keep
            },
            identityId = identityId,
        )
    }

    companion object {
        fun edit(server: SavedServer) = ServerFormState(
            serverId = server.id,
            name = server.name,
            address = server.address,
            port = server.port.toString(),
            username = server.username,
            hasSavedPassword = server.hasPassword,
            identityId = server.identityId,
        )
    }
}
