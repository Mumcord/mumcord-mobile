// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * The outputs of a session that the phone system did not take as a call (a tablet without
 * telephony, or a refusal): the devices the phone offers for calls, chosen through the audio
 * system directly. While a call exists its endpoints are used instead and this stays stopped.
 *
 * Everything here runs on the main thread.
 *
 * @param publish receives the outputs whenever they change.
 */
internal class DeviceOutputs(
    context: Context,
    private val log: (String) -> Unit,
    private val publish: (OutputState) -> Unit,
) {
    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private var running = false

    private val devicesListener = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private val currentListener = AudioManager.OnCommunicationDeviceChangedListener { refresh() }

    fun start() {
        if (running) return
        running = true
        audioManager.registerAudioDeviceCallback(devicesListener, Handler(Looper.getMainLooper()))
        audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, currentListener)
        refresh()
    }

    /** Stops following the devices and withdraws the app's own choice. */
    fun stop() {
        if (!running) return
        running = false
        audioManager.unregisterAudioDeviceCallback(devicesListener)
        audioManager.removeOnCommunicationDeviceChangedListener(currentListener)
        audioManager.clearCommunicationDevice()
    }

    fun select(id: String) {
        if (!running) return
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.id.toString() == id } ?: return
        if (!audioManager.setCommunicationDevice(device)) log("the phone refused the chosen audio output")
    }

    private fun refresh() {
        if (!running) return
        publish(
            OutputState(
                available = audioManager.availableCommunicationDevices.mapNotNull(::output),
                current = audioManager.communicationDevice?.let(::output),
            ),
        )
    }

    private fun output(device: AudioDeviceInfo): AudioOutput? =
        outputOfDevice(device.type, device.productName?.toString().orEmpty(), device.id)
}
