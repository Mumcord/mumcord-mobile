// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** What the phone system reports about the session's call. */
internal interface CallListener {
    /** The system put the call on hold (true) or took it off hold (false). */
    fun onHeld(held: Boolean)

    /**
     * The system ends the call: a headset's button, a car's hang-up button. True when the
     * session goes on, so the call is added again; false when the session is being left.
     */
    fun onEndRequested(): Boolean

    /** The system muted or unmuted the call's microphone (a long press on a wired headset). */
    fun onMuted(muted: Boolean)

    /** The call's outputs changed; null when the system has no call for the session (any more). */
    fun onOutputs(state: OutputState?)
}

/** Adds a call to the phone system and returns when it is over; a seam for the tests. */
internal fun interface CallAdder {
    suspend fun addCall(
        displayName: String,
        onDisconnect: () -> Unit,
        onActive: () -> Unit,
        onInactive: () -> Unit,
        block: CallControlScope.() -> Unit,
    )
}

/** The phone system's own call service. */
internal class TelecomCallAdder(context: Context) : CallAdder {
    private val callsManager = CallsManager(context.applicationContext)
    private var registered = false

    override suspend fun addCall(
        displayName: String,
        onDisconnect: () -> Unit,
        onActive: () -> Unit,
        onInactive: () -> Unit,
        block: CallControlScope.() -> Unit,
    ) {
        if (!registered) {
            callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            registered = true
        }
        callsManager.addCall(
            CallAttributesCompat(
                displayName = displayName,
                address = ADDRESS,
                direction = CallAttributesCompat.DIRECTION_OUTGOING,
                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
                isLogExcluded = true,
            ),
            onAnswer = { },
            onDisconnect = { onDisconnect() },
            onSetActive = { onActive() },
            onSetInactive = { onInactive() },
            block = block,
        )
    }

    private companion object {
        /** Calls need an address; a session has none that would mean anything to the system. */
        val ADDRESS: Uri = Uri.fromParts("mumcord", "voice", null)
    }
}

/**
 * The voice session as a call that the app manages itself (FR-5.31). With it the phone system
 * routes the audio like a call (Bluetooth hands-free, car systems), offers the call's outputs
 * (FR-5.34) and holds the session when the user accepts a normal phone call.
 *
 * The call never appears in the recent calls: on Android 16 QPR2 and later the call says so
 * itself (`isLogExcluded`), and before that the system only logs self-managed calls of apps
 * that ask for it (`PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS`), which this app does not.
 *
 * The name of a running call cannot be changed, so it stays what it was when the session
 * began; the notification follows the channel.
 *
 * The system ends the call when a headset's button is pressed and cannot be told not to.
 * When [CallListener.onEndRequested] says that the session goes on, the call is therefore
 * added again at once. Should the system refuse that, the session goes on without a call.
 *
 * @param scope must run on one thread; everything here happens on it.
 */
internal class TelecomCall(
    private val adder: CallAdder,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val listener: CallListener,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var job: Job? = null
    private var control: CallControlScope? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()

    /** Adds the call. A phone that refuses it keeps the session; it is then no call to the system. */
    fun start(displayName: String) {
        if (job != null) return
        job = scope.launch {
            try {
                var endedAt: Long? = null
                do {
                    var again = false
                    lateinit var call: Job
                    call = launch {
                        try {
                            adder.addCall(
                                displayName,
                                onDisconnect = {
                                    again = listener.onEndRequested()
                                    endedAt = now()
                                    // The system's call is over, but its scope does not end by
                                    // itself. It is ended once the system has its answer.
                                    scope.launch {
                                        yield()
                                        call.cancel()
                                    }
                                },
                                onActive = { listener.onHeld(false) },
                                onInactive = { listener.onHeld(true) },
                            ) {
                                control = this
                                endedAt?.let { log("the call was added again after ${now() - it} ms") }
                                // A channel is joined at once: there is no ringing on the other side.
                                launch { report("activating", setActive()) }
                                launch { followOutputs() }
                                launch { followMute() }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            log("the phone system did not take the voice session as a call: ${failure.javaClass.simpleName}")
                            again = false
                        }
                    }
                    call.join()
                    control = null
                    endpoints = emptyList()
                } while (again)
                listener.onOutputs(null)
            } finally {
                control = null
                endpoints = emptyList()
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
            if (result is CallControlResult.Success) listener.onHeld(false)
        }
    }

    /** Moves the call's audio to the endpoint with this id. */
    fun select(id: String) {
        val scopeOfCall = control ?: return
        val endpoint = endpoints.firstOrNull { it.identifier.toString() == id } ?: return
        scope.launch { report("changing the output of", scopeOfCall.requestEndpointChange(endpoint)) }
    }

    private suspend fun CallControlScope.followOutputs() {
        combine(availableEndpoints, currentCallEndpoint) { available, current -> available to current }
            .collect { (available, current) ->
                endpoints = available
                listener.onOutputs(OutputState(available.mapNotNull(::output), output(current)))
            }
    }

    private suspend fun CallControlScope.followMute() {
        // The first value is how the call starts, not something anybody did.
        var known: Boolean? = null
        isMuted.collect { muted ->
            if (known != null && known != muted) listener.onMuted(muted)
            known = muted
        }
    }

    private fun output(endpoint: CallEndpointCompat): AudioOutput? =
        outputOfEndpoint(endpoint.type, endpoint.name.toString(), endpoint.identifier.toString())

    private fun report(what: String, result: CallControlResult) {
        if (result is CallControlResult.Error) log("$what the call failed with code ${result.errorCode}")
    }
}
