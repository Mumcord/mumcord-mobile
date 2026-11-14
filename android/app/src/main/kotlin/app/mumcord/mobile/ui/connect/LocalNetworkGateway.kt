// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import java.net.InetAddress

/**
 * The permission of Android 17 (API 37) for reaching the local network. Spelled out because the
 * constant of the platform does not exist on the older versions the app also runs on.
 */
const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Whether the app may reach the local network. */
enum class LocalNetworkPermission {
    /** This Android version has no such permission; the local network is open. */
    NotRequired,
    Granted,
    Missing,
}

/** How the system prompt for the permission ended, as far as the system tells. */
data class LocalNetworkAnswer(
    val granted: Boolean,
    /** Whether the system said, before and after the prompt, that the user has refused before. */
    val refusedBefore: Boolean,
    val refusedAfter: Boolean,
) {
    /**
     * After a second refusal the system stops showing its prompt, which it only signals by no
     * longer reporting the earlier refusal. A prompt closed without an answer looks different:
     * no refusal was known before it either.
     */
    val blocksPrompt: Boolean
        get() = !granted && refusedBefore && !refusedAfter
}

/**
 * What the connect flow needs from the system for the local network permission. Everything
 * platform-specific stays behind it, so the flow can be tested without a device.
 */
interface LocalNetworkGateway {
    fun permission(): LocalNetworkPermission

    /** The networks the device is directly connected to right now (Wi-Fi, Ethernet). */
    fun onLinkPrefixes(): List<IpPrefix>

    /** The addresses of a host name or IP address; empty when the name cannot be resolved. */
    suspend fun resolve(host: String): List<InetAddress>

    /** Whether the system no longer shows its prompt, so only the system settings help. */
    var promptBlocked: Boolean
}

/** What happens before a connection is started. */
enum class LocalNetworkStep {
    Connect,

    /** Explain why access is needed, then let the system ask. */
    Explain,

    /** The system will not ask any more: say so and point to the system settings. */
    Denied,
}

/**
 * The step for a connect to [host], and whether a failure to reach it may be down to the
 * missing permission ([suspect]) although nothing was asked up front.
 */
data class LocalNetworkCheck(val step: LocalNetworkStep, val suspect: Boolean = false)

/**
 * Decides whether the permission has to be asked for before connecting to [host].
 * [suspected] is set when an earlier attempt could not reach this private address without the
 * permission; then it is treated like an address on the local network.
 */
suspend fun LocalNetworkGateway.check(host: String, suspected: Boolean): LocalNetworkCheck {
    if (permission() != LocalNetworkPermission.Missing) {
        // Granted, perhaps in the system settings: the prompt is possible again later.
        if (promptBlocked) promptBlocked = false
        return LocalNetworkCheck(LocalNetworkStep.Connect)
    }
    val locality = if (isLocalName(host)) {
        // Resolving such a name already needs the permission, so it cannot be looked at first.
        Locality.OnLink
    } else {
        // An unresolved name connects as before; the normal error then says what is wrong.
        localityOf(resolve(host), onLinkPrefixes())
    }
    val needsPermission = locality == Locality.OnLink || (locality == Locality.Private && suspected)
    return when {
        !needsPermission -> LocalNetworkCheck(LocalNetworkStep.Connect, suspect = locality == Locality.Private)
        promptBlocked -> LocalNetworkCheck(LocalNetworkStep.Denied)
        else -> LocalNetworkCheck(LocalNetworkStep.Explain)
    }
}
