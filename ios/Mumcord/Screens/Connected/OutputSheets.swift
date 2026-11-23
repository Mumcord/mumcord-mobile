// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

private extension OutputKind {
    var icon: MumcordIconData {
        switch self {
        case .speaker: MumcordIcons.speaker
        case .earpiece: MumcordIcons.volume
        case .bluetooth: MumcordIcons.bluetooth
        case .wiredHeadset: MumcordIcons.headphones
        }
    }
}

/// The words for an output: the app's own, or the name of a Bluetooth device.
private func outputName(_ kind: OutputKind, _ name: String) -> String {
    switch kind {
    case .speaker: Strings.outputSpeaker
    case .earpiece: Strings.outputEarpiece
    case .wiredHeadset: Strings.outputWired
    case .bluetooth: name.isEmpty ? Strings.outputBluetooth : name
    }
}

/// The chip in the voice view's header (mockup `03-voice-expanded`); it opens the sheet.
struct OutputChip: View {
    let current: AudioOutput
    let action: () -> Void

    var body: some View {
        let name = outputName(current.kind, current.name)
        MumcordOutputChip(text: name, icon: current.kind.icon, label: Strings.outputChip(name), action: action)
            .accessibilityIdentifier("output-chip")
    }
}

/// The choice of the audio output (mockup `04-audio-output`). Picking one switches at once.
struct AudioOutputSheet: View {
    let rows: [OutputRow]
    let onSelect: (String) -> Void
    let onDismiss: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: Strings.outputTitle, onDismiss: onDismiss) {
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                ForEach(rows.indices, id: \.self) { index in
                    let row = rows[index]
                    MumcordOptionRow(
                        title: outputName(row.kind, row.name),
                        icon: row.kind.icon,
                        selected: row.selected,
                        subtitle: subtitle(row),
                        unavailable: row.id == nil,
                        divider: index > 0,
                        action: { if let id = row.id { onSelect(id) } }
                    )
                    .accessibilityIdentifier("output-row-\(row.kind.rawValue)")
                }
            }
            MumcordNote(text: Strings.outputNote)
        }
    }

    private func subtitle(_ row: OutputRow) -> String? {
        if row.id == nil { return Strings.outputNotConnected }
        return row.kind == .bluetooth ? Strings.outputBluetooth : nil
    }
}

/// Asked with the first connection without a headset (FR-5.34): loudspeaker or earpiece. The
/// answer is remembered. Putting the sheet away keeps what the phone chose and asks again with
/// the next session.
struct OutputQuestionSheet: View {
    let onAnswer: (DefaultOutput) -> Void
    let onDismiss: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: Strings.outputQuestionTitle, onDismiss: onDismiss) {
            Paragraph(Strings.outputQuestionText)
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                MumcordOptionRow(
                    title: Strings.outputQuestionSpeaker,
                    icon: MumcordIcons.speaker,
                    selected: false,
                    action: { onAnswer(.speaker) }
                )
                .accessibilityIdentifier("output-question-speaker")
                MumcordOptionRow(
                    title: Strings.outputQuestionEarpiece,
                    icon: MumcordIcons.volume,
                    selected: false,
                    divider: true,
                    action: { onAnswer(.earpiece) }
                )
                .accessibilityIdentifier("output-question-earpiece")
            }
            MumcordNote(text: Strings.outputNote)
        }
    }
}
