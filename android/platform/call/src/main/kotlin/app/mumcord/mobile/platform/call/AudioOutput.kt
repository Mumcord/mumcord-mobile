// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.media.AudioDeviceInfo
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.flow.StateFlow

enum class OutputKind { Speaker, Earpiece, WiredHeadset, Bluetooth }

/**
 * Where the voice of a session can be heard. [name] is the phone's own name for it; for a
 * Bluetooth device that is the name its owner gave it.
 */
data class AudioOutput(val id: String, val kind: OutputKind, val name: String) {
    val isHeadset: Boolean
        get() = kind == OutputKind.WiredHeadset || kind == OutputKind.Bluetooth
}

/** The outputs of the running session; empty and null while there is none. */
data class OutputState(
    val available: List<AudioOutput> = emptyList(),
    val current: AudioOutput? = null,
)

/** The audio outputs of the voice session (FR-5.34). [state] follows the phone by itself. */
interface AudioOutputs {
    val state: StateFlow<OutputState>

    /** Moves the voice to the output with this id at once; an unknown id does nothing. */
    fun select(id: String)
}

/** What the phone system's wish to end the session's call means. */
enum class EndRequest { Leave, ToggleMute }

/**
 * The one rule for a call that the phone system ends (FR-5.35).
 *
 * While a call exists the phone system keeps a headset's button for itself and turns one press
 * into "end the call"; a Bluetooth hands-free button arrives the same way. So with the voice
 * on a headset, that request is the headset's button and toggles the microphone. On the
 * speaker or the earpiece no headset can have asked, and the session is left as before.
 *
 * A car's hang-up button cannot be told from an earbud's button and therefore mutes too.
 *
 * @param treatAsHeadset for tests on a phone that has no headset; never set in a release.
 */
fun endRequestMeans(current: AudioOutput?, treatAsHeadset: Boolean = false): EndRequest =
    if (treatAsHeadset || current?.isHeadset == true) EndRequest.ToggleMute else EndRequest.Leave

/** An endpoint of the phone system's call, or null for a kind the app does not offer. */
internal fun outputOfEndpoint(type: Int, name: String, id: String): AudioOutput? {
    val kind = when (type) {
        CallEndpointCompat.TYPE_SPEAKER -> OutputKind.Speaker
        CallEndpointCompat.TYPE_EARPIECE -> OutputKind.Earpiece
        CallEndpointCompat.TYPE_WIRED_HEADSET -> OutputKind.WiredHeadset
        CallEndpointCompat.TYPE_BLUETOOTH -> OutputKind.Bluetooth
        else -> return null
    }
    return AudioOutput(id, kind, name)
}

/** A device the phone offers for calls, or null for a kind the app does not offer. */
internal fun outputOfDevice(type: Int, name: String, id: Int): AudioOutput? {
    val kind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.Speaker
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.Earpiece
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> OutputKind.WiredHeadset
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> OutputKind.Bluetooth
        else -> return null
    }
    return AudioOutput(id.toString(), kind, name)
}
