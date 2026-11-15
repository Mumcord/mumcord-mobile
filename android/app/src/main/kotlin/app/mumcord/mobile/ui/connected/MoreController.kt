// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.AppSettings
import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.bridge.MobileSettings
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.session.MessageNotificationSettings
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.ui.profile.ProfileController
import app.mumcord.mobile.ui.themes.ThemesController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user does on the More tab: the activity log and the settings. Every setting is
 * saved at once; a failure is shown like a refusal. The end of a connection starts it afresh.
 */
class MoreController(
    private val repository: SessionRepository,
    private val connected: ConnectedController,
    private val messageSettings: MessageNotificationSettings,
    private val scope: CoroutineScope,
) {
    private val mutableLocal = MutableStateFlow(MoreLocal())
    val local: StateFlow<MoreLocal> = mutableLocal.asStateFlow()

    /** The user's own profile and its editor (FR-5.80). */
    val profile = ProfileController(repository, scope, onClosed = ::back)

    /** The themes screen (FR-5.81). */
    val themes = ThemesController(repository, scope)

    /** The profile and themes state the More tab shows besides [local]. */
    val extras: Flow<MoreExtras> = combine(profile.own, profile.edit, themes.local, ::MoreExtras)

    init {
        scope.launch {
            repository.state.map { it.showsServer }.distinctUntilChanged().collect { shown ->
                if (!shown) {
                    mutableLocal.value = MoreLocal()
                    themes.reset()
                }
            }
        }
    }

    fun openProfile() {
        profile.open()
        mutableLocal.update { it.copy(page = MorePage.EditProfile) }
    }

    fun openThemes() = mutableLocal.update { it.copy(page = MorePage.Themes) }

    fun openActivity() = mutableLocal.update { it.copy(page = MorePage.Activity, seenActivityId = newestActivity()) }

    fun selectFilter(filter: ActivityFilter) = mutableLocal.update { it.copy(filter = filter) }

    /** Opens the settings and reads the voice settings, which have no event of their own. */
    fun openSettings() {
        mutableLocal.update { it.copy(page = MorePage.Settings, noiseSuppression = null) }
        scope.launch {
            val voice = repository.voiceSettings() ?: return@launch repository.reportFailure()
            // A switch flipped meanwhile is newer than what was read.
            mutableLocal.update { it.copy(noiseSuppression = it.noiseSuppression ?: voice.noiseSuppression) }
        }
    }

    /** Back to the list of the More tab; what was in the log until now counts as seen. */
    fun back() {
        when (mutableLocal.value.page) {
            MorePage.Themes -> themes.reset()
            MorePage.EditProfile -> profile.discard()
            else -> Unit
        }
        mutableLocal.update {
            val seen = if (it.page == MorePage.Activity) newestActivity() else it.seenActivityId
            it.copy(page = MorePage.Main, seenActivityId = seen, sheet = null)
        }
    }

    fun openSheet(sheet: SettingsSheet) = mutableLocal.update { it.copy(sheet = sheet) }

    fun closeSheet() = mutableLocal.update { it.copy(sheet = null) }

    fun changeMobile(change: (MobileSettings) -> MobileSettings) {
        scope.launch { report(repository.updateMobileSettings(change)) }
    }

    fun changeApp(change: (AppSettings) -> AppSettings) {
        scope.launch { report(repository.updateAppSettings(change)) }
    }

    fun setNoiseSuppression(on: Boolean) {
        mutableLocal.update { it.copy(noiseSuppression = on) }
        scope.launch {
            if (repository.setNoiseSuppression(on) is Outcome.Failed) {
                mutableLocal.update { it.copy(noiseSuppression = !on) }
                repository.reportFailure()
            }
        }
    }

    fun setSenderOnly(senderOnly: Boolean) = messageSettings.setSenderOnly(senderOnly)

    /** The same switch as in the voice view; a held talk button is let go. */
    fun selectTalkMode(mode: TalkMode) {
        closeSheet()
        connected.selectTalkMode(mode)
    }

    fun selectDefaultOutput(output: DefaultOutput) {
        closeSheet()
        changeMobile { it.copy(defaultOutput = output) }
    }

    fun selectHistoryAge(days: UInt?) {
        closeSheet()
        changeApp { it.copy(historyMaxAgeDays = days) }
    }

    private fun newestActivity(): ULong? = repository.state.value.activity.firstOrNull()?.id

    private fun report(outcome: Outcome<Unit>) {
        if (outcome is Outcome.Failed) repository.reportFailure()
    }
}
