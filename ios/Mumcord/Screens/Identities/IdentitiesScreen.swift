// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// More → Identities (mockup `17-identity`): every identity as a card with the start of its
/// fingerprint, then the three ways to add one.
struct IdentitiesScreen: View {
    let state: IdentitiesUiState
    let onBack: () -> Void
    let onDetails: (Int64) -> Void
    let onScan: () -> Void
    let onImport: () -> Void
    let onCreate: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordScreen {
            VStack(spacing: 0) {
                MumcordAppBar(title: Strings.identitiesTitle, onBack: onBack, backLabel: Strings.actionBack)
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ParagraphText(Strings.identitiesIntro, style: .body, color: theme.colors.textSecondary)
                            .padding(.horizontal, MumcordSpace.s4)
                            .padding(.top, MumcordSpace.s2)
                        MumcordSectionHeader(title: Strings.identitiesSectionYours)
                        VStack(spacing: MumcordSpace.s3) {
                            ForEach(state.rows) { row in
                                IdentityCard(row: row) { onDetails(row.id) }
                            }
                        }
                        .padding(.horizontal, MumcordSpace.s4)
                        MumcordSectionHeader(title: Strings.identitiesSectionAdd)
                        AddIdentityOptions(
                            unreadableFile: state.unreadableFile,
                            onScan: onScan,
                            onImport: onImport,
                            onCreate: onCreate
                        )
                        .padding(.horizontal, MumcordSpace.s4)
                    }
                    .padding(.bottom, MumcordSpace.s4)
                }
            }
        }
        // Found as a whole while its buttons keep their own identifiers.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("identities-screen")
    }
}

/// The first start without an identity (FR-5.1): the same three ways, before the talk mode.
struct IdentitySetupScreen: View {
    let unreadableFile: Bool
    let onScan: () -> Void
    let onImport: () -> Void
    let onCreate: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: MumcordSpace.s4) {
                    VStack(alignment: .leading, spacing: MumcordSpace.s2) {
                        // The intro title of the first start, as on the talk mode choice.
                        ParagraphText(
                            Strings.identitySetupTitle,
                            style: TextToken.title.with(size: 30, lineHeight: 1.15),
                            color: theme.colors.text
                        )
                        .accessibilityAddTraits(.isHeader)
                        Paragraph(Strings.identitySetupText)
                    }
                    .padding(.top, MumcordSpace.s4)
                    AddIdentityOptions(
                        unreadableFile: unreadableFile,
                        onScan: onScan,
                        onImport: onImport,
                        onCreate: onCreate
                    )
                    .padding(.top, MumcordSpace.s3)
                }
                .padding(EdgeInsets(top: MumcordSpace.s5, leading: MumcordSpace.s4, bottom: MumcordSpace.s3, trailing: MumcordSpace.s4))
            }
        }
        // Found as a whole while its buttons keep their own identifiers.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("identity-setup")
    }
}

/// "Scan from desktop app" (the easiest, highlighted), "Import a .p12 file" and "Create a new
/// identity".
private struct AddIdentityOptions: View {
    let unreadableFile: Bool
    let onScan: () -> Void
    let onImport: () -> Void
    let onCreate: () -> Void

    var body: some View {
        VStack(spacing: MumcordSpace.s3) {
            AddOption(
                icon: MumcordIcons.qrCode,
                title: Strings.identitiesScan,
                subtitle: Strings.identitiesScanSub,
                tag: Strings.identitiesScanTag,
                action: onScan
            )
            .accessibilityIdentifier("identity-scan")
            AddOption(
                icon: MumcordIcons.paperclip,
                title: Strings.identitiesImport,
                subtitle: Strings.identitiesImportSub,
                action: onImport
            )
            .accessibilityIdentifier("identity-import")
            if unreadableFile {
                MumcordBanner(
                    text: Strings.identitiesFileUnreadable,
                    icon: MumcordIcons.alert,
                    tone: .danger,
                    horizontalMargin: 0
                )
                .accessibilityIdentifier("identity-file-notice")
            }
            AddOption(
                icon: MumcordIcons.plus,
                title: Strings.identitiesCreate,
                subtitle: Strings.identitiesCreateSub,
                action: onCreate
            )
            .accessibilityIdentifier("identity-create")
        }
    }
}

/// The text of a card saying how many servers use the identity.
func identityUsedOn(_ serverCount: Int) -> String {
    serverCount == 0 ? Strings.identitiesUnused : Strings.identitiesUsedOn(serverCount)
}

/// One identity (`.idcard`): avatar, label, use and the start of the fingerprint.
private struct IdentityCard: View {
    let row: IdentityRow
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: 20)
        let title = row.isDefault ? Strings.identitiesDefault(row.label) : row.label
        let used = identityUsedOn(row.serverCount)
        Button(action: action) {
            VStack(alignment: .leading, spacing: MumcordSpace.s3) {
                HStack(spacing: MumcordSpace.s3) {
                    MumcordAvatar(initials: row.label.initials(1), colorSlot: avatarSlot(row.label), size: .large)
                    VStack(alignment: .leading, spacing: 2) {
                        ParagraphText(title, style: .bodyStrong, color: colors.text)
                        ParagraphText(used, style: .small, color: colors.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    MumcordIcon(icon: MumcordIcons.shield, label: nil, tint: colors.success)
                }
                Text(verbatim: Strings.identitiesFingerprint(row.fingerprintStart))
                    .font(.system(size: TextToken.small.size * scale, design: .monospaced))
                    .foregroundStyle(colors.textSecondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, MumcordSpace.s3)
                    .padding(.vertical, 10)
                    .background(colors.bgServers, in: RoundedRectangle(cornerRadius: MumcordRadius.lg))
            }
            .padding(MumcordSpace.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(colors.bgElevated, in: shape)
            .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Strings.identitiesCardDescription(title, used))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("identity-card")
    }
}

/// One way to add an identity (`.opt`); the best one is highlighted (`.opt--best`).
private struct AddOption: View {
    let icon: MumcordIconData
    let title: String
    let subtitle: String
    var tag: String?
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let best = tag != nil
        let shape = RoundedRectangle(cornerRadius: 20)
        Button(action: action) {
            HStack(spacing: MumcordSpace.s4) {
                MumcordIcon(
                    icon: icon,
                    label: nil,
                    tint: best ? colors.onAccent : colors.accentText,
                    size: MumcordSize.iconLg
                )
                .frame(width: 44, height: 44)
                .background(best ? colors.accent : colors.bgHover, in: RoundedRectangle(cornerRadius: MumcordRadius.lg))
                VStack(alignment: .leading, spacing: 4) {
                    ParagraphText(title, style: .talkButton, color: colors.text)
                    if let tag {
                        LineText(tag, style: .smallStrong, color: colors.onAccent)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 2)
                            .background(colors.accent, in: Capsule())
                    }
                    ParagraphText(subtitle, style: .body, color: colors.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: colors.textMuted)
            }
            .padding(MumcordSpace.s4)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .background(best ? colors.bgSelected : colors.bgElevated, in: shape)
            .overlay { shape.strokeBorder(best ? colors.accent : colors.border, lineWidth: best ? 2 : 1) }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}
