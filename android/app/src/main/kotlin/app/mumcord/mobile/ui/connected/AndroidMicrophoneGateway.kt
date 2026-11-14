// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit

/** The microphone permission and what the user answered so far. */
class AndroidMicrophoneGateway(context: Context) : MicrophoneGateway {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override var declined: Boolean
        get() = preferences.getBoolean(KEY_DECLINED, false)
        set(value) = preferences.edit { putBoolean(KEY_DECLINED, value) }

    override var promptBlocked: Boolean
        get() = preferences.getBoolean(KEY_PROMPT_BLOCKED, false)
        set(value) = preferences.edit { putBoolean(KEY_PROMPT_BLOCKED, value) }

    private companion object {
        const val FILE = "microphone"
        const val KEY_DECLINED = "declined"
        const val KEY_PROMPT_BLOCKED = "prompt_blocked"
    }
}
