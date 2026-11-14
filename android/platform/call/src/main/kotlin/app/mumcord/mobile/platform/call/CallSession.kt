// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps a voice session alive in the background and shows it to the phone system as a call
 * (FR-5.30 to FR-5.32). While [info] has a value there is a foreground service with the
 * session's notification and a self-managed call; both end with the session.
 *
 * The service is only ever started when a session begins or the app comes on screen: the
 * system refuses a service that uses the microphone when it is started from the background.
 * A refused start is tried again by [onAppVisible].
 *
 * @param info the session, or null while there is none.
 * @param content opens the app on the voice view when the notification is tapped.
 * @param onInterruption receives what the phone reports about calls; see [Interruptions].
 * @param scope lives as long as the process and runs on the main thread.
 */
class CallSession(
    context: Context,
    private val info: StateFlow<CallInfo?>,
    internal val actions: CallActions,
    private val content: PendingIntent,
    private val onInterruption: (InterruptionEvent) -> Unit,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val telecom = TelecomCall(
        this.context,
        scope,
        log,
        onHeld = { held ->
            this.held = held
            onInterruption(InterruptionEvent.CallHeld(held))
        },
        onEndedBySystem = actions::leave,
    )

    /** The session the service and the notification show; kept until the service has stopped. */
    private var shown: CallInfo? = null
    private var startedAtMillis = 0L
    private var serviceRunning = false
    private var serviceTypes = 0
    private var held = false

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        val inPhoneCall = mode == AudioManager.MODE_IN_CALL
        onInterruption(InterruptionEvent.PhoneCall(inPhoneCall))
        // The system leaves the session's call on hold when the phone call is over (seen on
        // Android 17); it has to be taken off hold from here.
        if (!inPhoneCall && held) telecom.resume()
    }

    init {
        active = this
        createCallChannel(this.context)
        scope.launch { info.collect(::follow) }
    }

    /**
     * The app is on screen: a service that could not be started before is started now, and one
     * that runs without the microphone type gets it once the microphone is allowed. The
     * notification is shown again in case the user has just allowed notifications.
     */
    fun onAppVisible() {
        val current = shown ?: return
        if (!serviceRunning || serviceTypes != wantedTypes()) startService() else post(current)
    }

    /** Whether the phone only rings for an incoming call at this moment. */
    fun ringing(): Boolean = audioManager.mode == AudioManager.MODE_RINGTONE

    private fun follow(next: CallInfo?) {
        val step = serviceStep(serviceRunning, shown, next)
        if (shown == null && next != null) begin(next)
        if (next != null) shown = next
        when (step) {
            ServiceStep.Start -> startService()
            ServiceStep.Update -> next?.let(::post)
            ServiceStep.Stop -> end()
            ServiceStep.None -> Unit
        }
    }

    private fun begin(first: CallInfo) {
        startedAtMillis = System.currentTimeMillis()
        telecom.start(first.title)
        audioManager.addOnModeChangedListener(context.mainExecutor, modeListener)
    }

    private fun end() {
        audioManager.removeOnModeChangedListener(modeListener)
        telecom.end()
        context.stopService(Intent(context, CallSessionService::class.java))
        serviceRunning = false
        shown = null
        held = false
        onInterruption(InterruptionEvent.SessionEnded)
    }

    private fun startService() {
        try {
            context.startForegroundService(Intent(context, CallSessionService::class.java))
        } catch (refused: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app is not on screen.
            log("the voice session's service could not be started now: ${refused.javaClass.simpleName}")
        }
    }

    /** Called by the service once it exists: makes it a foreground service with the notification. */
    internal fun promote(service: Service): Boolean {
        val current = shown ?: return false
        val types = wantedTypes()
        return try {
            service.startForeground(CALL_NOTIFICATION_ID, notification(current), types)
            serviceRunning = true
            serviceTypes = types
            true
        } catch (refused: RuntimeException) {
            // The system refuses the microphone type for a service started from the background
            // (SecurityException), and any type while the app may not start one at all.
            log("the voice session's service was refused: ${refused.javaClass.simpleName}")
            serviceRunning = false
            false
        }
    }

    internal fun serviceDestroyed() {
        serviceRunning = false
    }

    private fun post(current: CallInfo) {
        if (serviceRunning) notifications.notify(CALL_NOTIFICATION_ID, notification(current))
    }

    private fun notification(current: CallInfo) = callNotification(context, current, startedAtMillis, content)

    private fun wantedTypes(): Int = foregroundServiceTypes(
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    )

    internal companion object {
        /**
         * The one session of the process, for the service and the notification's buttons. It
         * only holds the application's context, which lives as long as the process anyway.
         */
        @SuppressLint("StaticFieldLeak")
        @Volatile
        var active: CallSession? = null
            private set
    }
}

/**
 * The foreground service of a voice session. It does nothing itself: its existence, with the
 * call notification, is what tells the system to keep the app and its audio running. It is
 * not restarted after the system ended the app; the next start tells the user instead.
 */
internal class CallSessionService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (CallSession.active?.promote(this) != true) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CallSession.active?.serviceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
