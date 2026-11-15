// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.Profile
import app.mumcord.mobile.bridge.Theme
import app.mumcord.mobile.bridge.ThemeCodeException
import app.mumcord.mobile.bridge.ThemeCodePreview
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.bridge.ThemeShare
import app.mumcord.mobile.bridge.ThemeTokens
import app.mumcord.mobile.bridge.ThemeVariant

// The engine commands of the profile and theme screens (FR-5.80, FR-5.81).

/** The comment and avatar of a person on the connected server, the user's own included. */
suspend fun SessionRepository.personProfile(session: UInt): Outcome<Profile> =
    call("read profile") { it.personProfile(session) }

/** Saves the user's comment on the connected server; blank removes it. */
suspend fun SessionRepository.setOwnComment(text: String): Outcome<Unit> =
    call("save comment") { it.setOwnComment(text) }

/** Sets the user's avatar from image bytes, or removes it with null; the engine scales it. */
suspend fun SessionRepository.setOwnAvatar(image: ByteArray?): Outcome<Unit> =
    call("save avatar") { it.setOwnAvatar(image) }

/** The longest comment the connected server takes, in characters; null when unknown. */
suspend fun SessionRepository.commentLimit(): UInt? =
    (call("chat limits") { it.chatLimits() } as? Outcome.Ok)?.value?.text

/** Both versions of the theme [themeId] (null: the app theme), as the app draws them. */
suspend fun SessionRepository.themeTokens(themeId: String?): Outcome<Pair<ThemeTokens, ThemeTokens>> =
    call("read theme colours") { it.themeTokens(themeId, ThemeVariant.DARK) to it.themeTokens(themeId, ThemeVariant.LIGHT) }

suspend fun SessionRepository.setThemeMode(mode: ThemeMode): Outcome<Unit> =
    call("save appearance") { it.setThemeMode(mode) }

suspend fun SessionRepository.setAppTheme(themeId: String): Outcome<Unit> =
    call("save app theme") { it.setAppTheme(themeId) }

suspend fun SessionRepository.setServerTheme(serverId: Long, themeId: String?): Outcome<Unit> =
    call("save server theme") { it.setServerTheme(serverId, themeId) }

suspend fun SessionRepository.deleteCustomTheme(themeId: String): Outcome<Unit> =
    call("delete theme") { it.deleteCustomTheme(themeId) }

suspend fun SessionRepository.themeShare(themeId: String): Outcome<ThemeShare> =
    call("share theme") { it.themeShare(themeId) }

/** Imports the theme code in [text] (untrusted, checked by the engine) and returns the theme. */
suspend fun SessionRepository.importThemeCode(text: String): Outcome<Theme> =
    call("import theme") { it.importThemeCode(text) }

/** What a pasted text holds: a theme to preview, or why it is none. */
sealed interface ThemeCodeRead {
    data class Found(val preview: ThemeCodePreview) : ThemeCodeRead

    /** No theme code, or one that is broken or too large. */
    data object Invalid : ThemeCodeRead

    /** A theme whose text cannot reach the contrast the app needs. */
    data object Unreadable : ThemeCodeRead
}

/** Reads the first theme code in a pasted [text] without importing it. */
suspend fun SessionRepository.readThemeCode(text: String): Outcome<ThemeCodeRead> =
    call("read theme code") {
        try {
            ThemeCodeRead.Found(it.themeCodePreview(text))
        } catch (_: ThemeCodeException.Invalid) {
            ThemeCodeRead.Invalid
        } catch (_: ThemeCodeException.Unreadable) {
            ThemeCodeRead.Unreadable
        }
    }
