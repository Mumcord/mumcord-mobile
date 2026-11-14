// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import android.app.PendingIntent
import android.content.Context
import app.mumcord.mobile.platform.audio.AudioController
import app.mumcord.mobile.platform.audio.AudioFocusChange
import app.mumcord.mobile.platform.call.CallSession
import app.mumcord.mobile.platform.call.FocusLoss
import app.mumcord.mobile.platform.call.InterruptionEffect
import app.mumcord.mobile.platform.call.InterruptionEvent
import app.mumcord.mobile.platform.call.Interruptions
import app.mumcord.mobile.platform.call.SessionEndedNotice
import app.mumcord.mobile.platform.call.SessionMarkerStore
import app.mumcord.mobile.platform.call.sessionEndedNotice
import app.mumcord.mobile.ui.connected.NotificationGateway
import app.mumcord.mobile.ui.connected.NotificationPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Connects the voice session to the phone: the background service and the call of
 * [CallSession], what phone calls and other interruptions do to the session, the notification
 * question, and the note on disk that tells a later start that a session was cut off.
 * It follows the session by itself once it has been created.
 *
 * @param content opens the app on the voice view.
 * @param scope lives as long as the process.
 */
class VoiceCall(
    context: Context,
    repository: SessionRepository,
    private val audio: AudioController,
    notificationGateway: NotificationGateway,
    content: PendingIntent,
    scope: CoroutineScope,
    log: (String) -> Unit,
) {
    private val markers = SessionMarkerStore(context)

    /** Read before the first state of this run can overwrite what the last run left behind. */
    private var sessionEnded: SessionEndedNotice? =
        sessionEndedNotice(markers.read(), markers.lastExitReason()).also { markers.clear() }

    private val control = SessionVoiceControl(repository, scope)

    private val interruptions = Interruptions(scope, control::current) { effect ->
        when (effect) {
            is InterruptionEffect.SetVoice -> control.set(effect.voice)
            is InterruptionEffect.PausePlayback -> audio.setPlaybackPaused(effect.paused)
        }
    }

    private val session = CallSession(
        context = context,
        info = repository.state.map(::callInfo).distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, callInfo(repository.state.value)),
        actions = control,
        content = content,
        onInterruption = interruptions::send,
        // The service, the notification and the call are the system's, which wants the main thread.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        log = log,
    )

    val notifications = NotificationPrompt(notificationGateway, onGranted = session::onAppVisible)

    init {
        scope.launch {
            repository.state.map(::markerStep).distinctUntilChanged().collect { step ->
                when (step) {
                    is MarkerStep.Mark -> markers.mark(step.marker)
                    MarkerStep.Clear -> markers.clear()
                    MarkerStep.Keep -> Unit
                }
            }
        }
    }

    /** The cut-off session of the last run, once; null when there was none. */
    fun takeSessionEndedNotice(): SessionEndedNotice? = sessionEnded.also { sessionEnded = null }

    /** The app came on screen. */
    fun onAppVisible() {
        session.onAppVisible()
        notifications.recheck()
    }

    /** The phone gave or took the audio focus of the voice session. */
    fun onAudioFocus(change: AudioFocusChange) {
        val event = when (change) {
            AudioFocusChange.Gained -> InterruptionEvent.FocusGained
            AudioFocusChange.LostTransient -> InterruptionEvent.FocusLost(FocusLoss.Transient, session.ringing())
            AudioFocusChange.LostCanDuck -> InterruptionEvent.FocusLost(FocusLoss.CanDuck, session.ringing())
            AudioFocusChange.Lost -> InterruptionEvent.FocusLost(FocusLoss.Permanent, session.ringing())
        }
        interruptions.send(event)
    }
}
