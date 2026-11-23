// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

import android.content.Context
import androidx.core.content.edit

/**
 * What the update check remembers between starts: when it last asked, the newer release it found
 * and the version whose notice the user closed. The switch itself is an engine setting
 * (`update_check`), like the other settings.
 */
interface UpdateStore {
    var lastCheckMillis: Long?
    var available: AvailableUpdate?
    var dismissed: ReleaseVersion?
}

class InMemoryUpdateStore(
    override var lastCheckMillis: Long? = null,
    override var available: AvailableUpdate? = null,
    override var dismissed: ReleaseVersion? = null,
) : UpdateStore

class PreferencesUpdateStore(context: Context) : UpdateStore {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var lastCheckMillis: Long?
        get() = if (preferences.contains(LAST_CHECK)) preferences.getLong(LAST_CHECK, 0) else null
        set(value) = preferences.edit { if (value == null) remove(LAST_CHECK) else putLong(LAST_CHECK, value) }

    override var available: AvailableUpdate?
        get() = preferences.getString(AVAILABLE, null)?.let(ReleaseVersion::parse)?.let {
            AvailableUpdate(it, hasApk = preferences.getBoolean(AVAILABLE_APK, false))
        }
        set(value) = preferences.edit {
            if (value == null) {
                remove(AVAILABLE)
                remove(AVAILABLE_APK)
            } else {
                putString(AVAILABLE, value.version.toString())
                putBoolean(AVAILABLE_APK, value.hasApk)
            }
        }

    override var dismissed: ReleaseVersion?
        get() = preferences.getString(DISMISSED, null)?.let(ReleaseVersion::parse)
        set(value) = preferences.edit { if (value == null) remove(DISMISSED) else putString(DISMISSED, value.toString()) }

    private companion object {
        const val FILE = "update_check"
        const val LAST_CHECK = "last_check_millis"
        const val AVAILABLE = "available_version"
        const val AVAILABLE_APK = "available_has_apk"
        const val DISMISSED = "dismissed_version"
    }
}
