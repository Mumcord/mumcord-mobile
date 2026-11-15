// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.about

import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.update.AppInfo
import app.mumcord.mobile.update.AvailableUpdate
import app.mumcord.mobile.update.UpdateChecker
import app.mumcord.mobile.update.updateNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The About screen, its licences and the update notice (FR-5.100, FR-5.101). The update switch
 * is the engine setting `update_check`; without [updates] (tests, previews) nothing is checked.
 */
class AboutController(
    val info: AppInfo,
    private val updates: UpdateChecker?,
    private val repository: SessionRepository,
    private val notices: NoticesSource,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val licences = MutableStateFlow<Licences>(Licences.NotRead)

    private val enabled = repository.state.map { it.mobileSettings?.updateCheck == true }

    /** The release the notices offer, on the server list and on the About screen. */
    val update: StateFlow<AvailableUpdate?> =
        if (updates == null) {
            MutableStateFlow(null)
        } else {
            combine(enabled, updates.available, updates.dismissed) { on, available, dismissed ->
                updateNotice(updates.installed, on, available, dismissed)
            }.stateIn(scope, SharingStarted.Eagerly, null)
        }

    val local: StateFlow<AboutLocal> =
        combine(update, licences) { update, licences -> AboutLocal(info, update, licences) }
            .stateIn(scope, SharingStarted.Eagerly, AboutLocal(info))

    /** Called whenever the app comes to the front; the checker decides whether a check is due. */
    fun checkIfDue() {
        val checker = updates ?: return
        scope.launch {
            // The switch as saved now, not as last reported: the check may run before any event.
            val on = repository.mobileSettings()?.updateCheck ?: return@launch
            checker.checkIfDue(on)
        }
    }

    fun dismissUpdate() {
        val version = update.value?.version ?: return
        updates?.dismiss(version)
    }

    /** Reads the notices the first time the licences open, and again after a failure. */
    fun loadLicences() {
        if (licences.value is Licences.Loaded || licences.value is Licences.Loading) return
        licences.value = Licences.Loading
        scope.launch {
            licences.value = try {
                Licences.Loaded(withContext(io) { noticeBlocks(notices.read()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("third-party notices unreadable: ${e.javaClass.simpleName}")
                Licences.Failed
            }
        }
    }
}
