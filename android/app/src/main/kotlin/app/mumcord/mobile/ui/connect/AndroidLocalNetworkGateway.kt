// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.coroutines.resume

/** The local network permission of Android 17 and the device's own networks. */
class AndroidLocalNetworkGateway(context: Context) : LocalNetworkGateway {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun permission(): LocalNetworkPermission = when {
        Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_SDK -> LocalNetworkPermission.NotRequired
        context.checkSelfPermission(ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED -> LocalNetworkPermission.Granted
        else -> LocalNetworkPermission.Missing
    }

    /**
     * The networks of the interfaces that reach neighbours directly. Tunnels (VPN) and the
     * loopback are left out: the system does not count them as local network.
     */
    override fun onLinkPrefixes(): List<IpPrefix> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { it.interfaceAddresses }
            .map { IpPrefix(it.address, it.networkPrefixLength.toInt()) }
            .toList()
    } catch (error: SocketException) {
        // Without the list nothing counts as local; an unreachable private address still gets
        // the permission hint in its error.
        Log.w(LOG_TAG, "Reading the network interfaces failed: ${error.javaClass.simpleName}")
        emptyList()
    }

    /**
     * Looks the name up on a background thread. The lookup cannot be interrupted, so after
     * [RESOLVE_TIMEOUT_MILLIS] the connect goes on without its answer instead of hanging.
     */
    override suspend fun resolve(host: String): List<InetAddress> =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                Dispatchers.IO.asExecutor().execute {
                    val addresses = try {
                        InetAddress.getAllByName(host).toList()
                    } catch (_: UnknownHostException) {
                        // The connect itself reports a name that does not resolve.
                        emptyList()
                    }
                    continuation.resume(addresses)
                }
            }
        }.orEmpty()

    override var promptBlocked: Boolean
        get() = preferences.getBoolean(KEY_PROMPT_BLOCKED, false)
        set(value) = preferences.edit { putBoolean(KEY_PROMPT_BLOCKED, value) }

    private companion object {
        const val LOG_TAG = "Mumcord"
        const val FILE = "local_network"
        const val KEY_PROMPT_BLOCKED = "prompt_blocked"
        const val RESOLVE_TIMEOUT_MILLIS = 5_000L

        /** Android 17, the first version with the local network permission. */
        const val LOCAL_NETWORK_PERMISSION_SDK = 37
    }
}
