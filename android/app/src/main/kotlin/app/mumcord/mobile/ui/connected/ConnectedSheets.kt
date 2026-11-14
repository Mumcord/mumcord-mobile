// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordPersonHeader
import app.mumcord.mobile.design.components.MumcordSliderRow
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.components.MumcordToggle
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay
import kotlin.math.roundToInt

const val CHANNEL_PASSWORD_JOIN_TEST_TAG = "channel-password-join"
const val CHANNEL_MENU_JOIN_TEST_TAG = "channel-menu-join"
const val CHANNEL_MENU_FOLD_TEST_TAG = "channel-menu-fold"
const val PERSON_MUTE_TEST_TAG = "person-mute"
const val PERSON_SHEET_TEST_TAG = "person-sheet"
const val PERSON_WHISPER_TEST_TAG = "person-whisper"
const val CHANNEL_MENU_SHOUT_TEST_TAG = "channel-menu-shout"

/** Draws the sheet that is open over the connected screens. */
@Composable
fun ConnectedSheetHost(
    sheet: ConnectedSheet,
    actions: ConnectedController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    onMicrophoneContinue: () -> Unit,
    onMicrophoneNotNow: () -> Unit,
    onNotificationsContinue: () -> Unit = {},
    onNotificationsNotNow: () -> Unit = {},
) {
    when (sheet) {
        is ConnectedSheet.ChannelPassword -> ChannelPasswordSheet(sheet, actions, topInsets, bottomInsets)
        is ConnectedSheet.PersonSheet -> PersonSheet(sheet, actions, topInsets, bottomInsets)
        is ConnectedSheet.ChannelMenu -> ChannelMenuSheet(sheet, actions, topInsets, bottomInsets)
        ConnectedSheet.Microphone -> MicrophoneSheet(onMicrophoneContinue, onMicrophoneNotNow, topInsets, bottomInsets)
        ConnectedSheet.Notifications ->
            NotificationSheet(onNotificationsContinue, onNotificationsNotNow, topInsets, bottomInsets)
    }
}

@Composable
private fun ChannelPasswordSheet(
    sheet: ConnectedSheet.ChannelPassword,
    actions: ConnectedController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.channel_password_title), actions::dismissSheet, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.channel_password_text, sheet.channelName))
        MumcordTextField(
            value = sheet.password,
            onValueChange = actions::changePassword,
            label = stringResource(R.string.password_label),
            error = if (sheet.wrong) stringResource(R.string.channel_password_wrong) else null,
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { actions.submitPassword() }),
        )
        MumcordButton(
            text = stringResource(R.string.channel_join),
            onClick = actions::submitPassword,
            enabled = sheet.password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag(CHANNEL_PASSWORD_JOIN_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = actions::dismissSheet,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Mockup `06-person-sheet`: the head, the two settings that apply to the user only, and whisper. */
@Composable
private fun PersonSheet(
    sheet: ConnectedSheet.PersonSheet,
    actions: ConnectedController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(title = null, actions::dismissSheet, topInsets, bottomInsets, paneTitle = sheet.name) {
        MumcordPersonHeader(
            name = sheet.name,
            initials = sheet.name.initials(1),
            colorSlot = avatarSlot(sheet.name),
            channelName = sheet.channelName,
            status = sheet.state.statusIcons(),
            modifier = Modifier.testTag(PERSON_SHEET_TEST_TAG),
        )
        MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
            MumcordSliderRow(
                title = stringResource(R.string.person_volume),
                valueText = stringResource(R.string.person_volume_value, sheet.volumePercent),
                value = sheet.volumePercent / ConnectedController.MAX_VOLUME_PERCENT.toFloat(),
                onValueChange = { actions.changeVolume((it * ConnectedController.MAX_VOLUME_PERCENT).roundToInt()) },
                icon = MumcordIcons.Volume,
            )
            MumcordListRow(
                title = stringResource(R.string.person_mute),
                icon = MumcordIcons.VolumeX,
                divider = true,
                trailing = {
                    MumcordToggle(
                        checked = sheet.locallyMuted,
                        onCheckedChange = actions::setLocalMute,
                        contentDescription = stringResource(R.string.person_mute),
                        modifier = Modifier.testTag(PERSON_MUTE_TEST_TAG),
                    )
                },
            )
        }
        // A card of its own, as in the mockup, where it follows "Send private message".
        MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
            MumcordListRow(
                title = stringResource(R.string.person_whisper, sheet.name),
                icon = MumcordIcons.Users,
                trailing = {
                    MumcordIcon(
                        MumcordIcons.ChevronRight,
                        contentDescription = null,
                        tint = MumcordTheme.colors.textMuted,
                        size = MumcordSize.iconSm,
                    )
                },
                onClick = { actions.openWhisper(WhisperTarget.Person(sheet.session)) },
                modifier = Modifier.testTag(PERSON_WHISPER_TEST_TAG),
            )
        }
    }
}

/** What a long press on a channel offers: joining and folding, which are also on the row, and shouting. */
@Composable
private fun ChannelMenuSheet(
    sheet: ConnectedSheet.ChannelMenu,
    actions: ConnectedController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(sheet.name, actions::dismissSheet, topInsets, bottomInsets) {
        MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
            if (sheet.canJoin) {
                MumcordListRow(
                    title = stringResource(R.string.channel_join),
                    icon = MumcordIcons.Volume,
                    onClick = { actions.join(sheet.id) },
                    modifier = Modifier.testTag(CHANNEL_MENU_JOIN_TEST_TAG),
                )
            }
            if (sheet.expanded != null) {
                MumcordListRow(
                    title = stringResource(if (sheet.expanded) R.string.channel_collapse else R.string.channel_expand),
                    icon = if (sheet.expanded) MumcordIcons.ChevronUp else MumcordIcons.ChevronDown,
                    divider = sheet.canJoin,
                    onClick = { actions.toggleCollapsed(sheet.id) },
                    modifier = Modifier.testTag(CHANNEL_MENU_FOLD_TEST_TAG),
                )
            }
            MumcordListRow(
                title = stringResource(R.string.channel_shout),
                icon = MumcordIcons.Users,
                divider = sheet.canJoin || sheet.expanded != null,
                onClick = { actions.openWhisper(WhisperTarget.Channel(sheet.id)) },
                modifier = Modifier.testTag(CHANNEL_MENU_SHOUT_TEST_TAG),
            )
        }
    }
}
