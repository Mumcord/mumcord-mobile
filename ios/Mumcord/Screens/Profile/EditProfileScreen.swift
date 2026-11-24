// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The text area of the mockup is three lines high before it grows.
private let commentLines = 3

/// Edit profile (mockup `20-edit-profile`, FR-5.80): the avatar with "Change photo", the name of
/// the connection (set per server, not changed here), the comment as plain text with the
/// server's limit, and how others see the result. "Save" sends what changed; going back drops it.
struct EditProfileScreen: View {
    let state: ProfileEditUiState
    let actions: ProfileController
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let edit = state.edit
        let colors = theme.colors
        let picture = state.avatar.flatMap(avatarPicture)
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.profileTitle, onBack: actions.close, backLabel: Strings.actionBack) {
                MumcordButton(text: Strings.profileSave, small: true, action: actions.save)
                    .disabled(!edit.canSave)
                    .accessibilityIdentifier("profile-save")
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MumcordSpace.s4) {
                    avatar(picture)
                    if let error = edit.error {
                        ParagraphText(errorText(error), style: .small, color: colors.danger)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .padding(.horizontal, MumcordSpace.s4)
                            .accessibilityIdentifier("profile-error")
                    }
                    VStack(alignment: .leading, spacing: MumcordSpace.s4) {
                        MumcordTextField(
                            value: state.name,
                            onValueChange: { _ in },
                            label: Strings.profileName,
                            hint: Strings.profileNameHint,
                            readOnly: true,
                            trailingIcon: MumcordIcons.lock
                        )
                        commentField(edit)
                    }
                    .padding(.horizontal, MumcordSpace.s4)
                    .padding(.top, MumcordSpace.s1)
                    VStack(alignment: .leading, spacing: 0) {
                        MumcordSectionHeader(title: Strings.profilePreview)
                        ProfilePreview(name: state.name, comment: edit.comment, picture: picture)
                    }
                }
                .padding(.top, MumcordSpace.s3)
                .padding(.bottom, MumcordSpace.s4)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .onChange(of: edit.error) { _, error in
            // An error that appears below the avatar is spoken.
            if let error { AccessibilityNotification.Announcement(errorText(error)).post() }
        }
    }

    /// The avatar with the camera badge on its edge, ringed in the screen colour (`.av .iconbtn`).
    private func avatar(_ picture: UIImage?) -> some View {
        MumcordAvatar(
            initials: state.name.initials(1),
            colorSlot: avatarSlot(state.name),
            size: .profile,
            image: picture
        )
        .overlay(alignment: .bottomTrailing) {
            MumcordIconButton(
                icon: MumcordIcons.camera,
                label: Strings.profileChangePhoto,
                style: .on,
                action: actions.openAvatarSheet
            )
            .background(Circle().fill(theme.colors.bgChat).padding(-3))
            .offset(x: 10, y: 6)
            .accessibilityIdentifier("profile-photo")
        }
        .frame(maxWidth: .infinity)
    }

    private func commentField(_ edit: ProfileEdit) -> some View {
        VStack(alignment: .trailing, spacing: 6) {
            MumcordTextField(
                value: edit.comment,
                onValueChange: actions.changeComment,
                label: Strings.profileComment,
                error: edit.tooLong ? Strings.profileTooLong : nil,
                capitalization: .sentences,
                minLines: commentLines
            )
            .accessibilityIdentifier("profile-comment")
            if let limit = edit.limit {
                LineText(
                    Strings.profileCommentCount(edit.length, Int(limit)),
                    style: .caption,
                    color: edit.tooLong ? theme.colors.danger : theme.colors.textMuted,
                    tabularNumbers: true
                )
                .accessibilityLabel(Strings.profileCommentCountDescription(edit.length, Int(limit)))
            }
        }
    }

    private func errorText(_ error: ProfileError) -> String {
        switch error {
        case .avatarUnreadable: Strings.profileAvatarUnreadable
        case .saveFailed: Strings.profileSaveFailed
        }
    }
}

/// The profile as the person list shows it to others (`.prev`).
private struct ProfilePreview: View {
    let name: String
    let comment: String
    let picture: UIImage?
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.list)
        HStack(spacing: MumcordSpace.s3) {
            MumcordAvatar(initials: name.initials(1), colorSlot: avatarSlot(name), size: .large, image: picture)
            VStack(alignment: .leading, spacing: 2) {
                (Text(verbatim: name).font(Font(MumcordFonts.font(.bodyStrong, scale: scale))).foregroundColor(colors.text)
                    + Text(verbatim: " ")
                    + Text(verbatim: Strings.profileYou).font(Font(MumcordFonts.font(.small, scale: scale))).foregroundColor(colors.textMuted))
                    .lineLimit(1)
                // Plain characters, line breaks kept: what the server shows others is this text.
                let shown = comment.trimmingCharacters(in: .whitespacesAndNewlines)
                if !shown.isEmpty {
                    ParagraphText(shown, style: .small, color: colors.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(MumcordSpace.s4)
        .background(colors.bgElevated, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .padding(.horizontal, MumcordSpace.s4)
        .accessibilityElement(children: .combine)
    }
}
