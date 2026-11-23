// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The network the phone uses for everything that does not ask for a particular one.
 *
 * @param id tells one network from the next (Wi-Fi, then mobile data); null while there is none.
 * @param validated the phone has checked that the network reaches the internet. A network
 *   without that check (a home network without internet) still carries traffic.
 * @param metered traffic costs the user money or data volume, as on mobile data. Taken from
 *   what the network says about itself, not from its kind: a Wi-Fi hotspot of another phone is
 *   metered too. Without a network nothing is loaded, so that counts as metered as well.
 */
data class NetworkStatus(val id: Long?, val validated: Boolean, val metered: Boolean) {
    /** A network is there and has been checked to work. */
    val usable: Boolean
        get() = id != null && validated

    companion object {
        val None = NetworkStatus(id = null, validated = false, metered = true)
    }
}

/**
 * The status of a network from what it says it can do: whether it has been validated
 * (`NET_CAPABILITY_VALIDATED`) and whether it is not metered (`NET_CAPABILITY_NOT_METERED`).
 */
fun networkStatus(id: Long, validated: Boolean, notMetered: Boolean): NetworkStatus =
    NetworkStatus(id, validated, metered = !notMetered)

/** Follows the phone's default network. */
interface NetworkMonitor {
    val status: StateFlow<NetworkStatus>
}

/**
 * The default network as the system reports it. The system calls back only when something
 * changes; nothing here runs on a timer.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val mutableStatus = MutableStateFlow(NetworkStatus.None)
    override val status: StateFlow<NetworkStatus> = mutableStatus.asStateFlow()

    init {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                // Always follows onAvailable for a new default network, with what it can do.
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    mutableStatus.value = networkStatus(
                        id = network.networkHandle,
                        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    )
                }

                override fun onLost(network: Network) {
                    // A network that was already replaced by the next one is not the default any more.
                    mutableStatus.update { if (it.id == network.networkHandle) NetworkStatus.None else it }
                }
            },
        )
    }
}
