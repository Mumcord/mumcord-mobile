// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.media.AudioDeviceInfo
import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The outputs of a session as values, and the one rule for a call the phone system ends. */
class AudioOutputTest {
    private val speaker = AudioOutput("s", OutputKind.Speaker, "Speaker")
    private val earpiece = AudioOutput("e", OutputKind.Earpiece, "Earpiece")
    private val wired = AudioOutput("w", OutputKind.WiredHeadset, "Wired headset")
    private val buds = AudioOutput("b", OutputKind.Bluetooth, "Buds")

    @Test
    fun onAHeadsetTheSystemsEndOfTheCallIsTheHeadsetsButton() {
        assertEquals(EndRequest.ToggleMute, endRequestMeans(wired))
        assertEquals(EndRequest.ToggleMute, endRequestMeans(buds))
    }

    @Test
    fun onTheSpeakerOrTheEarpieceTheSessionIsLeft() {
        assertEquals(EndRequest.Leave, endRequestMeans(speaker))
        assertEquals(EndRequest.Leave, endRequestMeans(earpiece))
        assertEquals("An unknown output is no headset", EndRequest.Leave, endRequestMeans(null))
    }

    @Test
    fun aTestMayHaveAnyOutputCountAsAHeadset() {
        assertEquals(EndRequest.ToggleMute, endRequestMeans(speaker, treatAsHeadset = true))
        assertEquals(EndRequest.ToggleMute, endRequestMeans(null, treatAsHeadset = true))
    }

    @Test
    fun theCallsEndpointsBecomeOutputsAndOtherKindsAreLeftOut() {
        assertEquals(speaker, outputOfEndpoint(CallEndpointCompat.TYPE_SPEAKER, "Speaker", "s"))
        assertEquals(earpiece, outputOfEndpoint(CallEndpointCompat.TYPE_EARPIECE, "Earpiece", "e"))
        assertEquals(wired, outputOfEndpoint(CallEndpointCompat.TYPE_WIRED_HEADSET, "Wired headset", "w"))
        assertEquals(buds, outputOfEndpoint(CallEndpointCompat.TYPE_BLUETOOTH, "Buds", "b"))
        assertNull(outputOfEndpoint(CallEndpointCompat.TYPE_STREAMING, "Tablet", "t"))
    }

    @Test
    fun thePhonesCallDevicesBecomeOutputsAndOtherKindsAreLeftOut() {
        assertEquals(OutputKind.Speaker, outputOfDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone", 1)?.kind)
        assertEquals(OutputKind.Earpiece, outputOfDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Phone", 2)?.kind)
        assertEquals(OutputKind.WiredHeadset, outputOfDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Phone", 3)?.kind)
        assertEquals(OutputKind.WiredHeadset, outputOfDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Phone", 4)?.kind)
        assertEquals(OutputKind.WiredHeadset, outputOfDevice(AudioDeviceInfo.TYPE_USB_HEADSET, "Phone", 5)?.kind)
        assertEquals(AudioOutput("6", OutputKind.Bluetooth, "Buds"), outputOfDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds", 6))
        assertEquals(OutputKind.Bluetooth, outputOfDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", 7)?.kind)
        assertNull(outputOfDevice(AudioDeviceInfo.TYPE_HDMI, "TV", 8))
    }
}
