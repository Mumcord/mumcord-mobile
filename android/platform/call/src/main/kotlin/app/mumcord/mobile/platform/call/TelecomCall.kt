// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The voice session as a call that the app manages itself (FR-5.31). With it the phone system
 * routes the audio like a call (Bluetooth hands-free, car systems) and holds the session when
 * the user accepts a normal phone call.
 *
 * The call never appears in the recent calls: on Android 16 QPR2 and later the call says so
 * itself (`isLogExcluded`), and before that the system only logs self-managed calls of apps
 * that ask for it (`PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS`), which this app does not.
 *
 * The name of a running call cannot be changed, so it stays what it was when the session
 * began; the notification follows the channel.
 *
 * @param scope must run on one thread; everything here happens on it.
 * @param onHeld the system put the call on hold (true) or took it off hold (false).
 * @param onEndedBySystem the system ended the call, for example a car's hang-up button.
 */
internal class TelecomCall(
    context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onHeld: (Boolean) -> Unit,
    private val onEndedBySystem: () -> Unit,
) {
    private val callsManager = CallsManager(context.applicationContext)
    private var registered = false
    private var job: Job? = null
    private var control: CallControlScope? = null

    /** Adds the call. A phone that refuses it keeps the session; it is then no call to the system. */
    fun start(displayName: String) {
        if (job != null) return
        job = scope.launch {
            try {
                if (!registered) {
                    callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                    registered = true
                }
                // Returns when the call is over.
                callsManager.addCall(
                    attributes(displayName),
                    onAnswer = { },
                    onDisconnect = { onEndedBySystem() },
                    onSetActive = { onHeld(false) },
                    onSetInactive = { onHeld(true) },
                ) {
                    control = this
                    // A channel is joined at once: there is no ringing on the other side.
                    launch { report("activating", setActive()) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("the phone system did not take the voice session as a call: ${failure.javaClass.simpleName}")
            } finally {
                control = null
                job = null
            }
        }
    }

    /** Ends the call because the session is over. */
    fun end() {
        val running = job ?: return
        val scopeOfCall = control
        scope.launch {
            scopeOfCall?.let { report("ending", it.disconnect(DisconnectCause(DisconnectCause.LOCAL))) }
            running.cancel()
        }
    }

    /**
     * Takes the call off hold after the phone call that held it is over. The system only
     * reports a hold that it ended itself, so the end of this one is reported here.
     */
    fun resume() {
        val scopeOfCall = control ?: return
        scope.launch {
            val result = scopeOfCall.setActive()
            report("resuming", result)
            if (result is CallControlResult.Success) onHeld(false)
        }
    }

    private fun report(what: String, result: CallControlResult) {
        if (result is CallControlResult.Error) log("$what the call failed with code ${result.errorCode}")
    }

    private fun attributes(displayName: String) = CallAttributesCompat(
        displayName = displayName,
        address = ADDRESS,
        direction = CallAttributesCompat.DIRECTION_OUTGOING,
        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
        callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        isLogExcluded = true,
    )

    private companion object {
        /** Calls need an address; a session has none that would mean anything to the system. */
        val ADDRESS: Uri = Uri.fromParts("mumcord", "voice", null)
    }
}
