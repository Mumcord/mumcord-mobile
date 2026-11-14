// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user does on the connected screens: tabs, folding, search, joining, the sheets.
 * It lives in the view model's scope, so folded channels and the tab survive a rotation; the
 * end of a connection starts it afresh.
 */
class ConnectedController(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
) {
    private val mutableLocal = MutableStateFlow(ConnectedLocal())
    val local: StateFlow<ConnectedLocal> = mutableLocal.asStateFlow()

    init {
        scope.launch {
            repository.state.map { it.showsServer }.distinctUntilChanged().collect { shown ->
                if (!shown) mutableLocal.value = ConnectedLocal()
            }
        }
    }

    fun selectTab(tab: ConnectedTab) = mutableLocal.update { it.copy(tab = tab) }

    fun toggleCollapsed(channelId: UInt) = mutableLocal.update {
        val collapsed = if (channelId in it.collapsed) it.collapsed - channelId else it.collapsed + channelId
        it.copy(collapsed = collapsed, menuChannelId = null)
    }

    /** Joins a channel; the own channel is left alone. A needed password is asked for by an event. */
    fun join(channelId: UInt) {
        mutableLocal.update { it.copy(menuChannelId = null) }
        if (repository.state.value.server?.selfChannelId == channelId) return
        scope.launch { repository.joinChannel(channelId, password = null) }
    }

    fun openChannelMenu(channelId: UInt) = mutableLocal.update { it.copy(menuChannelId = channelId) }

    fun openSearch() = mutableLocal.update { it.copy(searching = true) }

    fun closeSearch() = mutableLocal.update { it.copy(searching = false, query = "") }

    fun changeQuery(query: String) = mutableLocal.update { it.copy(query = query) }

    /** Opens the person sheet. The own entry has none: its controls only make sense for others. */
    fun openPerson(session: UInt) {
        if (repository.state.value.server?.selfSession == session) return
        mutableLocal.update { it.copy(personSession = session, volumeDraft = null) }
    }

    fun changeVolume(percent: Int) {
        val session = mutableLocal.value.personSession ?: return
        val clamped = percent.coerceIn(0, MAX_VOLUME_PERCENT)
        mutableLocal.update { it.copy(volumeDraft = clamped) }
        scope.launch { repository.setPersonVolume(session, clamped) }
    }

    fun setLocalMute(muted: Boolean) {
        val session = mutableLocal.value.personSession ?: return
        scope.launch { repository.setPersonLocalMute(session, muted) }
    }

    fun changePassword(password: String) = mutableLocal.update { it.copy(password = password) }

    /** Tries the typed channel password; the sheet stays until the server answers. */
    fun submitPassword() {
        val prompt = repository.state.value.channelPassword ?: return
        val password = mutableLocal.value.password
        if (password.isEmpty()) return
        mutableLocal.update { it.copy(password = "") }
        scope.launch { repository.joinChannel(prompt.channelId, password) }
    }

    /** Closes whichever sheet is open; an open password question is given up. */
    fun dismissSheet() {
        if (repository.state.value.channelPassword != null) {
            repository.dismissChannelPassword()
            mutableLocal.update { it.copy(password = "") }
        } else {
            mutableLocal.update { it.copy(personSession = null, volumeDraft = null, menuChannelId = null) }
        }
    }

    fun dismissDenied() = repository.dismissDenied()

    companion object {
        const val MAX_VOLUME_PERCENT = 200
    }
}
