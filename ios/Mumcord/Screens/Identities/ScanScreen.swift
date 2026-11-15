// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

/// "Scan from desktop" (mockup `18-qr-scan`): the camera reads the QR codes of the desktop's
/// "Send to your phone" with progress; after the last one the code of the desktop is typed into
/// 4 + 4 boxes. Only a right code shows what arrives, and nothing is imported before "Import".
struct ScanScreen: View {
    let state: ScanState
    let qrSource: QrSource
    let onQrText: (String) -> Void
    let onCameraFailed: () -> Void
    let onClose: () -> Void
    let onAllowCamera: () -> Void
    let onOpenSettings: () -> Void
    let onCode: (String) -> Void
    let onImport: () -> Void

    var body: some View {
        MumcordScreen {
            VStack(spacing: 0) {
                // A screen of its own task, closed rather than gone back from (mockup 18).
                MumcordAppBar(title: Strings.scanTitle, leading: {
                    MumcordIconButton(icon: MumcordIcons.x, label: Strings.actionClose, action: onClose)
                        .accessibilityIdentifier("scan-close")
                }, subtitle: { EmptyView() }, actions: { EmptyView() })
                ScrollView {
                    VStack(alignment: .leading, spacing: MumcordSpace.s4) {
                        content
                    }
                    .padding(EdgeInsets(top: MumcordSpace.s2, leading: MumcordSpace.s4, bottom: MumcordSpace.s4, trailing: MumcordSpace.s4))
                }
            }
        }
        .accessibilityIdentifier("scan-screen")
    }

    @ViewBuilder private var content: some View {
        switch state.step {
        case .scanning(let have, let total):
            Viewfinder(
                state: state,
                qrSource: qrSource,
                onQrText: onQrText,
                onCameraFailed: onCameraFailed,
                onAllowCamera: onAllowCamera,
                onOpenSettings: onOpenSettings
            )
            VStack(spacing: MumcordSpace.s3) {
                ScanProgress(have: have, total: total)
                ScanHint(first: total == 0)
            }
            ScanNoteBanner(note: state.note)
        case .code(let entry):
            AllScanned()
            ScanNoteBanner(note: state.note)
            CodeEntryView(entry: entry, onCode: onCode)
        case .checking(let digits, _):
            AllScanned()
            CheckingView(digits: digits)
        case .preview(let contents):
            PreviewView(contents: contents, onImport: onImport)
        case .done(let summary):
            DoneView(summary: summary, onClose: onClose)
        }
    }
}

/// The camera picture in its card (`.cam`), or what to do while the camera cannot be used.
private struct Viewfinder: View {
    let state: ScanState
    let qrSource: QrSource
    let onQrText: (String) -> Void
    let onCameraFailed: () -> Void
    let onAllowCamera: () -> Void
    let onOpenSettings: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: 20)
        ZStack {
            LinearGradient(colors: [colors.bgServers, colors.bgHover], startPoint: .topLeading, endPoint: .bottomTrailing)
            switch state.camera {
            case nil:
                MumcordIcon(icon: MumcordIcons.qrCode, label: nil, tint: colors.textMuted, size: 72)
                    .opacity(0.5)
                if state.note != .noCamera {
                    qrSource.viewfinder(onText: { onQrText($0) }, onFailure: { onCameraFailed() })
                        .accessibilityElement()
                        .accessibilityLabel(Strings.scanViewfinder)
                }
                ScanFrame(color: colors.accentText)
                    .frame(width: 210, height: 210)
                    .accessibilityHidden(true)
            // The explanation is a sheet over this screen; behind it the card stays calm.
            case .explain:
                EmptyView()
            case .denied, .blocked:
                CameraRefused(
                    blocked: state.camera == .blocked,
                    action: state.camera == .blocked ? onOpenSettings : onAllowCamera
                )
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 300)
        .clipShape(shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
    }
}

/// The four corners and the line of the mockup's scan frame.
private struct ScanFrame: View {
    let color: Color

    var body: some View {
        Canvas { context, size in
            let arm: CGFloat = 38
            let radius: CGFloat = 14
            let stroke: CGFloat = 4
            let inset = stroke / 2
            let width = size.width - inset
            let height = size.height - inset
            var corners = Path()
            let starts: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [
                (inset, inset, 1, 1), (width, inset, -1, 1), (inset, height, 1, -1), (width, height, -1, -1),
            ]
            for (x, y, dx, dy) in starts {
                corners.move(to: CGPoint(x: x, y: y + dy * arm))
                corners.addLine(to: CGPoint(x: x, y: y + dy * radius))
                corners.addQuadCurve(to: CGPoint(x: x + dx * radius, y: y), control: CGPoint(x: x, y: y))
                corners.addLine(to: CGPoint(x: x + dx * arm, y: y))
            }
            context.stroke(corners, with: .color(color), lineWidth: stroke)
            let line: CGFloat = 2
            let bar = CGRect(x: 12, y: size.height / 2 - line / 2, width: size.width - 24, height: line)
            context.fill(Path(roundedRect: bar, cornerRadius: line / 2), with: .color(color))
        }
    }
}

private struct CameraRefused: View {
    let blocked: Bool
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        VStack(spacing: MumcordSpace.s3) {
            MumcordIcon(icon: MumcordIcons.alert, label: nil, tint: colors.warning, size: MumcordSize.iconLg)
            ParagraphText(blocked ? Strings.scanCameraBlocked : Strings.scanCameraDenied, style: .body, color: colors.text)
                .multilineTextAlignment(.center)
            MumcordButton(text: blocked ? Strings.cameraOpenSettings : Strings.cameraAllow, action: action)
                .accessibilityIdentifier("scan-camera-action")
        }
        .padding(MumcordSpace.s4)
    }
}

/// "Code 3 of 7" with its share and one segment per code (`.prog`).
private struct ScanProgress: View {
    let have: Int
    let total: Int
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let text = total == 0 ? Strings.scanProgressNone : Strings.scanProgress(have, total)
        VStack(spacing: MumcordSpace.s2) {
            HStack {
                ParagraphText(text, style: .bodyStrong, color: colors.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if total > 0 {
                    LineText(Strings.scanPercent(scanPercent(have: have, total: total)), style: .small, color: colors.textMuted)
                }
            }
            if total > 0 {
                // Many codes share the width; the gap shrinks before the segments vanish.
                HStack(spacing: total > 24 ? 2 : 6) {
                    ForEach(0..<total, id: \.self) { index in
                        Capsule()
                            .fill(index < have ? colors.accent : colors.bgHover)
                            .frame(height: 8)
                    }
                }
            }
        }
        // One announcement per new code, not one per element.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
        .accessibilityIdentifier("scan-progress")
    }
}

private struct ScanHint: View {
    let first: Bool
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        ParagraphText(first ? Strings.scanHintWhere : Strings.scanHint, style: .small, color: theme.colors.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }
}

private struct ScanNoteBanner: View {
    let note: ScanNote?

    var body: some View {
        if let note {
            MumcordBanner(
                text: text(note),
                icon: MumcordIcons.alert,
                tone: note == .notATransfer || note == .unreadable ? .warning : .danger,
                horizontalMargin: 0
            )
            .accessibilityIdentifier("scan-note")
        }
    }

    private func text(_ note: ScanNote) -> String {
        switch note {
        case .notATransfer: Strings.scanNoteNotATransfer
        case .unreadable: Strings.scanNoteUnreadable
        case .newerVersion: Strings.scanNoteNewerVersion
        case .expired: Strings.scanNoteExpired
        case .clockSkew: Strings.scanNoteClockSkew
        case .rescanRequired: Strings.scanNoteRescan
        case .damaged: Strings.scanNoteDamaged
        case .outOfMemory: Strings.scanNoteOutOfMemory
        case .failed: Strings.scanNoteFailed
        case .noCamera: Strings.scanNoteNoCamera
        }
    }
}

/// Stands where the camera was once every code is in: the camera is off from here on.
private struct AllScanned: View {
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        HStack(spacing: 10) {
            MumcordIcon(icon: MumcordIcons.check, label: nil, tint: colors.success)
            ParagraphText(Strings.scanAllScanned, style: .bodyStrong, color: colors.text)
        }
        .padding(.horizontal, MumcordSpace.s4)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.bgElevated, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
    }
}

/// The code of the desktop, typed into one box per digit in two groups (`.code`).
private struct CodeEntryView: View {
    let entry: CodeEntry
    let onCode: (String) -> Void
    @FocusState private var focused: Bool
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        VStack(alignment: .leading, spacing: 6) {
            ParagraphText(Strings.scanCodeLabel, style: .smallStrong, color: colors.textSecondary)
                .accessibilityHidden(true)
            ZStack {
                CodeBoxes(typed: entry.typed, digits: entry.digits, active: true)
                    .accessibilityHidden(true)
                // The digits are drawn by the boxes; the field itself only takes the typing.
                TextField("", text: Binding(get: { entry.typed }, set: onCode))
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .autocorrectionDisabled()
                    .foregroundStyle(Color.clear)
                    .tint(Color.clear)
                    .focused($focused)
                    .accessibilityLabel(Strings.scanCodeDescription(entry.digits))
                    .accessibilityIdentifier("scan-code")
            }
            if entry.wrong {
                ParagraphText(Strings.scanWrongCode(entry.attemptsLeft), style: .small, color: colors.danger)
                    .accessibilityIdentifier("scan-code-status")
            }
        }
        .onAppear { focused = true }
        .onChange(of: entry.wrong) { _, wrong in
            if wrong { UIAccessibility.post(notification: .announcement, argument: Strings.scanWrongCode(entry.attemptsLeft)) }
        }
    }
}

private struct CheckingView: View {
    let digits: Int
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // The typed code is gone from the screen while it is checked.
            CodeBoxes(typed: "", digits: digits, active: false)
                .accessibilityHidden(true)
            ParagraphText(Strings.scanChecking, style: .small, color: theme.colors.textSecondary)
                .accessibilityIdentifier("scan-code-status")
        }
        .onAppear { UIAccessibility.post(notification: .announcement, argument: Strings.scanChecking) }
    }
}

private struct CodeBoxes: View {
    let typed: String
    let digits: Int
    let active: Bool
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        let characters = Array(typed)
        HStack(spacing: 6) {
            ForEach(0..<digits, id: \.self) { index in
                let current = active && index == characters.count
                Text(verbatim: index < characters.count ? String(characters[index]) : "")
                    .font(.system(size: TextToken.bodyStrong.size * scale, weight: .semibold, design: .monospaced))
                    .foregroundStyle(colors.text)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(colors.bgElevated, in: shape)
                    .overlay { shape.strokeBorder(current ? colors.focus : colors.border, lineWidth: current ? 2 : 1) }
                    // A wider gap between the two groups (4 + 4).
                    .padding(.leading, index == digits / 2 ? MumcordSpace.s4 - 6 : 0)
            }
        }
    }
}

/// What the transfer holds, before anything is imported (`.sum` and the lists below it).
private struct PreviewView: View {
    let contents: TransferContents
    let onImport: () -> Void

    var body: some View {
        Summary(lead: Strings.scanReceiving, what: transferCounts(contents.identities.count, contents.servers?.count))
        Names(title: Strings.scanSectionIdentities, names: contents.identities)
        if let servers = contents.servers {
            Names(title: Strings.scanSectionServers, names: servers)
            Paragraph(Strings.scanNoPasswords)
        }
        if let error = contents.error {
            MumcordBanner(text: error.message, icon: MumcordIcons.alert, tone: .danger, horizontalMargin: 0)
                .accessibilityIdentifier("scan-note")
        }
        MumcordButton(text: contents.importing ? Strings.scanImporting : Strings.scanImport, fullWidth: true, action: onImport)
            .disabled(contents.importing)
            .accessibilityIdentifier("scan-import")
    }
}

private struct DoneView: View {
    let summary: ImportSummary
    let onClose: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        ParagraphText(Strings.scanDoneTitle, style: .title, color: theme.colors.text)
            .accessibilityAddTraits(.isHeader)
        if summary.identitiesImported == 0 && summary.serversImported == 0 {
            Paragraph(Strings.scanDoneNothing)
        } else {
            Summary(
                lead: Strings.scanDoneAdded,
                what: transferCounts(
                    summary.identitiesImported,
                    summary.serversImported > 0 ? summary.serversImported : nil,
                    zeroIdentities: false
                )
            )
        }
        if !summary.skipped.isEmpty {
            Names(
                title: Strings.scanSectionSkipped,
                names: summary.skipped.map {
                    "\($0.name) · \($0.alreadyThere ? Strings.scanSkippedDuplicate : Strings.scanSkippedInvalid)"
                }
            )
        }
        MumcordButton(text: Strings.scanDone, fullWidth: true, action: onClose)
            .accessibilityIdentifier("scan-done")
    }
}

/// "1 identity, 4 servers"; a part with nothing in it is left out.
func transferCounts(_ identities: Int, _ servers: Int?, zeroIdentities: Bool = true) -> String {
    let identityText = Strings.scanReceivingIdentities(identities)
    guard let servers else { return identityText }
    let serverText = Strings.scanReceivingServers(servers)
    if identities == 0 && !zeroIdentities { return serverText }
    return Strings.scanReceivingBoth(identityText, serverText)
}

private struct Summary: View {
    let lead: String
    let what: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        var text = AttributedString("\(lead) ")
        var bold = AttributedString(what)
        bold.inlinePresentationIntent = .stronglyEmphasized
        text.append(bold)
        return HStack(spacing: 10) {
            MumcordIcon(icon: MumcordIcons.key, label: nil, tint: colors.accentText)
            ParagraphText(text, style: .body, color: colors.text)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, MumcordSpace.s4)
        .padding(.vertical, 12)
        .background(colors.bgElevated, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("scan-summary")
    }
}

/// Names from the desktop are drawn as plain text, one per line.
private struct Names: View {
    let title: String
    let names: [String]
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        VStack(alignment: .leading, spacing: MumcordSpace.s2) {
            LineText(title.uppercased(), style: .section, color: colors.textMuted)
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: MumcordSpace.s2) {
                ForEach(Array(names.enumerated()), id: \.offset) { _, name in
                    ParagraphText(name, style: .body, color: colors.text)
                }
            }
            .padding(.horizontal, MumcordSpace.s4)
            .padding(.vertical, MumcordSpace.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(colors.bgElevated, in: RoundedRectangle(cornerRadius: MumcordRadius.lg))
        }
    }
}
