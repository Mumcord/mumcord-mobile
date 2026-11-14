// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.platform.call.AudioOutput
import app.mumcord.mobile.platform.call.AudioOutputs
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.OutputState
import kotlinx.coroutines.flow.MutableStateFlow

/** The phone's outputs, played by the test: a choice is taken at once, as the phone does. */
class FakeOutputs(initial: OutputState = OutputState()) : AudioOutputs {
    override val state = MutableStateFlow(initial)
    val selected = mutableListOf<String>()

    override fun select(id: String) {
        selected += id
        state.value.available.firstOrNull { it.id == id }?.let { state.value = state.value.copy(current = it) }
    }
}

val SPEAKER = AudioOutput("speaker", OutputKind.Speaker, "Speaker")
val EARPIECE = AudioOutput("earpiece", OutputKind.Earpiece, "Earpiece")
val WIRED = AudioOutput("wired", OutputKind.WiredHeadset, "Wired headset")
val BUDS = AudioOutput("buds", OutputKind.Bluetooth, "Lea's Buds")
