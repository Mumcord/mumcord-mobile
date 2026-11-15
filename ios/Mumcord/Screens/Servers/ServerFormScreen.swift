// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Adds a server to the list or changes one: address, port, name, user name, server password.
struct ServerFormScreen: View {
    let form: ServerFormState
    let onChange: ((inout ServerFormState) -> Void) -> Void
    let onSave: () -> Void
    let onBack: () -> Void
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
