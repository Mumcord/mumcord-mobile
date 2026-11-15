// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.PublicServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pings waiting for their answer at the same time, as on desktop; the engine paces them further. */
const val PUBLIC_PING_CONCURRENCY = 4

/**
 * Pings the servers whose rows are on screen, each once per load, a few at a time (FR-5.9).
 * Rows that scroll away before their turn are not pinged. Nothing is sent while stopped: the
 * browser stops it when it closes and when the app leaves the screen, so no ping runs in the
 * background (FR-5.43).
 *
 * Not thread-safe: call it, and let [scope] resume it, on one thread (the main thread in the app).
 */
class PublicPingScheduler(
    private val scope: CoroutineScope,
    private val ping: suspend (PublicServer) -> PublicPing,
    private val onResult: (key: String, ping: PublicPing) -> Unit,
    private val concurrency: Int = PUBLIC_PING_CONCURRENCY,
) {
    private var visible: List<PublicServer> = emptyList()

    /** Servers that were asked in this load and either answered or are being asked. */
    private val asked = HashSet<String>()
    private val running = HashMap<String, Job>()
    private var stopped = true

    /** The servers on screen now, top to bottom. */
    fun setVisible(servers: List<PublicServer>) {
        visible = servers
        pump()
    }

    /** Forgets which servers were pinged, so they are asked again (a new load of the list). */
    fun reset() {
        cancelRunning()
        asked.clear()
        pump()
    }

    fun start() {
        stopped = false
        pump()
    }

    /** Nothing new is sent after this; pings on their way are cancelled and asked again after [start]. */
    fun stop() {
        stopped = true
        visible = emptyList()
        cancelRunning()
    }

    private fun cancelRunning() {
        running.values.forEach { it.cancel() }
        asked.removeAll(running.keys)
        running.clear()
    }

    private fun pump() {
        while (!stopped && running.size < concurrency) {
            val next = visible.firstOrNull { it.key() !in asked } ?: return
            val key = next.key()
            asked += key
            // Registered before it can finish, so a ping that answers at once is never counted twice.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val answer = ping(next)
                if (running.remove(key) != null) {
                    onResult(key, answer)
                    pump()
                }
            }
            running[key] = job
            job.start()
        }
    }
}
