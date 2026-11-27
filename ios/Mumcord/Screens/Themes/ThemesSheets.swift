// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

private let previewSampleHeight: CGFloat = 48

/// The open sheet of the themes screen. None of them has a mockup.
struct ThemesSheetHost: View {
    let state: ThemesUiState
    let actions: ThemesController
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        switch state.sheet {
        case .paste(let text, let read, let applying):
            PasteSheet(text: text, read: read, applying: applying, actions: actions)
        case .actions(let themeId):
            if let item = state.find(themeId) {
                SheetOverlay(title: item.name, onDismiss: actions.closeSheet) {
                    MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                        MumcordListRow(title: Strings.themesShare, icon: MumcordIcons.send, action: { actions.share(item.id) })
                            .accessibilityIdentifier("themes-share")
                        if !item.builtIn {
                            MumcordListRow(
                                title: Strings.themesDelete,
                                icon: MumcordIcons.trash,
                                divider: true,
                                danger: true,
                                action: { actions.requestDelete(item.id) }
                            )
                            .accessibilityIdentifier("themes-delete")
                        }
                    }
                }
            }
        case .confirmDelete(let themeId):
            if let item = state.find(themeId) {
                SheetOverlay(title: Strings.themesDeleteTitle(item.name), onDismiss: actions.closeSheet) {
                    Paragraph(Strings.themesDeleteMessage)
                    MumcordButton(
                        text: Strings.themesDeleteConfirm,
                        style: .danger,
                        icon: MumcordIcons.trash,
                        fullWidth: true,
                        action: { actions.confirmDelete(item.id) }
                    )
                    .accessibilityIdentifier("themes-delete-confirm")
                    MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: actions.closeSheet)
                }
            }
        case nil:
            EmptyView()
        }
    }
}

/// "Paste theme code": the pasted text is read by the engine as it changes; a theme found in it
/// is previewed in both versions before "Apply theme" imports it and makes it the app theme. The
/// text is untrusted: only the engine reads it, and only its name and checked colours are shown.
private struct PasteSheet: View {
    let text: String
    let read: ThemeCodeRead?
    let applying: Bool
    let actions: ThemesController

    var body: some View {
        SheetOverlay(title: Strings.themesImportTitle, onDismiss: actions.closeSheet) {
            MumcordTextField(
                value: text,
                onValueChange: actions.changePasteText,
                label: Strings.themesImportLabel,
                hint: Strings.themesImportHint,
                error: error,
                minLines: 2
            )
            .accessibilityIdentifier("themes-code-field")
            if case .found(let preview) = read {
                CodePreview(preview: preview)
                MumcordButton(text: Strings.themesApply, fullWidth: true, action: actions.applyPasted)
                    .disabled(applying)
                    .accessibilityIdentifier("themes-apply")
            }
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: actions.closeSheet)
        }
    }

    private var error: String? {
        switch read {
        case .invalid: Strings.themesImportInvalid
        case .unreadable: Strings.themesImportUnreadable
        case .found, nil: nil
        }
    }
}

/// The theme found in the pasted text: its name, both versions, and notes as on the desktop.
private struct CodePreview: View {
    let preview: ThemeCodePreview
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.list)
        VStack(alignment: .leading, spacing: MumcordSpace.s2) {
            HStack(spacing: MumcordSpace.s2) {
                // The name comes from the pasted code: plain characters only.
                LineText(preview.name, style: .bodyStrong, color: colors.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if preview.importedId != nil {
                    LineText(Strings.themesImported, style: .small, color: colors.success)
                }
            }
            HStack(spacing: MumcordSpace.s2) {
                version(preview.dark, label: Strings.themesModeDark)
                version(preview.light, label: Strings.themesModeLight)
            }
            if preview.madeWithNewer {
                ParagraphText(Strings.themesMadeWithNewer(preview.createdWith), style: .small, color: colors.textMuted)
            }
        }
        .padding(MumcordSpace.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.bgSidebar, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("themes-preview")
        .onAppear {
            // The preview appears while the user is in the field above it.
            AccessibilityNotification.Announcement(preview.name).post()
        }
    }

    private func version(_ sample: ThemeColors, label: String) -> some View {
        VStack(spacing: MumcordSpace.s1) {
            ThemeSample(sample: sample).frame(height: previewSampleHeight)
            LineText(label, style: .caption, color: theme.colors.textMuted)
        }
        .frame(maxWidth: .infinity)
    }
}
