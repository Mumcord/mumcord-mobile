// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import java.net.Inet4Address
import java.net.InetAddress

/** A network as the device sees it on one of its interfaces, for example 192.168.1.0/24. */
data class IpPrefix(val address: InetAddress, val length: Int) {
    fun contains(other: InetAddress): Boolean {
        val own = address.address
        val bytes = other.address
        if (own.size != bytes.size) return false
        val wholeBytes = length / BITS_PER_BYTE
        for (index in 0 until wholeBytes) {
            if (own[index] != bytes[index]) return false
        }
        val restBits = length % BITS_PER_BYTE
        if (restBits == 0) return true
        val mask = (0xFF shl (BITS_PER_BYTE - restBits)) and 0xFF
        return (own[wholeBytes].toInt() and mask) == (bytes[wholeBytes].toInt() and mask)
    }

    private companion object {
        const val BITS_PER_BYTE = 8
    }
}

/** How a server address relates to the local network rule of Android 17. */
enum class Locality {
    /**
     * On a network the device is directly connected to, or link-local, multicast or broadcast.
     * This is what the system blocks without the local network permission.
     */
    OnLink,

    /**
     * A private address that is not on a directly connected network, for example behind a
     * second router or a VPN. Observed to be reachable without the permission, so nobody is
     * asked up front; it only shapes the error when such a server turns out to be unreachable.
     */
    Private,

    /** Everything else: the internet, which the permission never affects. */
    Other,
}

/**
 * Where a server is, from the addresses its name resolved to and the networks the device is
 * directly connected to ([onLink]). The most local address decides, because the engine may use
 * any of them.
 */
fun localityOf(addresses: List<InetAddress>, onLink: List<IpPrefix>): Locality = when {
    addresses.any { it.isOnLink(onLink) } -> Locality.OnLink
    addresses.any { it.isPrivate() } -> Locality.Private
    else -> Locality.Other
}

/** Names ending in `.local` are looked up on the local network itself (mDNS). */
fun isLocalName(host: String): Boolean = host.trimEnd('.').endsWith(".local", ignoreCase = true)

private fun InetAddress.isOnLink(onLink: List<IpPrefix>): Boolean =
    !isLoopbackAddress &&
        (isLinkLocalAddress || isMulticastAddress || isBroadcast() || onLink.any { it.contains(this) })

private fun InetAddress.isBroadcast(): Boolean = this is Inet4Address && address.all { it == 0xFF.toByte() }

private fun InetAddress.isPrivate(): Boolean = !isLoopbackAddress && PRIVATE_RANGES.any { it.contains(this) }

/** Private IPv4 ranges, the shared range of carrier-grade NAT, and IPv6 unique local addresses. */
private val PRIVATE_RANGES = listOf(
    prefix(byteArrayOf(10, 0, 0, 0), 8),
    prefix(byteArrayOf(172.toByte(), 16, 0, 0), 12),
    prefix(byteArrayOf(192.toByte(), 168.toByte(), 0, 0), 16),
    prefix(byteArrayOf(100, 64, 0, 0), 10),
    prefix(ByteArray(16).also { it[0] = 0xFC.toByte() }, 7),
)

private fun prefix(bytes: ByteArray, length: Int) = IpPrefix(InetAddress.getByAddress(bytes), length)
