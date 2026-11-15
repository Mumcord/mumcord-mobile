// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.themes

import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.ThemeCodeRead
import app.mumcord.mobile.session.deleteCustomTheme
import app.mumcord.mobile.session.importThemeCode
import app.mumcord.mobile.session.readThemeCode
import app.mumcord.mobile.session.setAppTheme
import app.mumcord.mobile.session.setThemeMode
import app.mumcord.mobile.session.themeShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user does on the themes screen. Every choice is saved at once and comes back as the
 * engine's theme event, which redraws the app; a failure is shown like a refusal.
 */
class ThemesController(private val repository: SessionRepository, private val scope: CoroutineScope) {
    private val mutableLocal = MutableStateFlow(ThemesLocal())
    val local: StateFlow<ThemesLocal> = mutableLocal.asStateFlow()

    private var reading: Job? = null

    fun setMode(mode: ThemeMode) = save { repository.setThemeMode(mode) }

    fun apply(themeId: String) = save { repository.setAppTheme(themeId) }

    fun openActions(themeId: String) = mutableLocal.update { it.copy(sheet = ThemesSheet.Actions(themeId)) }

    fun openPaste() = mutableLocal.update { it.copy(sheet = ThemesSheet.Paste()) }

    fun closeSheet() {
        reading?.cancel()
        mutableLocal.update { it.copy(sheet = null) }
    }

    /** Hands the theme's share message to the share sheet (see [shared]). */
    fun share(themeId: String) {
        closeSheet()
        scope.launch {
            when (val share = repository.themeShare(themeId)) {
                is Outcome.Ok -> mutableLocal.update { it.copy(shareText = share.value.message) }
                is Outcome.Failed -> repository.reportFailure()
            }
        }
    }

    /** The share sheet has the message. */
    fun shared() = mutableLocal.update { it.copy(shareText = null) }

    fun requestDelete(themeId: String) = mutableLocal.update { it.copy(sheet = ThemesSheet.ConfirmDelete(themeId)) }

    fun confirmDelete(themeId: String) {
        closeSheet()
        save { repository.deleteCustomTheme(themeId) }
    }

    /** The pasted text changed: the engine reads it again for the preview. */
    fun changePasteText(text: String) {
        mutableLocal.update { local ->
            val sheet = local.sheet as? ThemesSheet.Paste ?: return
            local.copy(sheet = sheet.copy(text = text, read = null))
        }
        reading?.cancel()
        if (text.isBlank()) return
        reading = scope.launch {
            val read = when (val outcome = repository.readThemeCode(text)) {
                is Outcome.Ok -> outcome.value
                is Outcome.Failed -> ThemeCodeRead.Invalid
            }
            mutableLocal.update { local ->
                val sheet = local.sheet as? ThemesSheet.Paste
                if (sheet == null || sheet.text != text) local else local.copy(sheet = sheet.copy(read = read))
            }
        }
    }

    /**
     * "Apply theme": adds the pasted theme to the user's own themes (once) and makes it the app
     * theme, as the desktop's theme card does.
     */
    fun applyPasted() {
        val sheet = mutableLocal.value.sheet as? ThemesSheet.Paste ?: return
        if (sheet.read !is ThemeCodeRead.Found || sheet.applying) return
        mutableLocal.update { it.copy(sheet = sheet.copy(applying = true)) }
        scope.launch {
            val applied = when (val imported = repository.importThemeCode(sheet.text)) {
                is Outcome.Ok -> repository.setAppTheme(imported.value.id)
                is Outcome.Failed -> imported
            }
            when (applied) {
                is Outcome.Ok -> closeSheet()
                is Outcome.Failed -> {
                    mutableLocal.update { local ->
                        val open = local.sheet as? ThemesSheet.Paste
                        if (open == null) local else local.copy(sheet = open.copy(applying = false))
                    }
                    repository.reportFailure()
                }
            }
        }
    }

    /** The screen was left or the connection ended. */
    fun reset() {
        reading?.cancel()
        mutableLocal.value = ThemesLocal()
    }

    private fun save(command: suspend () -> Outcome<Unit>) {
        scope.launch { if (command() is Outcome.Failed) repository.reportFailure() }
    }
}
