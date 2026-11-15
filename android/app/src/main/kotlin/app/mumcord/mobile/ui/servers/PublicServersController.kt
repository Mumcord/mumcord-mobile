// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.PublicServer
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The public server browser (FR-5.9): the list is loaded only when the user opens it, searched
 * and filtered on the phone, and only the rows on screen are pinged. Runs on [scope]'s thread
 * (the main thread in the app).
 */
class PublicServersController(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
) {
    val local = MutableStateFlow(PublicServersLocal())

    private var load: Job? = null

    private val pings = PublicPingScheduler(
        scope = scope,
        ping = { server -> repository.pingPublicServer(server.host, server.port).toPublicPing() },
        onResult = { key, ping -> local.update { it.copy(pings = it.pings + (key to ping)) } },
    )

    /** The browser was opened: the list is loaded the first time, then the one loaded before is shown. */
    fun open() {
        if (local.value.servers == null) load(refresh = false)
        pings.start()
    }

    /** The browser was closed: no more pings, and the next opening starts without search and filter. */
    fun close() {
        pings.stop()
        local.update { it.copy(search = "", country = null, refreshFailed = false, addFailed = false) }
    }

    /** The app left the screen or came back to it while the browser is open. */
    fun pausePings() = pings.stop()

    fun resumePings() = pings.start()

    /** "Refresh", and "Try again" after a failed first load. */
    fun refresh() = load(refresh = local.value.servers != null)

    fun search(text: String) = local.update { it.copy(search = text) }

    fun selectCountry(code: String?) = local.update { it.copy(country = code) }

    /** The rows on screen, by [key], top to bottom. */
    fun showing(keys: List<String>) {
        val byKey = local.value.servers.orEmpty().associateBy { it.key() }
        pings.setVisible(keys.mapNotNull(byKey::get))
    }

    /**
     * Saves the listed server to the server list, with the name of the default identity as the
     * user name, as the desktop app does. Connecting then follows the normal flow.
     */
    fun add(key: String) {
        val server = local.value.servers?.firstOrNull { it.key() == key } ?: return
        if (key in local.value.adding) return
        local.update { it.copy(adding = it.adding + key, addFailed = false) }
        scope.launch {
            val added = repository.addServer(input(server))
            local.update { it.copy(adding = it.adding - key, addFailed = added is Outcome.Failed) }
        }
    }

    private fun input(server: PublicServer): ServerInput {
        val identity = repository.state.value.identities?.firstOrNull { it.isDefault }
        return ServerInput(
            name = server.name,
            address = server.host,
            port = server.port,
            username = identity?.let { it.commonName.ifBlank { it.label } }.orEmpty(),
            password = PasswordInput.Keep,
            identityId = null,
        )
    }

    private fun load(refresh: Boolean) {
        if (load?.isActive == true) return
        local.update { it.copy(loading = true, loadFailed = false, refreshFailed = false) }
        load = scope.launch {
            when (val loaded = repository.publicServers(refresh)) {
                is Outcome.Ok -> {
                    local.update { it.copy(servers = loaded.value, loading = false, pings = emptyMap()) }
                    pings.reset()
                }
                is Outcome.Failed -> local.update { state ->
                    // The last list stays; only a first load without any list is a failure screen.
                    state.copy(
                        loading = false,
                        loadFailed = state.servers == null,
                        refreshFailed = state.servers != null,
                    )
                }
            }
        }
    }
}
