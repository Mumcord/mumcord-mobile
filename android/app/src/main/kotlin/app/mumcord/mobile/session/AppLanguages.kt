// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.text.Collator
import java.util.Locale

/**
 * The languages of the app (FR-5.82): the 24 of the desktop app as ISO 639-1 codes, the codes
 * the shared `language` setting stores. `scripts/translations.py` writes their texts and
 * `res/xml/locales_config.xml` with the same list.
 */
val APP_LANGUAGES: List<String> = listOf(
    "en", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "ga", "hr",
    "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv",
)

/** A language under its own name ("Deutsch", "Français"), as the desktop's language picker shows it. */
fun languageEndonym(code: String): String {
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/** The languages in the order of their own names, as on the desktop. */
fun languagesByEndonym(): List<String> {
    val collator = Collator.getInstance(Locale.ENGLISH)
    return APP_LANGUAGES.sortedWith(compareBy(collator) { languageEndonym(it) })
}

/**
 * The phone's own language setting for this app (Android 13 and later). It says the same as the
 * shared `language` setting: null follows the phone, otherwise one of [APP_LANGUAGES].
 */
interface AppLanguages {
    /** False on Android 12, which has no language per app: there the app follows the phone. */
    val available: Boolean

    /** The app's own language, or null when it follows the phone. */
    fun current(): String?

    /** Switches the app's language; the system then recreates the screens in it. */
    fun set(language: String?)

    companion object {
        /** The app follows the phone's language (Android 12, and the tests). */
        val PhoneOnly: AppLanguages = object : AppLanguages {
            override val available = false

            override fun current(): String? = null

            override fun set(language: String?) = Unit
        }

        fun of(context: Context): AppLanguages =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SystemAppLanguages(context.getSystemService(LocaleManager::class.java))
            } else {
                PhoneOnly
            }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class SystemAppLanguages(private val manager: LocaleManager) : AppLanguages {
    override val available = true

    override fun current(): String? {
        val locales = manager.applicationLocales
        // A language the app has no texts for (set with adb, for example) counts as following the phone.
        return if (locales.isEmpty) null else locales[0].language.takeIf { it in APP_LANGUAGES }
    }

    override fun set(language: String?) {
        manager.applicationLocales = language?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
    }
}
