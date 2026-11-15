// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The open sheet of the identity screens: details, create, import, export or delete.
struct IdentitySheetHost: View {
    let sheet: IdentitySheet
    let actions: IdentitiesController

    var body: some View {
        switch sheet {
        case .details(let identity):
            DetailsSheet(identity: identity, actions: actions)
        case .create(let form):
            CreateSheet(form: form, actions: actions)
        case .importFile(let form):
            ImportSheet(form: form, actions: actions)
        case .export(let identity, let form):
            ExportSheet(identity: identity, form: form, actions: actions)
        case .delete(let identity, let form, let defaultLabel):
            DeleteSheet(identity: identity, form: form, defaultLabel: defaultLabel, actions: actions)
        }
    }
}

private struct DetailsSheet: View {
    let identity: IdentityDetails
    let actions: IdentitiesController
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: identity.label, onDismiss: actions.closeSheet) {
            Fact(label: Strings.identityDetailsName, value: identity.commonName)
            Fact(label: Strings.identityDetailsFingerprint, value: identity.fingerprint, monospace: true)
            Fact(
                label: Strings.identityDetailsAdded,
                value: Date(timeIntervalSince1970: TimeInterval(identity.createdMs) / 1000)
                    .formatted(date: .abbreviated, time: .omitted)
            )
            Paragraph(
                identity.isDefault
                    ? "\(Strings.identityDetailsDefault) \(identityUsedOn(identity.serverCount))"
                    : identityUsedOn(identity.serverCount)
            )
            if !identity.isDefault {
                MumcordButton(text: Strings.identitySetDefault, style: .secondary, fullWidth: true) {
                    actions.setDefault(identity.id)
                }
                .accessibilityIdentifier("identity-set-default")
            }
            MumcordButton(text: Strings.identityExport, style: .secondary, fullWidth: true) {
                actions.openExport(identity.id)
            }
            .accessibilityIdentifier("identity-export")
            if identity.canDelete {
                MumcordButton(text: Strings.identityDelete, style: .danger, icon: MumcordIcons.trash, fullWidth: true) {
                    actions.openDelete(identity.id)
                }
                .accessibilityIdentifier("identity-delete")
            } else {
                ParagraphText(Strings.identityDeleteLast, style: .small, color: theme.colors.textMuted)
            }
            MumcordButton(text: Strings.actionClose, style: .secondary, fullWidth: true, action: actions.closeSheet)
                .accessibilityIdentifier("identity-details-close")
        }
    }
}

/// A label with its value; the fingerprint in a fixed-width font.
private struct Fact: View {
    let label: String
    let value: String
    var monospace = false
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        VStack(alignment: .leading, spacing: 4) {
            ParagraphText(label, style: .smallStrong, color: colors.textSecondary)
            if monospace {
                Text(verbatim: value)
                    .font(.system(size: TextToken.body.size * scale, design: .monospaced))
                    .foregroundStyle(colors.text)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ParagraphText(value, style: .body, color: colors.text)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

private struct CreateSheet: View {
    let form: CreateForm
    let actions: IdentitiesController

    var body: some View {
        SheetOverlay(title: Strings.identityCreateTitle, onDismiss: actions.closeSheet) {
            Paragraph(Strings.identityCreateText)
            MumcordTextField(
                value: form.name,
                onValueChange: actions.changeCreateName,
                label: Strings.identityCreateName,
                error: form.error.map { $0 == .invalidInput ? Strings.identityCreateNameInvalid : $0.message },
                submitLabel: .done,
                onSubmit: actions.create,
                autoFocus: true
            )
            MumcordButton(text: Strings.identityCreateConfirm, fullWidth: true, action: actions.create)
                .disabled(!form.canCreate)
                .accessibilityIdentifier("identity-create-confirm")
            CancelButton(action: actions.closeSheet)
        }
    }
}

private struct ImportSheet: View {
    let form: ImportForm
    let actions: IdentitiesController

    var body: some View {
        SheetOverlay(title: Strings.identityImportTitle, onDismiss: actions.closeSheet) {
            Paragraph(Strings.identityImportText)
            MumcordTextField(
                value: form.password,
                onValueChange: actions.changeImportPassword,
                label: Strings.identityImportPassword,
                error: form.problem.map(importProblemText),
                password: true,
                onSubmit: actions.confirmImport,
                autoFocus: true
            )
            MumcordButton(text: Strings.identityImportConfirm, fullWidth: true, action: actions.confirmImport)
                .disabled(form.saving)
                .accessibilityIdentifier("identity-import-confirm")
            CancelButton(action: actions.closeSheet)
        }
    }
}

private func importProblemText(_ problem: ImportProblem) -> String {
    switch problem {
    case .wrongPasswordOrFile: Strings.identityImportWrong
    case .duplicate: Strings.errorDuplicateIdentity
    case .failed: Strings.identityImportFailed
    }
}

private struct ExportSheet: View {
    let identity: IdentityDetails
    let form: ExportForm
    let actions: IdentitiesController

    var body: some View {
        SheetOverlay(title: Strings.identityExportTitle(identity.label), onDismiss: actions.closeSheet) {
            Paragraph(Strings.identityExportText)
            MumcordTextField(
                value: form.password,
                onValueChange: actions.changeExportPassword,
                label: Strings.identityExportPassword,
                error: form.failed ? Strings.identityExportFailed : nil,
                password: true,
                submitLabel: .next,
                autoFocus: true
            )
            MumcordTextField(
                value: form.repeated,
                onValueChange: actions.changeExportRepeat,
                label: Strings.identityExportRepeat,
                error: form.mismatch ? Strings.identityExportMismatch : nil,
                password: true,
                onSubmit: actions.export
            )
            MumcordButton(text: Strings.identityExportConfirm, fullWidth: true, action: actions.export)
                .disabled(!form.canExport)
                .accessibilityIdentifier("identity-export-confirm")
            CancelButton(action: actions.closeSheet)
        }
    }
}

private struct DeleteSheet: View {
    let identity: IdentityDetails
    let form: DeleteForm
    let defaultLabel: String
    let actions: IdentitiesController

    var body: some View {
        let nextDefault = identity.nextDefaultLabel
        SheetOverlay(title: Strings.identityDeleteTitle(identity.label), onDismiss: actions.closeSheet) {
            MumcordBanner(
                text: Strings.identityDeleteWarning,
                icon: MumcordIcons.alert,
                tone: .danger,
                horizontalMargin: 0
            )
            if let nextDefault {
                Paragraph(Strings.identityDeleteNextDefault(nextDefault))
            }
            if identity.serverCount > 0 && (nextDefault != nil || !identity.isDefault) {
                Paragraph(Strings.identityDeleteServers(identity.serverCount, nextDefault ?? defaultLabel))
            }
            MumcordButton(text: Strings.identityDeleteBackup, style: .secondary, fullWidth: true) {
                actions.openExport(identity.id)
            }
            .accessibilityIdentifier("identity-delete-backup")
            MumcordTextField(
                value: form.typed,
                onValueChange: actions.changeDeleteConfirmation,
                label: Strings.identityDeleteType,
                error: form.error?.message
            )
            MumcordButton(text: Strings.identityDelete, style: .danger, fullWidth: true, action: actions.delete)
                .disabled(!canDelete(identity, form))
                .accessibilityIdentifier("identity-delete-confirm")
            CancelButton(action: actions.closeSheet)
        }
    }
}

private struct CancelButton: View {
    let action: () -> Void

    var body: some View {
        MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: action)
    }
}
