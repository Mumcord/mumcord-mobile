// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.platform.notify.IdleNotifier
import java.util.concurrent.CopyOnWriteArrayList

/** Remembers what "disconnect when alone" asked the phone to show. */
class FakeIdleNotifier : IdleNotifier {
    val shown = CopyOnWriteArrayList<String>()

    @Volatile
    var warning = false

    @Volatile
    var disconnected = false

    override fun showWarning() {
        warning = true
        shown += "warning"
    }

    override fun hideWarning() {
        warning = false
    }

    override fun showDisconnected() {
        disconnected = true
        shown += "disconnected"
    }

    override fun hideDisconnected() {
        disconnected = false
    }
}
