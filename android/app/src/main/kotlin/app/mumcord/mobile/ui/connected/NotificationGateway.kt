// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the notification question needs from the system and remembers about the answer. */
interface NotificationGateway {
    fun granted(): Boolean

    /** The question was put once, whatever the answer: it is not put again. */
    var asked: Boolean
}

/** The notification question as the screens see it. */
interface NotificationAccess {
    /** Whether to say why notifications are wanted, before the system asks. */
    val explain: StateFlow<Boolean>

    /** The user put the explanation away without letting the system ask. */
    fun notNow()

    /** The system's question was answered. */
    fun answer(granted: Boolean)

    /** Used where the question is never put, such as screens drawn without a phone behind them. */
    object Never : NotificationAccess {
        override val explain: StateFlow<Boolean> = MutableStateFlow(false)

        override fun notNow() = Unit

        override fun answer(granted: Boolean) = Unit
    }
}

/**
 * Asks once for notifications, when the first voice session shows what they are for. A "no"
 * changes nothing about the session: it goes on in the background, only without its
 * notification.
 *
 * @param onGranted called when the user allowed notifications, so the session's is shown.
 */
class NotificationPrompt(
    private val gateway: NotificationGateway,
    private val onGranted: () -> Unit,
) : NotificationAccess {
    private val mutableExplain = MutableStateFlow(needed())
    override val explain: StateFlow<Boolean> = mutableExplain.asStateFlow()

    override fun notNow() = done()

    override fun answer(granted: Boolean) {
        done()
        if (granted) onGranted()
    }

    /** The app is on screen again: notifications may have been allowed in the system settings. */
    fun recheck() {
        mutableExplain.value = needed()
    }

    private fun done() {
        gateway.asked = true
        mutableExplain.value = false
    }

    private fun needed(): Boolean = !gateway.granted() && !gateway.asked
}

/** The notification permission, which exists since Android 13, and whether it was asked for. */
class AndroidNotificationGateway(context: Context) : NotificationGateway {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun granted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override var asked: Boolean
        get() = preferences.getBoolean(KEY_ASKED, false)
        set(value) = preferences.edit { putBoolean(KEY_ASKED, value) }

    private companion object {
        const val FILE = "notifications"
        const val KEY_ASKED = "asked"
    }
}
