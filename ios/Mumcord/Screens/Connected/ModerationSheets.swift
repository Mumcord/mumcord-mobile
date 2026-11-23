// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// The moderation section at the end of the person sheet (mockup `06-person-sheet`): only the
/// entries the server allows, and nothing at all without any of them.
struct ModerationSection: View {
    let rows: ModerationRows
    let actions: ModerationActions
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        if rows.any {
            MumcordSectionHeader(title: Strings.moderationTitle, padding: MumcordSectionHeader.sheetPadding)
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                if rows.move {
                    MumcordListRow(title: Strings.moderationMove, icon: MumcordIcons.hash, action: actions.openMove)
                        .accessibilityIdentifier("moderation-move")
                }
                if rows.serverMute {
                    MumcordListRow(
                        title: rows.serverMuted ? Strings.moderationServerUnmute : Strings.moderationServerMute,
                        icon: MumcordIcons.micOff,
                        divider: rows.move,
                        action: actions.toggleServerMute
                    )
                    .accessibilityIdentifier("moderation-mute")
                }
                if rows.kick {
                    MumcordListRow(
                        title: Strings.moderationKick,
                        icon: MumcordIcons.phoneOff,
                        divider: rows.move || rows.serverMute,
                        danger: true,
                        action: actions.openKick
                    )
                    .accessibilityIdentifier("moderation-kick")
                }
                if rows.ban {
                    MumcordListRow(
                        title: Strings.moderationBan,
                        icon: MumcordIcons.shield,
                        divider: rows.move || rows.serverMute || rows.kick,
                        danger: true,
                        action: actions.openBan
                    )
                    .accessibilityIdentifier("moderation-ban")
                }
            }
        }
    }
}

/// Draws the moderation step that replaced the person sheet. Going back returns to it.
struct ModerationSheetHost: View {
    let sheet: ModerationSheet
    let actions: ModerationActions
    let onBack: () -> Void

    var body: some View {
        switch sheet {
        case .move(let name, let choices):
            MoveSheet(name: name, choices: choices, actions: actions, onBack: onBack)
        case .kick(let kick):
            KickSheet(sheet: kick, actions: actions, onBack: onBack)
        case .ban(let ban):
            BanSheet(sheet: ban, actions: actions, onBack: onBack)
        }
    }
}

/// The channel picker: every channel but the person's own, indented like the channel list.
private struct MoveSheet: View {
    let name: String
    let choices: [MoveChoice]
    let actions: ModerationActions
    let onBack: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: Strings.moveTitle, onDismiss: onBack) {
            Paragraph(Strings.moveText(name))
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                ForEach(Array(choices.enumerated()), id: \.element.id) { index, choice in
                    MumcordListRow(
                        title: choice.name,
                        icon: MumcordIcons.hash,
                        divider: index > 0,
                        action: { actions.move(choice.id) }
                    )
                    .padding(.leading, MumcordSpace.s4 * CGFloat(choice.depth))
                    .accessibilityIdentifier("move-choice")
                }
            }
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: onBack)
        }
    }
}

/// "Kick Jonas?" with the optional reason, as the desktop's kick dialog (FR-10.57).
private struct KickSheet: View {
    let sheet: KickSheetState
    let actions: ModerationActions
    let onBack: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.kickTitle(sheet.name), onDismiss: onBack) {
            PersonLine(name: sheet.name, channelName: sheet.channelName)
            Paragraph(Strings.kickReconnect)
            ReasonField(reason: sheet.reason, onChange: actions.changeKickReason, hint: Strings.kickReasonHint(sheet.name))
            if let failure = sheet.failure {
                FailureBanner(failure: failure, name: sheet.name)
            }
            MumcordButton(text: Strings.kickConfirm, style: .danger, fullWidth: true, action: actions.confirmKick)
                .disabled(sheet.busy)
                .accessibilityIdentifier("kick-confirm")
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: onBack)
        }
    }
}

/// The ban with the fields of the desktop's ban dialog (FR-10.12 … FR-10.14) that a standard
/// server honours: reason, length, the certificate, "Remove from server now". The address is
/// always part of the ban, so it is said instead of offered as a switch.
private struct BanSheet: View {
    let sheet: BanSheetState
    let actions: ModerationActions
    let onBack: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.banTitle(sheet.name), onDismiss: onBack) {
            PersonLine(name: sheet.name, channelName: sheet.channelName)
            if sheet.loading {
                Paragraph(Strings.banLoading)
                    .onAppear { AccessibilityNotification.Announcement(Strings.banLoading).post() }
            } else if let target = sheet.target {
                BanFormFields(sheet: sheet, target: target, actions: actions)
            }
            if let failure = sheet.failure {
                FailureBanner(failure: failure, name: sheet.name)
            }
            if sheet.conflict {
                MumcordBanner(
                    text: Strings.banConflictText,
                    title: Strings.banConflictTitle,
                    icon: MumcordIcons.alert,
                    tone: .warning,
                    horizontalMargin: 0
                )
                MumcordButton(text: Strings.banSaveAnyway, style: .danger, fullWidth: true, action: actions.banAnyway)
                    .disabled(sheet.busy)
                    .accessibilityIdentifier("ban-anyway")
            } else if let target = sheet.target {
                MumcordButton(
                    text: banButtonText(target),
                    style: .danger,
                    fullWidth: true,
                    action: actions.confirmBan
                )
                .disabled(sheet.busy)
                .accessibilityIdentifier("ban-confirm")
                if target.canKick {
                    MumcordButton(
                        text: Strings.banKickInstead,
                        style: .secondary,
                        fullWidth: true,
                        action: actions.kickInstead
                    )
                    .disabled(sheet.busy)
                    .accessibilityIdentifier("ban-kick-instead")
                }
            }
            MumcordButton(
                text: sheet.conflict ? Strings.actionBack : Strings.actionCancel,
                style: .secondary,
                fullWidth: true,
                action: onBack
            )
        }
    }

    private func banButtonText(_ target: BanTarget) -> String {
        if target.quick { return Strings.banConfirm }
        if sheet.form.durationSecs == 0 { return Strings.banConfirmPermanent }
        return Strings.banConfirmFor(banDurationText(sheet.form.durationSecs))
    }
}

private struct BanFormFields: View {
    let sheet: BanSheetState
    let target: BanTarget
    let actions: ModerationActions

    /// Two rows: all five chips fit a phone's width only in small type.
    private static let durationsPerRow = 3

    var body: some View {
        ReasonField(reason: sheet.form.reason, onChange: actions.changeBanReason, hint: Strings.banReasonHint(sheet.name))
        if target.quick {
            Paragraph(Strings.banQuick)
            Paragraph(Strings.banQuickTargets)
        } else {
            durations
            if let endsAt = sheet.endsAt {
                // The end of the ban in the phone's language and time zone, such as
                // "November 15, 2026".
                Paragraph(Strings.banEndsOn(endsAt.formatted(date: .long, time: .omitted)))
                    .accessibilityIdentifier("ban-ends-on")
            }
            if target.certificateChoice {
                SwitchCard(
                    title: Strings.banByCertificate,
                    subtitle: Strings.banByCertificateHint,
                    identifier: "ban-by-certificate",
                    checked: sheet.form.byCertificate,
                    onChange: actions.setBanByCertificate
                )
            }
            if target.removeNowChoice {
                SwitchCard(
                    title: Strings.banRemoveNow,
                    subtitle: Strings.banRemoveNowHint,
                    identifier: "ban-remove-now",
                    checked: sheet.form.removeNow,
                    onChange: actions.setBanRemoveNow
                )
            }
            Paragraph(target.hasCertificate ? Strings.banAddress : Strings.banNoCertificate)
        }
    }

    private var durations: some View {
        let rows = stride(from: 0, to: banDurationsSecs.count, by: Self.durationsPerRow).map { start in
            Array(banDurationsSecs[start..<min(start + Self.durationsPerRow, banDurationsSecs.count)])
        }
        return VStack(alignment: .leading, spacing: MumcordSpace.s2) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: MumcordSpace.s2) {
                    ForEach(row, id: \.self) { secs in
                        MumcordChip(text: banDurationText(secs), selected: sheet.form.durationSecs == secs) {
                            actions.selectBanDuration(secs)
                        }
                        .accessibilityIdentifier("ban-duration")
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Strings.banDuration)
    }
}

private func banDurationText(_ secs: UInt32) -> String {
    switch secs {
    case banDurationsSecs[0]: Strings.banDurationHour
    case banDurationsSecs[1]: Strings.banDurationDay
    case banDurationsSecs[2]: Strings.banDurationWeek
    case banDurationsSecs[3]: Strings.banDurationMonth
    default: Strings.banDurationPermanent
    }
}

/// A switch with its name and a line on what it does, which may wrap.
private struct SwitchCard: View {
    let title: String
    let subtitle: String
    /// The accessibility identifier of the switch.
    let identifier: String
    let checked: Bool
    let onChange: (Bool) -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
            HStack(spacing: MumcordSpace.s3) {
                VStack(alignment: .leading, spacing: 0) {
                    Paragraph(title, color: theme.colors.text)
                    ParagraphText(subtitle, style: .small, color: theme.colors.textMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                MumcordToggle(checked: checked, label: title, onCheckedChange: onChange)
                    .accessibilityIdentifier(identifier)
            }
            .padding(.horizontal, MumcordSpace.s4)
            .padding(.vertical, MumcordSpace.s3)
        }
    }
}

private struct PersonLine: View {
    let name: String
    let channelName: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        Paragraph(Strings.moderationPersonLine(name, channelName), color: theme.colors.textMuted)
    }
}

private struct ReasonField: View {
    let reason: String
    let onChange: (String) -> Void
    let hint: String

    var body: some View {
        MumcordTextField(
            value: reason,
            onValueChange: onChange,
            label: Strings.moderationReason,
            hint: hint,
            capitalization: .sentences
        )
    }
}

private struct FailureBanner: View {
    let failure: ModerationFailure
    let name: String

    var body: some View {
        MumcordBanner(
            text: text,
            title: refused ? Strings.moderationRefusedTitle : nil,
            icon: MumcordIcons.alert,
            tone: .danger,
            horizontalMargin: 0
        )
        .accessibilityIdentifier("moderation-error")
        .onAppear { AccessibilityNotification.Announcement(text).post() }
    }

    private var refused: Bool {
        if case .refused = failure { return true }
        return false
    }

    private var text: String {
        switch failure {
        // The server's own words are shown as plain characters.
        case .refused(let serverText): serverText.isEmpty ? Strings.moderationRefused : serverText
        case .gone: Strings.moderationGone(name)
        case .disconnected: Strings.moderationDisconnected
        case .failed: Strings.moderationFailed
        }
    }
}
