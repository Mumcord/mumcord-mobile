// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.setup

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers that the first-start setup is done. The engine cannot say so itself: its talk mode
 * is voice activity until one is chosen, which looks the same as having chosen voice activity.
 */
interface SetupStore {
    val firstStartDone: Boolean

    fun markFirstStartDone()
}

class PreferencesSetupStore(context: Context) : SetupStore {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override val firstStartDone: Boolean
        get() = preferences.getBoolean(KEY_DONE, false)

    override fun markFirstStartDone() {
        preferences.edit { putBoolean(KEY_DONE, true) }
    }

    private companion object {
        const val FILE = "setup"
        const val KEY_DONE = "first_start_done"
    }
}
