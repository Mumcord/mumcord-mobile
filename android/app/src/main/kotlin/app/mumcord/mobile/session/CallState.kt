// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.platform.call.CallActions
import app.mumcord.mobile.platform.call.CallInfo
import app.mumcord.mobile.platform.call.SelfVoice
import app.mumcord.mobile.platform.call.SessionMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The session as the phone system shows it, or null while no server is shown. */
fun callInfo(state: SessionState): CallInfo? {
    val server = state.server?.takeIf { state.showsServer } ?: return null
    val self = server.people[server.selfSession]
    return CallInfo(
        serverName = state.activeServerName,
        channelName = server.selfChannelId?.let(server.channels::get)?.name,
        muted = self?.selfMuted == true || self?.selfDeafened == true,
        deafened = self?.selfDeafened == true,
    )
}

/** What the user is set to on the server, or null while they are on none. */
fun selfVoice(state: SessionState): SelfVoice? {
    val server = state.server?.takeIf { state.showsServer } ?: return null
    val self = server.people[server.selfSession] ?: return null
    return SelfVoice(muted = self.selfMuted || self.selfDeafened, deafened = self.selfDeafened)
}

/** What to remember on disk about the running session, so a cut-off one is noticed (FR-5.37). */
sealed interface MarkerStep {
    data class Mark(val marker: SessionMarker) : MarkerStep

    /** The session ended in an orderly way: the user left, or the app said why it failed. */
    data object Clear : MarkerStep

    /** A connection is being made; nothing is known yet. */
    data object Keep : MarkerStep
}

fun markerStep(state: SessionState): MarkerStep {
    val serverId = state.activeServerId
    return when {
        state.showsServer && serverId != null -> MarkerStep.Mark(SessionMarker(serverId, state.activeServerName))
        !state.isBusy -> MarkerStep.Clear
        else -> MarkerStep.Keep
    }
}

private val SessionState.activeServerName: String
    get() = servers.firstOrNull { it.id == activeServerId }?.name.orEmpty()

/**
 * Mute, deafen and leave for everything outside the screens: the buttons of the session's
 * notification and the phone call handling. Nothing here can open the microphone in
 * push-to-talk mode: that takes the hold button, which only exists on screen (FR-5.21).
 *
 * @param scope lives as long as the process.
 */
class SessionVoiceControl(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
) : CallActions {
    fun current(): SelfVoice? = selfVoice(repository.state.value)

    /** As the mute button on screen: unmuting also ends a deafen. */
    override fun toggleMute() {
        val self = current() ?: return
        scope.launch { repository.setSelfMute(!self.muted) }
    }

    override fun toggleDeafen() {
        val self = current() ?: return
        scope.launch { repository.setSelfDeaf(!self.deafened) }
    }

    override fun leave() {
        scope.launch { repository.disconnect() }
    }

    /** Sets both at once. Deafening includes muting; ending it leaves the mute to be set. */
    suspend fun set(voice: SelfVoice) {
        if (voice.deafened) {
            repository.setSelfDeaf(true)
        } else {
            repository.setSelfDeaf(false)
            repository.setSelfMute(voice.muted)
        }
    }
}
