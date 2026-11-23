// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.BanTarget
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordChip
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.components.MumcordToggle
import app.mumcord.mobile.design.components.SheetSectionPadding
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

const val MODERATION_MOVE_TEST_TAG = "moderation-move"
const val MODERATION_MUTE_TEST_TAG = "moderation-mute"
const val MODERATION_KICK_TEST_TAG = "moderation-kick"
const val MODERATION_BAN_TEST_TAG = "moderation-ban"
const val MOVE_CHOICE_TEST_TAG = "move-choice"
const val KICK_CONFIRM_TEST_TAG = "kick-confirm"
const val BAN_CONFIRM_TEST_TAG = "ban-confirm"
const val BAN_ANYWAY_TEST_TAG = "ban-anyway"
const val BAN_KICK_INSTEAD_TEST_TAG = "ban-kick-instead"
const val BAN_DURATION_TEST_TAG = "ban-duration"
const val MODERATION_ERROR_TEST_TAG = "moderation-error"

/**
 * The moderation section at the end of the person sheet (mockup `06-person-sheet`): only the
 * entries the server allows, and nothing at all without any of them.
 */
@Composable
fun ColumnScope.ModerationSection(rows: ModerationRows, actions: ModerationController) {
    if (!rows.any) return
    MumcordSectionHeader(stringResource(R.string.moderation_title), padding = SheetSectionPadding)
    MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
        var first = true
        fun divider() = (!first).also { first = false }
        if (rows.move) {
            MumcordListRow(
                title = stringResource(R.string.moderation_move),
                icon = MumcordIcons.Hash,
                divider = divider(),
                onClick = actions::openMove,
                modifier = Modifier.testTag(MODERATION_MOVE_TEST_TAG),
            )
        }
        if (rows.serverMute) {
            MumcordListRow(
                title = stringResource(if (rows.serverMuted) R.string.moderation_server_unmute else R.string.moderation_server_mute),
                icon = MumcordIcons.MicOff,
                divider = divider(),
                onClick = actions::toggleServerMute,
                modifier = Modifier.testTag(MODERATION_MUTE_TEST_TAG),
            )
        }
        if (rows.kick) {
            MumcordListRow(
                title = stringResource(R.string.moderation_kick),
                icon = MumcordIcons.PhoneOff,
                danger = true,
                divider = divider(),
                onClick = actions::openKick,
                modifier = Modifier.testTag(MODERATION_KICK_TEST_TAG),
            )
        }
        if (rows.ban) {
            MumcordListRow(
                title = stringResource(R.string.moderation_ban),
                icon = MumcordIcons.Shield,
                danger = true,
                divider = divider(),
                onClick = actions::openBan,
                modifier = Modifier.testTag(MODERATION_BAN_TEST_TAG),
            )
        }
    }
}

/** Draws the moderation step that replaced the person sheet. Going back returns to it. */
@Composable
fun ModerationSheetHost(
    sheet: ModerationSheet,
    actions: ModerationController,
    onBack: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    when (sheet) {
        is ModerationSheet.Move -> MoveSheet(sheet, actions, onBack, topInsets, bottomInsets)
        is ModerationSheet.Kick -> KickSheet(sheet, actions, onBack, topInsets, bottomInsets)
        is ModerationSheet.Ban -> BanSheet(sheet, actions, onBack, topInsets, bottomInsets)
    }
}

/** The channel picker: every channel but the person's own, indented like the channel list. */
@Composable
private fun MoveSheet(
    sheet: ModerationSheet.Move,
    actions: ModerationController,
    onBack: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.move_title), onBack, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.move_text, sheet.name))
        MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
            sheet.choices.forEachIndexed { index, choice ->
                MumcordListRow(
                    title = choice.name,
                    icon = MumcordIcons.Hash,
                    divider = index > 0,
                    onClick = { actions.move(choice.id) },
                    modifier = Modifier
                        .padding(start = (MumcordSpace.s4 * choice.depth))
                        .testTag(MOVE_CHOICE_TEST_TAG),
                )
            }
        }
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = onBack,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Kick Jonas?" with the optional reason, as the desktop's kick dialog (FR-10.57). */
@Composable
private fun KickSheet(
    sheet: ModerationSheet.Kick,
    actions: ModerationController,
    onBack: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.kick_title, sheet.name), onBack, topInsets, bottomInsets) {
        PersonLine(sheet.name, sheet.channelName)
        Paragraph(stringResource(R.string.kick_reconnect))
        ReasonField(sheet.reason, actions::changeKickReason, stringResource(R.string.kick_reason_hint, sheet.name))
        sheet.failure?.let { FailureBanner(it, sheet.name) }
        MumcordButton(
            text = stringResource(R.string.kick_confirm),
            onClick = actions::confirmKick,
            style = MumcordButtonStyle.Danger,
            enabled = !sheet.busy,
            modifier = Modifier.fillMaxWidth().testTag(KICK_CONFIRM_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = onBack,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The ban with the fields of the desktop's ban dialog (FR-10.12 … FR-10.14) that a standard
 * server honours: reason, length, the certificate, "Remove from server now". The address is
 * always part of the ban, so it is said instead of offered as a switch.
 */
@Composable
private fun BanSheet(
    sheet: ModerationSheet.Ban,
    actions: ModerationController,
    onBack: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.ban_title, sheet.name), onBack, topInsets, bottomInsets) {
        PersonLine(sheet.name, sheet.channelName)
        val target = sheet.target
        when {
            sheet.loading -> Paragraph(stringResource(R.string.ban_loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            target != null -> BanForm(sheet, target, actions)
        }
        sheet.failure?.let { FailureBanner(it, sheet.name) }
        if (sheet.conflict) {
            MumcordBanner(
                title = stringResource(R.string.ban_conflict_title),
                text = stringResource(R.string.ban_conflict_text),
                icon = MumcordIcons.Alert,
                tone = MumcordBannerTone.Warning,
                horizontalMargin = 0.dp,
            )
            MumcordButton(
                text = stringResource(R.string.ban_save_anyway),
                onClick = actions::banAnyway,
                style = MumcordButtonStyle.Danger,
                enabled = !sheet.busy,
                modifier = Modifier.fillMaxWidth().testTag(BAN_ANYWAY_TEST_TAG),
            )
        } else if (target != null) {
            MumcordButton(
                text = banButtonText(sheet, target),
                onClick = actions::confirmBan,
                style = MumcordButtonStyle.Danger,
                enabled = !sheet.busy,
                modifier = Modifier.fillMaxWidth().testTag(BAN_CONFIRM_TEST_TAG),
            )
            if (target.canKick) {
                MumcordButton(
                    text = stringResource(R.string.ban_kick_instead),
                    onClick = actions::kickInstead,
                    style = MumcordButtonStyle.Secondary,
                    enabled = !sheet.busy,
                    modifier = Modifier.fillMaxWidth().testTag(BAN_KICK_INSTEAD_TEST_TAG),
                )
            }
        }
        MumcordButton(
            text = stringResource(if (sheet.conflict) R.string.action_back else R.string.action_cancel),
            onClick = onBack,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BanForm(sheet: ModerationSheet.Ban, target: BanTarget, actions: ModerationController) {
    ReasonField(sheet.form.reason, actions::changeBanReason, stringResource(R.string.ban_reason_hint, sheet.name))
    if (target.quick) {
        Paragraph(stringResource(R.string.ban_quick))
        Paragraph(stringResource(R.string.ban_quick_targets))
        return
    }
    val durationLabel = stringResource(R.string.ban_duration)
    // Two rows: all five chips fit a phone's width only in small type. 12 dp between the rows
    // leaves each 36 dp chip its full 48 dp touch area.
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = Modifier.selectableGroup().semantics { contentDescription = durationLabel },
    ) {
        BAN_DURATIONS_SECS.chunked(DURATIONS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
                row.forEach { secs ->
                    MumcordChip(
                        text = durationText(secs),
                        selected = sheet.form.durationSecs == secs,
                        onClick = { actions.selectBanDuration(secs) },
                        modifier = Modifier.testTag(BAN_DURATION_TEST_TAG),
                    )
                }
            }
        }
    }
    sheet.endsAtMs?.let { Paragraph(stringResource(R.string.ban_ends_on, longDate(it))) }
    if (target.certificateChoice) {
        SwitchCard(
            title = stringResource(R.string.ban_by_certificate),
            subtitle = stringResource(R.string.ban_by_certificate_hint),
            checked = sheet.form.byCertificate,
            onChange = actions::setBanByCertificate,
        )
    }
    if (target.removeNowChoice) {
        SwitchCard(
            title = stringResource(R.string.ban_remove_now),
            subtitle = stringResource(R.string.ban_remove_now_hint),
            checked = sheet.form.removeNow,
            onChange = actions::setBanRemoveNow,
        )
    }
    Paragraph(stringResource(if (target.hasCertificate) R.string.ban_address else R.string.ban_no_certificate))
}

private const val DURATIONS_PER_ROW = 3

/** A switch with its name and a line on what it does, which may wrap. */
@Composable
private fun SwitchCard(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s3),
        ) {
            Column(Modifier.weight(1f)) {
                Paragraph(title, color = MumcordTheme.colors.text)
                BasicText(subtitle, style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textMuted))
            }
            MumcordToggle(checked = checked, onCheckedChange = onChange, contentDescription = title)
        }
    }
}

@Composable
private fun PersonLine(name: String, channelName: String) {
    Paragraph(stringResource(R.string.moderation_person_line, name, channelName), color = MumcordTheme.colors.textMuted)
}

@Composable
private fun ReasonField(reason: String, onChange: (String) -> Unit, hint: String) {
    MumcordTextField(
        value = reason,
        onValueChange = onChange,
        label = stringResource(R.string.moderation_reason),
        hint = hint,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
    )
}

@Composable
private fun FailureBanner(failure: ModerationFailure, name: String) {
    val text = when (failure) {
        is ModerationFailure.Refused -> failure.serverText.ifEmpty { stringResource(R.string.moderation_refused) }
        ModerationFailure.Gone -> stringResource(R.string.moderation_gone, name)
        ModerationFailure.Disconnected -> stringResource(R.string.moderation_disconnected)
        ModerationFailure.Failed -> stringResource(R.string.moderation_failed)
    }
    MumcordBanner(
        title = stringResource(R.string.moderation_refused_title).takeIf { failure is ModerationFailure.Refused },
        text = text,
        icon = MumcordIcons.Alert,
        tone = MumcordBannerTone.Danger,
        horizontalMargin = 0.dp,
        modifier = Modifier.testTag(MODERATION_ERROR_TEST_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun durationText(secs: UInt): String = stringResource(
    when (secs) {
        BAN_DURATIONS_SECS[0] -> R.string.ban_duration_hour
        BAN_DURATIONS_SECS[1] -> R.string.ban_duration_day
        BAN_DURATIONS_SECS[2] -> R.string.ban_duration_week
        BAN_DURATIONS_SECS[3] -> R.string.ban_duration_month
        else -> R.string.ban_duration_permanent
    },
)

@Composable
private fun banButtonText(sheet: ModerationSheet.Ban, target: BanTarget): String = when {
    target.quick -> stringResource(R.string.ban_confirm)
    sheet.form.durationSecs == 0u -> stringResource(R.string.ban_confirm_permanent)
    else -> stringResource(R.string.ban_confirm_for, durationText(sheet.form.durationSecs))
}

/** The end of a ban in the phone's language and time zone, such as "November 15, 2026". */
@Composable
private fun longDate(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
