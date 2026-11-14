// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import java.net.InetAddress

/**
 * A device whose permission state, networks and name lookups are set by the test. By default
 * it is an Android version without the local network permission, where nothing is asked.
 */
class FakeLocalNetworkGateway(
    var permission: LocalNetworkPermission = LocalNetworkPermission.NotRequired,
    /** The networks the device is directly connected to, as "address/length". */
    onLink: List<String> = listOf("192.168.1.0/24"),
) : LocalNetworkGateway {
    private val onLink = onLink.map { text ->
        IpPrefix(InetAddress.getByName(text.substringBefore('/')), text.substringAfter('/').toInt())
    }

    /** The addresses of host names; a name that is not listed does not resolve. */
    val names = mutableMapOf<String, List<String>>()
    val resolved = mutableListOf<String>()

    override var promptBlocked = false

    override fun permission(): LocalNetworkPermission = permission

    override fun onLinkPrefixes(): List<IpPrefix> = onLink

    override suspend fun resolve(host: String): List<InetAddress> {
        resolved += host
        val addresses = names[host] ?: listOf(host).filter { it.first().isDigit() || ':' in it }
        // Only literal addresses are passed on, so nothing is ever looked up on a real network.
        return addresses.map(InetAddress::getByName)
    }
}
