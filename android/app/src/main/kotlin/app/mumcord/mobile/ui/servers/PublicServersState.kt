// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.PublicServer
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerPing

/** What a listed server answered to its ping. A server that was not asked yet has none. */
sealed interface PublicPing {
    data class Answered(val people: Int, val maxPeople: Int, val latencyMs: Int) : PublicPing
    data object NoAnswer : PublicPing
}

fun ServerPing?.toPublicPing(): PublicPing =
    if (this == null) PublicPing.NoAnswer else PublicPing.Answered(peopleOnline.toInt(), maxPeople.toInt(), latencyMs.toInt())

/** One server of the list, the same however often it is listed or asked: host (any case) and port. */
fun PublicServer.key(): String = "${host.lowercase()}:$port"

/** A country of the list, for the country filter. */
data class PublicCountry(val code: String, val name: String)

/**
 * The browser's own state. The list stays while the app runs, also when the browser is closed;
 * the engine only downloads it again on "Refresh".
 */
data class PublicServersLocal(
    /** The list as last loaded, or null before the first successful load. */
    val servers: List<PublicServer>? = null,
    val loading: Boolean = false,
    /** The first load failed: there is no list to show. */
    val loadFailed: Boolean = false,
    /** A refresh failed: the last list is still shown. */
    val refreshFailed: Boolean = false,
    val search: String = "",
    /** The country code of the country filter, or null for all countries. */
    val country: String? = null,
    /** By [key]. */
    val pings: Map<String, PublicPing> = emptyMap(),
    /** The servers being added to the server list, by [key]. */
    val adding: Set<String> = emptySet(),
    val addFailed: Boolean = false,
)

/** One row of the browser, ready to draw. Name and country are untrusted text, shown as plain text. */
data class PublicServerRow(
    val key: String,
    val name: String,
    val initials: String,
    val address: String,
    val country: String,
    val ping: PublicPing?,
    /** The saved server with this address, once it is in the server list. */
    val savedId: Long?,
    val adding: Boolean,
)

/** What the browser shows below its search and filters. */
enum class PublicListStatus { Loading, Failed, Empty, NoMatch, Ready }

data class PublicServersUiState(
    val status: PublicListStatus,
    val rows: List<PublicServerRow>,
    /** How many servers the list has in all. */
    val total: Int,
    val search: String,
    val countries: List<PublicCountry>,
    /** The chosen country, or null for all countries. */
    val country: String?,
    /** True while a refresh runs over a list that is already shown. */
    val refreshing: Boolean,
    val refreshFailed: Boolean,
    val addFailed: Boolean,
)

/**
 * Kept when [search] is part of the name, the address or the country, whatever the case, and
 * the server is in the country [countryFilter] (any country when null). The same rule as the
 * bridge's query.
 */
fun PublicServer.matches(search: String, countryFilter: String?): Boolean {
    val text = search.trim()
    val inCountry = countryFilter == null || countryCode.equals(countryFilter, ignoreCase = true)
    return inCountry && (
        text.isEmpty() ||
            name.contains(text, ignoreCase = true) ||
            host.contains(text, ignoreCase = true) ||
            country.contains(text, ignoreCase = true)
        )
}

/** The countries of [servers] with a code, each once, sorted by name. */
fun countriesOf(servers: List<PublicServer>): List<PublicCountry> =
    servers.filter { it.countryCode.isNotEmpty() }
        .distinctBy { it.countryCode.uppercase() }
        .map { PublicCountry(it.countryCode.uppercase(), it.country.ifBlank { it.countryCode.uppercase() }) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/** The saved server with the address and port of [server], as the desktop app matches them. */
fun savedMatch(saved: List<SavedServer>, server: PublicServer): SavedServer? =
    saved.firstOrNull { it.address.equals(server.host, ignoreCase = true) && it.port == server.port }

/** The servers the search and the country filter keep, sorted by name as on desktop. */
fun shownServers(local: PublicServersLocal): List<PublicServer> =
    local.servers.orEmpty()
        .filter { it.matches(local.search, local.country) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

fun publicServersUiState(local: PublicServersLocal, saved: List<SavedServer>): PublicServersUiState {
    val servers = local.servers
    val shown = shownServers(local)
    val status = when {
        servers == null && local.loadFailed && !local.loading -> PublicListStatus.Failed
        servers == null -> PublicListStatus.Loading
        servers.isEmpty() -> PublicListStatus.Empty
        shown.isEmpty() -> PublicListStatus.NoMatch
        else -> PublicListStatus.Ready
    }
    return PublicServersUiState(
        status = status,
        rows = shown.map { server ->
            val key = server.key()
            PublicServerRow(
                key = key,
                name = server.name,
                initials = initials(server.name),
                address = displayAddress(server.host, server.port),
                country = server.country,
                ping = local.pings[key],
                savedId = savedMatch(saved, server)?.id,
                adding = key in local.adding,
            )
        },
        total = servers?.size ?: 0,
        search = local.search,
        countries = countriesOf(servers.orEmpty()),
        country = local.country,
        refreshing = servers != null && local.loading,
        refreshFailed = local.refreshFailed,
        addFailed = local.addFailed,
    )
}
