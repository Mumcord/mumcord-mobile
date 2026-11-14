// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.session.Outcome
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

    /** The microphone level, 0 to 100; changes often, so only the level meter reads it. */
    val inputLevel: StateFlow<Int> = repository.inputLevel

    init {
        scope.launch {
            repository.state.map { it.showsServer }.distinctUntilChanged().collect { shown ->
                if (!shown) {
                    releaseHolds()
                    mutableLocal.value = ConnectedLocal()
                }
            }
        }
        // The whisper bar goes away with the person or channel it is for.
        scope.launch {
            repository.state.collect { session ->
                val target = mutableLocal.value.whisperTarget ?: return@collect
                if (session.server?.whisperName(target) == null) closeWhisper()
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

    // ----- voice -----

    fun openVoice() = mutableLocal.update { it.copy(voiceOpen = true) }

    /** Closes the full voice view; its hold-to-talk button goes with it. */
    fun closeVoice() {
        releaseTalk()
        mutableLocal.update { it.copy(voiceOpen = false) }
    }

    /** Mutes or unmutes the user. Unmuting also ends a deafen (the engine does that). */
    fun toggleMute() {
        val self = self() ?: return
        scope.launch { repository.setSelfMute(!(self.selfMuted || self.selfDeafened)) }
    }

    /** Deafens the user, which also mutes them, or ends it. */
    fun toggleDeafen() {
        val self = self() ?: return
        scope.launch { repository.setSelfDeaf(!self.selfDeafened) }
    }

    /** Switches the talk mode at once and for good. A held button of the old mode is let go. */
    fun selectTalkMode(mode: TalkMode) {
        if (repository.state.value.talkMode == mode) return
        releaseTalk()
        scope.launch { report(repository.setTalkMode(mode)) }
    }

    /** The hold-to-talk button went down or up. It only talks in push-to-talk mode. */
    fun setTalkHeld(held: Boolean) {
        if (!held) return releaseTalk()
        if (repository.state.value.talkMode != TalkMode.PUSH_TO_TALK || mutableLocal.value.talkHeld) return
        mutableLocal.update { it.copy(talkHeld = true) }
        repository.setPushToTalkPressed(true)
    }

    private fun releaseTalk() {
        if (!mutableLocal.value.talkHeld) return
        mutableLocal.update { it.copy(talkHeld = false) }
        repository.setPushToTalkPressed(false)
    }

    /**
     * Lets go of every hold button. Called when the app leaves the screen, so that nothing
     * keeps talking unseen. (Should the process die instead, the engine dies with it, and the
     * bridge lets go of both buttons at the start of every connection.)
     */
    fun releaseHolds() {
        releaseTalk()
        releaseWhisper()
    }

    /** Shows the whisper bar for a person or a channel; nothing is sent until it is held. */
    fun openWhisper(target: WhisperTarget) {
        releaseWhisper()
        mutableLocal.update {
            it.copy(whisperTarget = target, personSession = null, volumeDraft = null, menuChannelId = null)
        }
    }

    /** The hold-to-whisper button went down or up. */
    fun setWhisperHeld(held: Boolean) {
        if (!held) return releaseWhisper()
        val target = mutableLocal.value.whisperTarget ?: return
        if (mutableLocal.value.whisperHeld) return
        mutableLocal.update { it.copy(whisperHeld = true) }
        scope.launch {
            val started = repository.startWhisper(target)
            when {
                // Let go while the engine was still starting: the release came too early for it.
                !mutableLocal.value.whisperHeld -> repository.stopWhisper()
                !started -> mutableLocal.update { it.copy(whisperHeld = false) }
            }
        }
    }

    private fun releaseWhisper() {
        if (!mutableLocal.value.whisperHeld) return
        mutableLocal.update { it.copy(whisperHeld = false) }
        repository.stopWhisper()
    }

    /** Puts the whisper bar away, which ends the whisper. */
    fun closeWhisper() {
        releaseWhisper()
        mutableLocal.update { it.copy(whisperTarget = null) }
    }

    private fun self(): Person? = repository.state.value.server?.let { it.people[it.selfSession] }

    private fun report(outcome: Outcome<Unit>) {
        if (outcome is Outcome.Failed) repository.reportFailure()
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
