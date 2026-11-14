// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.platform.call.AudioOutput
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.OutputState

/**
 * One line of the output sheet. [name] is the phone's name of a Bluetooth device (untrusted
 * text); the other kinds have the app's own words. A line with [id] null stands for a wired
 * headset that is not connected and cannot be picked.
 */
data class OutputRow(val id: String?, val kind: OutputKind, val name: String, val selected: Boolean)

/** The audio output for the voice view: the chip and the lines of the sheet. */
data class OutputUiState(val current: AudioOutput, val rows: List<OutputRow>)

/**
 * The outputs as the sheet lists them (mockup `04-audio-output`): speaker, earpiece, every
 * Bluetooth device by name, and the wired headset, which is listed as not connected while
 * there is none. Null while no session has an output.
 */
fun outputUiState(state: OutputState): OutputUiState? {
    val current = state.current ?: return null
    fun rows(kind: OutputKind) = state.available.filter { it.kind == kind }
        .map { OutputRow(it.id, it.kind, it.name, selected = it.id == current.id) }
    val wired = rows(OutputKind.WiredHeadset)
        .ifEmpty { listOf(OutputRow(id = null, OutputKind.WiredHeadset, name = "", selected = false)) }
    return OutputUiState(
        current = current,
        rows = rows(OutputKind.Speaker) + rows(OutputKind.Earpiece) + rows(OutputKind.Bluetooth) + wired,
    )
}
