// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

private let readOnlyOpacity = 0.75

/// Single-line text field of a form (`.label` over `.field`): the `label` above, the field with
/// a focus ring while it is being edited, and below it the `error` in the danger colour or else
/// the `hint`. VoiceOver reads the label with the field and the error as the text after it.
/// `password` hides the typed characters. The text is taken as typed: no autocorrection, and
/// automatic capitals only with a `capitalization`. `minLines` above 1 makes it a text area that
/// grows with its text; `readOnly` shows a value that cannot be changed here, dimmed, with
/// `trailingIcon` saying why.
struct MumcordTextField: View {
    private let value: String
    private let onValueChange: (String) -> Void
    private let label: String
    private let placeholder: String?
    private let hint: String?
    private let error: String?
    private let password: Bool
    private let keyboard: UIKeyboardType
    private let submitLabel: SubmitLabel
    private let onSubmit: (() -> Void)?
    private let autoFocus: Bool
    private let capitalization: TextInputAutocapitalization
    private let minLines: Int
    private let readOnly: Bool
    private let trailingIcon: MumcordIconData?
    @FocusState private var focused: Bool
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    init(
        value: String,
        onValueChange: @escaping (String) -> Void,
        label: String,
        placeholder: String? = nil,
        hint: String? = nil,
        error: String? = nil,
        password: Bool = false,
        keyboard: UIKeyboardType = .default,
        submitLabel: SubmitLabel = .done,
        onSubmit: (() -> Void)? = nil,
        autoFocus: Bool = false,
        capitalization: TextInputAutocapitalization = .never,
        minLines: Int = 1,
        readOnly: Bool = false,
        trailingIcon: MumcordIconData? = nil
    ) {
        self.value = value
        self.onValueChange = onValueChange
        self.label = label
        self.placeholder = placeholder
        self.hint = hint
        self.error = error
        self.password = password
        self.keyboard = keyboard
        self.submitLabel = submitLabel
        self.onSubmit = onSubmit
        self.autoFocus = autoFocus
        self.capitalization = capitalization
        self.minLines = minLines
        self.readOnly = readOnly
        self.trailingIcon = trailingIcon
    }

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        VStack(alignment: .leading, spacing: 6) {
            // Read as part of the field below, not as a separate element.
            LineText(label, style: .smallStrong, color: colors.textSecondary)
                .accessibilityHidden(true)
            field
                .padding(.horizontal, MumcordSpace.s4)
                .padding(.vertical, minLines > 1 ? MumcordSpace.s3 : 0)
                .frame(maxWidth: .infinity, minHeight: MumcordSize.touchVisual, alignment: minLines > 1 ? .topLeading : .leading)
                .background(colors.bgElevated, in: shape)
                .overlay { shape.strokeBorder(borderColor, lineWidth: 1) }
                // The dimmed look of `.field` in the edit profile mockup for a value that cannot
                // be changed.
                .opacity(readOnly ? readOnlyOpacity : 1)
                .overlay {
                    if focused {
                        // The 2 pt ring around the focused field, as the CSS box shadow of
                        // `.field.is-focus`. It is drawn outside the field so that focusing
                        // does not move anything.
                        RoundedRectangle(cornerRadius: MumcordRadius.lg + 2)
                            .strokeBorder(colors.focus.opacity(theme.opacities.focusRing), lineWidth: 2)
                            .padding(-2)
                    }
                }
                // The whole box takes the tap, not only the line of text in it.
                .contentShape(Rectangle())
                .onTapGesture { focused = true }
            if let error {
                ParagraphText(error, style: .small, color: colors.danger)
            } else if let hint {
                ParagraphText(hint, style: .small, color: colors.textMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task {
            if autoFocus { focused = true }
        }
        .onChange(of: error) { _, newError in
            // An error that appears while the user is elsewhere on the screen is spoken.
            if let newError {
                AccessibilityNotification.Announcement(newError).post()
            }
        }
    }

    @ViewBuilder private var field: some View {
        let colors = theme.colors
        let configured = input
            .font(Font(MumcordFonts.font(.body, scale: scale)))
            .foregroundStyle(colors.text)
            .tint(colors.accent)
            .autocorrectionDisabled(true)
            .textInputAutocapitalization(capitalization)
            .keyboardType(keyboard)
            .submitLabel(submitLabel)
            .focused($focused)
            .onSubmit { onSubmit?() }
            .accessibilityLabel(label)
            .disabled(readOnly)
        if let trailingIcon {
            HStack(spacing: MumcordSpace.s2) {
                configured
                MumcordIcon(icon: trailingIcon, label: nil, tint: colors.textSecondary, size: MumcordSize.iconSm)
            }
        } else {
            configured
        }
    }

    @ViewBuilder private var input: some View {
        let text = Binding(get: { value }, set: onValueChange)
        let prompt = Text(verbatim: placeholder ?? "").foregroundColor(theme.colors.textMuted)
        if password {
            SecureField("", text: text, prompt: prompt)
        } else if minLines > 1 {
            TextField("", text: text, prompt: prompt, axis: .vertical)
                .lineLimit(minLines...)
        } else {
            TextField("", text: text, prompt: prompt)
        }
    }

    private var borderColor: Color {
        if error != nil { return theme.colors.danger }
        return focused ? theme.colors.focus : theme.colors.border
    }
}
