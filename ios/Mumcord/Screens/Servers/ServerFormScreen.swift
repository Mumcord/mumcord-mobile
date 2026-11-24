// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// Adds a server to the list or changes one: address, port, name, user name, server password,
/// and for a saved server the theme it is shown in (no mockup; as in the desktop's edit-server
/// dialog, Spec 02 FR-2.17).
struct ServerFormScreen: View {
    let form: ServerFormState
    let onChange: ((inout ServerFormState) -> Void) -> Void
    let onSave: () -> Void
    let onBack: () -> Void
    /// Every theme, built in and the user's own; empty until they were read.
    var themes: [Theme] = []
    @Environment(\.mumcordTheme) private var theme
    /// The field the keyboard writes into, so that its "Next" key can move on.
    @FocusState private var focus: FormField?

    var body: some View {
        MumcordScreen {
            MumcordAppBar(
                title: form.isEdit ? Strings.formEditTitle : Strings.formAddTitle,
                onBack: onBack,
                backLabel: Strings.actionBack
            )
            // Scrolling keeps the field being typed in and the Save button above the keyboard.
            ScrollView {
                VStack(alignment: .leading, spacing: MumcordSpace.s4) {
                    fields
                    if form.hasSavedPassword && form.password.isEmpty {
                        MumcordButton(
                            text: form.removeSavedPassword ? Strings.formPasswordKeep : Strings.formPasswordRemove,
                            style: .ghost,
                            action: { onChange { $0.removeSavedPassword.toggle() } }
                        )
                    }
                    if form.isEdit && !themes.isEmpty {
                        themeRow
                    }
                    if let saveError = form.saveError {
                        MumcordBanner(
                            text: saveError.message,
                            title: Strings.formSaveFailed,
                            icon: MumcordIcons.alert,
                            tone: .danger,
                            horizontalMargin: 0
                        )
                    }
                    MumcordButton(text: Strings.formSave, fullWidth: true, action: onSave)
                        .disabled(form.saving)
                        .accessibilityIdentifier("server-form-save")
                }
                .padding(EdgeInsets(top: MumcordSpace.s2, leading: MumcordSpace.s4, bottom: MumcordSpace.s4, trailing: MumcordSpace.s4))
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }

    @ViewBuilder private var fields: some View {
        MumcordTextField(
            value: form.address,
            onValueChange: { text in onChange { $0.address = text } },
            label: Strings.formAddressLabel,
            placeholder: Strings.formAddressPlaceholder,
            error: form.showErrors && form.addressMissing ? Strings.formAddressMissing : nil,
            keyboard: .URL,
            submitLabel: .next,
            onSubmit: { focus = .port }
        )
        .focused($focus, equals: .address)
        MumcordTextField(
            value: form.port,
            onValueChange: { text in onChange { $0.port = text } },
            label: Strings.formPortLabel,
            error: form.showErrors && form.portNumber == nil ? Strings.formPortInvalid : nil,
            keyboard: .numberPad,
            submitLabel: .next,
            onSubmit: { focus = .name }
        )
        .focused($focus, equals: .port)
        MumcordTextField(
            value: form.name,
            onValueChange: { text in onChange { $0.name = text } },
            label: Strings.formNameLabel,
            hint: Strings.formNameHint,
            submitLabel: .next,
            onSubmit: { focus = .username }
        )
        .focused($focus, equals: .name)
        MumcordTextField(
            value: form.username,
            onValueChange: { text in onChange { $0.username = text } },
            label: Strings.formUsernameLabel,
            hint: Strings.formUsernameHint,
            error: form.showErrors && form.usernameMissing ? Strings.formUsernameMissing : nil,
            submitLabel: .next,
            onSubmit: { focus = .password }
        )
        .focused($focus, equals: .username)
        MumcordTextField(
            value: form.password,
            onValueChange: { text in onChange { $0.password = text } },
            label: Strings.formPasswordLabel,
            hint: passwordHint,
            password: true,
            submitLabel: .done,
            onSubmit: onSave
        )
        .focused($focus, equals: .password)
    }

    private var themeRow: some View {
        let name = form.themeId.flatMap { id in themes.first { $0.id == id }?.name } ?? Strings.serverThemeUseApp
        return VStack(alignment: .leading, spacing: 6) {
            MumcordList(horizontalMargin: 0) {
                MumcordListRow(
                    title: Strings.serverTheme,
                    icon: MumcordIcons.moon,
                    action: { onChange { $0.themeSheet = true } },
                    trailing: {
                        MumcordListRowValue(text: name)
                        MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: theme.colors.textMuted)
                    }
                )
                .accessibilityIdentifier("server-form-theme")
            }
            ParagraphText(Strings.serverThemeHint, style: .small, color: theme.colors.textMuted)
        }
    }

    private var passwordHint: String {
        if form.removeSavedPassword { return Strings.formPasswordWillBeRemoved }
        return form.hasSavedPassword ? Strings.formPasswordSaved : Strings.formPasswordHint
    }
}

/// The fields of the server form in the order the keyboard's "Next" key walks through them.
private enum FormField: Hashable {
    case address
    case port
    case name
    case username
    case password
}

/// The accessibility identifier of a choice in the server theme sheet; "app" for "Use app theme".
func serverThemeOptionIdentifier(_ themeId: String?) -> String {
    "server-theme-option-" + (themeId ?? "app")
}

/// The theme a server is shown in: "Use app theme" or one of all themes. Taken over on Save.
struct ServerThemeSheet: View {
    let form: ServerFormState
    let themes: [Theme]
    let onChange: ((inout ServerFormState) -> Void) -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let options: [(id: String?, name: String)] = [(nil, Strings.serverThemeUseApp)] + themes.map { ($0.id, $0.name) }
        SheetOverlay(
            title: Strings.serverThemeTitle(form.name.isBlank ? form.address : form.name),
            onDismiss: { onChange { $0.themeSheet = false } }
        ) {
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                    let selected = form.themeId == option.id
                    MumcordListRow(
                        title: option.name,
                        divider: index > 0,
                        selected: selected,
                        action: {
                            onChange {
                                $0.themeId = option.id
                                $0.themeSheet = false
                            }
                        },
                        trailing: {
                            if selected {
                                MumcordIcon(icon: MumcordIcons.check, label: nil, tint: theme.colors.accentText)
                            }
                        }
                    )
                    .accessibilityIdentifier(serverThemeOptionIdentifier(option.id))
                }
            }
        }
    }
}
