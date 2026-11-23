// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.MumcordApplication
import app.mumcord.mobile.platform.audio.AudioDirection
import app.mumcord.mobile.platform.audio.AudioProbe

/**
 * What the phone was doing at the moment the speaker had a gap, as one line in the test's
 * output and in the device log, so that a gap can be told apart afterwards: the speaker stream
 * stood still, the engine had nothing to play, the connection or the network changed, the
 * phone system moved the call, or the test itself was held up. States and numbers only.
 */
object GapEvidence {
    private const val LOG_TAG = "MumcordE2E"

    fun record(phone: PhoneSystem) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val state = (context as MumcordApplication).sessionRepository.state.value
        val polls = VoiceChecks.lastPolls.joinToString(",") { "${it.millis}ms/${"%.3f".format(it.level)}/${it.frames}f" }
        val line = "evidence: unix_ms=${System.currentTimeMillis()} polls=[$polls] " +
            "phase=${state.phase::class.simpleName} ${network(context)} " +
            "reduced_voice=${state.mobileSettings?.mobileDataReducedVoice} ${audio(context)} " +
            "call_route=${phone.callRoute()} idle=${idle(context)} " +
            "playback_frames=${AudioProbe.frames(AudioDirection.Playback)} " +
            "capture_frames=${AudioProbe.frames(AudioDirection.Capture)}"
        Log.i(LOG_TAG, line)
        VoiceChecks.report(line)
    }

    private fun network(context: Context): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return "network=none"
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return "network=${network.networkHandle}/unknown"
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        return "network=${network.networkHandle}/$transport " +
            "metered=${!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)} " +
            "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    private fun audio(context: Context): String {
        val audio = context.getSystemService(AudioManager::class.java)
        return "audio_mode=${audio.mode} output_type=${audio.communicationDevice?.type}"
    }

    private fun idle(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        return "${power.isDeviceIdleMode}/${power.isDeviceLightIdleMode}/save=${power.isPowerSaveMode}"
    }
}
