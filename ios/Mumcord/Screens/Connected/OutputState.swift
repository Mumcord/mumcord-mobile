// SPDX-License-Identifier: AGPL-3.0-only

/// One line of the output sheet. `name` is the phone's name of a Bluetooth device (untrusted
/// text); the other kinds have the app's own words. A line with `id` nil stands for a wired
/// headset that is not connected and cannot be picked.
struct OutputRow: Equatable {
    let id: String?
    let kind: OutputKind
    let name: String
    let selected: Bool
}

/// The audio output for the voice view: the chip and the lines of the sheet.
struct OutputUiState: Equatable {
    let current: AudioOutput
    let rows: [OutputRow]
}

/// The outputs as the sheet lists them (mockup `04-audio-output`): speaker, earpiece, every
/// Bluetooth device by name, and the wired headset, which is listed as not connected while
/// there is none. Nil while no session has an output.
func outputUiState(_ state: OutputState) -> OutputUiState? {
    guard let current = state.current else { return nil }
    func rows(_ kind: OutputKind) -> [OutputRow] {
        state.available.filter { $0.kind == kind }
            .map { OutputRow(id: $0.id, kind: $0.kind, name: $0.name, selected: $0.id == current.id) }
    }
    var wired = rows(.wiredHeadset)
    if wired.isEmpty { wired = [OutputRow(id: nil, kind: .wiredHeadset, name: "", selected: false)] }
    return OutputUiState(current: current, rows: rows(.speaker) + rows(.earpiece) + rows(.bluetooth) + wired)
}
