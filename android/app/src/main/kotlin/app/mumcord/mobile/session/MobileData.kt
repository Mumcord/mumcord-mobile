// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.MobileSettings
import app.mumcord.mobile.platform.network.NetworkMonitor
import app.mumcord.mobile.platform.network.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The voice bitrate of "Reduced voice quality" on mobile data, in kbit/s (FR-5.26). */
const val REDUCED_VOICE_KBPS: UInt = 24u

/** The engine's reconnect attempt from which the app gets back in by itself; see [reconnectsNow]. */
const val RECONNECT_NOW_FROM_ATTEMPT = 3

/**
 * "Load images and GIFs" (FR-5.41): on a network that is not metered they are always loaded,
 * on a metered one as the switch says. On by default, also while the settings are unread.
 */
fun loadMedia(network: NetworkStatus, settings: MobileSettings?): Boolean =
    !network.metered || settings?.mobileDataLoadMedia != false

/**
 * "Start streams automatically" (FR-5.41): on a network that is not metered streams start by
 * themselves, on a metered one only with the switch on; off by default. The screens for
 * streams ask this before they start one.
 */
fun autoplayStreams(network: NetworkStatus, settings: MobileSettings?): Boolean =
    !network.metered || settings?.mobileDataAutoplayStreams == true

/**
 * "Reduced voice quality" (FR-5.26, FR-5.41): the limit for the voice bitrate in kbit/s while
 * the network is metered and the switch is on, null (no limit) otherwise.
 */
fun bitrateLimitKbps(network: NetworkStatus, settings: MobileSettings?): UInt? =
    if (network.metered && settings?.mobileDataReducedVoice == true) REDUCED_VOICE_KBPS else null

/**
 * Whether the app gets back into the server itself, now (FR-5.40).
 *
 * The engine notices a lost connection and reconnects by itself, at first within seconds.
 * After the third attempt it waits up to half a minute between tries, also when a network has
 * come back meanwhile. So when a working network appears ([previous] had none, or another one)
 * while the engine is that far, the app does not wait for it. Earlier attempts are left to the
 * engine, and nothing is started while one such reconnect [running] already.
 */
fun reconnectsNow(previous: NetworkStatus, current: NetworkStatus, phase: ConnectionPhase, running: Boolean): Boolean =
    !running &&
        current.usable &&
        (!previous.usable || previous.id != current.id) &&
        phase is ConnectionPhase.Reconnecting &&
        phase.attempt >= RECONNECT_NOW_FROM_ATTEMPT

/**
 * What the phone's network means for the session: the voice bitrate limit on mobile data and
 * getting back in when a network returns. It follows the network and the session by itself
 * once it has been created; the system reports network changes, nothing here runs on a timer.
 *
 * @param scope lives as long as the process.
 */
class NetworkSession(
    private val repository: SessionRepository,
    monitor: NetworkMonitor,
    scope: CoroutineScope,
) {
    @Volatile
    private var reconnecting = false

    init {
        scope.launch {
            // The engine forgets the limit when it starts, so it is set again with every
            // connection, and whenever the network or the switch changes.
            combine(
                monitor.status,
                repository.state.map { it.mobileSettings }.distinctUntilChanged(),
                repository.state.map { it.serversLoaded to it.phase::class }.distinctUntilChanged(),
            ) { network, settings, _ -> bitrateLimitKbps(network, settings) }
                .collect(repository::setBitrateLimit)
        }
        scope.launch {
            var previous = monitor.status.value
            monitor.status.collect { current ->
                if (reconnectsNow(previous, current, repository.state.value.phase, reconnecting)) {
                    reconnecting = true
                    launch {
                        try {
                            repository.reconnectNow()
                        } finally {
                            reconnecting = false
                        }
                    }
                }
                previous = current
            }
        }
    }

}
