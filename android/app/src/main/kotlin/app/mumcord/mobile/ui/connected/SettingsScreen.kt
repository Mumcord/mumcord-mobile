// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordLevelMeter
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordListRowValue
import app.mumcord.mobile.design.components.MumcordNote
import app.mumcord.mobile.design.components.MumcordOptionRow
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.components.MumcordToggle
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.SheetOverlay
import kotlinx.coroutines.flow.StateFlow

const val SETTINGS_SCREEN_TEST_TAG = "settings"
const val SETTINGS_TALK_MODE_TEST_TAG = "settings-talk-mode"
const val SETTINGS_NOISE_TEST_TAG = "settings-noise-suppression"
const val SETTINGS_LEVEL_TEST_TAG = "settings-input-level"
const val SETTINGS_OUTPUT_TEST_TAG = "settings-default-output"
const val SETTINGS_AUTOPLAY_TEST_TAG = "settings-autoplay-streams"
const val SETTINGS_LOAD_MEDIA_TEST_TAG = "settings-load-media"
const val SETTINGS_REDUCED_VOICE_TEST_TAG = "settings-reduced-voice"
const val SETTINGS_IDLE_TEST_TAG = "settings-idle-disconnect"
const val SETTINGS_NOTIFY_PRIVATE_TEST_TAG = "settings-notify-private"
const val SETTINGS_NOTIFY_MENTIONS_TEST_TAG = "settings-notify-mentions"
const val SETTINGS_SENDER_ONLY_TEST_TAG = "settings-sender-only"
const val SETTINGS_HISTORY_TEST_TAG = "settings-history"
const val SETTINGS_OPTION_TEST_TAG_PREFIX = "settings-option-"

/** The test tag of a choice in a settings sheet, by its position. */
fun settingsOptionTestTag(index: Int): String = SETTINGS_OPTION_TEST_TAG_PREFIX + index

/**
 * The settings (mockup `16-settings`, FR-5.100). Every switch is saved at once; the rows with a
 * value open a sheet with the choices. Left out until their features exist: echo cancellation
 * (the phone's own voice processing does it, there is nothing to switch), link previews (the
 * chat shows none yet), the language (task 8.3, after the translations), about and updates (task
 * 10.2). Dark, light or system is chosen on the themes screen (mockup `19-themes`). The
 * level meter, the "Chat" section and the note under "Notifications" have no mockup.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: MoreController,
    inputLevel: StateFlow<Int>,
    topInsets: WindowInsets,
) {
    Column(Modifier.fillMaxSize().testTag(SETTINGS_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.settings_title),
            onBack = actions::back,
            backContentDescription = stringResource(R.string.settings_back),
            windowInsets = topInsets,
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4)) {
            VoiceSection(state, actions, inputLevel)
            MobileDataSection(state, actions)
            MumcordSectionHeader(stringResource(R.string.settings_section_battery))
            MumcordList {
                SwitchRow(
                    title = stringResource(R.string.settings_idle_disconnect),
                    subtitle = stringResource(R.string.settings_idle_disconnect_sub),
                    icon = MumcordIcons.PhoneOff,
                    checked = state.mobile.idleDisconnect,
                    onCheckedChange = { on -> actions.changeMobile { it.copy(idleDisconnect = on) } },
                    testTag = SETTINGS_IDLE_TEST_TAG,
                )
            }
            NotificationsSection(state, actions)
            MumcordSectionHeader(stringResource(R.string.settings_section_chat))
            MumcordList {
                ValueRow(
                    title = stringResource(R.string.settings_history),
                    value = historyAgeText(state.app.historyMaxAgeDays),
                    icon = MumcordIcons.Trash,
                    onClick = { actions.openSheet(SettingsSheet.HistoryAge) },
                    testTag = SETTINGS_HISTORY_TEST_TAG,
                )
            }
        }
    }
}

@Composable
private fun VoiceSection(state: SettingsUiState, actions: MoreController, inputLevel: StateFlow<Int>) {
    MumcordSectionHeader(stringResource(R.string.settings_section_voice))
    MumcordList {
        ValueRow(
            title = stringResource(R.string.voice_talk_mode),
            value = talkModeText(state.talkMode),
            icon = MumcordIcons.Mic,
            onClick = { actions.openSheet(SettingsSheet.TalkMode) },
            testTag = SETTINGS_TALK_MODE_TEST_TAG,
        )
        state.noiseSuppression?.let { on ->
            SwitchRow(
                title = stringResource(R.string.settings_noise_suppression),
                icon = MumcordIcons.Activity,
                checked = on,
                onCheckedChange = actions::setNoiseSuppression,
                testTag = SETTINGS_NOISE_TEST_TAG,
                divider = true,
            )
        }
        LevelRow(inputLevel)
        if (state.hasEarpiece) {
            ValueRow(
                title = stringResource(R.string.settings_default_output),
                value = defaultOutputText(state.mobile.defaultOutput),
                icon = MumcordIcons.Speaker,
                onClick = { actions.openSheet(SettingsSheet.DefaultOutput) },
                testTag = SETTINGS_OUTPUT_TEST_TAG,
                divider = true,
            )
        }
    }
}

@Composable
private fun MobileDataSection(state: SettingsUiState, actions: MoreController) {
    MumcordSectionHeader(stringResource(R.string.settings_section_mobile_data))
    MumcordList {
        SwitchRow(
            title = stringResource(R.string.settings_autoplay_streams),
            icon = MumcordIcons.Monitor,
            checked = state.mobile.mobileDataAutoplayStreams,
            onCheckedChange = { on -> actions.changeMobile { it.copy(mobileDataAutoplayStreams = on) } },
            testTag = SETTINGS_AUTOPLAY_TEST_TAG,
        )
        SwitchRow(
            title = stringResource(R.string.settings_load_media),
            icon = MumcordIcons.Image,
            checked = state.mobile.mobileDataLoadMedia,
            onCheckedChange = { on -> actions.changeMobile { it.copy(mobileDataLoadMedia = on) } },
            testTag = SETTINGS_LOAD_MEDIA_TEST_TAG,
            divider = true,
        )
        SwitchRow(
            title = stringResource(R.string.settings_reduced_voice),
            icon = MumcordIcons.Wifi,
            checked = state.mobile.mobileDataReducedVoice,
            onCheckedChange = { on -> actions.changeMobile { it.copy(mobileDataReducedVoice = on) } },
            testTag = SETTINGS_REDUCED_VOICE_TEST_TAG,
            divider = true,
        )
    }
}

@Composable
private fun NotificationsSection(state: SettingsUiState, actions: MoreController) {
    MumcordSectionHeader(stringResource(R.string.settings_section_notifications))
    MumcordList {
        SwitchRow(
            title = stringResource(R.string.settings_notify_private),
            icon = MumcordIcons.Message,
            checked = state.app.notifyPrivateMessages,
            onCheckedChange = { on -> actions.changeApp { it.copy(notifyPrivateMessages = on) } },
            testTag = SETTINGS_NOTIFY_PRIVATE_TEST_TAG,
        )
        SwitchRow(
            title = stringResource(R.string.settings_notify_mentions),
            icon = MumcordIcons.Bell,
            checked = state.app.notifyMentions,
            onCheckedChange = { on -> actions.changeApp { it.copy(notifyMentions = on) } },
            testTag = SETTINGS_NOTIFY_MENTIONS_TEST_TAG,
            divider = true,
        )
        SwitchRow(
            title = stringResource(R.string.settings_sender_only),
            icon = MumcordIcons.Lock,
            checked = state.senderOnly,
            onCheckedChange = actions::setSenderOnly,
            testTag = SETTINGS_SENDER_ONLY_TEST_TAG,
            divider = true,
        )
    }
    // FR-5.54: there is no push server, so the settings say when notifications come.
    MumcordNote(
        stringResource(R.string.settings_notify_note),
        modifier = Modifier.padding(start = MumcordSpace.s4, top = MumcordSpace.s2, end = MumcordSpace.s4),
    )
}

/** The microphone level, live while the voice runs. Its changes redraw only the meter. */
@Composable
private fun LevelRow(level: StateFlow<Int>) {
    val percent by level.collectAsStateWithLifecycle()
    val title = stringResource(R.string.voice_level)
    MumcordListRow(
        title = title,
        icon = MumcordIcons.Volume,
        divider = true,
        trailing = {
            MumcordLevelMeter(
                level = { percent / 100f },
                contentDescription = title,
                modifier = Modifier.width(LEVEL_METER_WIDTH).testTag(SETTINGS_LEVEL_TEST_TAG),
            )
        },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    subtitle: String? = null,
    divider: Boolean = false,
) {
    MumcordListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        divider = divider,
        // Nothing is cut off, also with a large font.
        wrapTitle = true,
        trailing = {
            MumcordToggle(
                checked = checked,
                onCheckedChange = onCheckedChange,
                contentDescription = title,
                modifier = Modifier.testTag(testTag),
            )
        },
    )
}

/** A setting with its current value at the end; tapping it opens the choices. */
@Composable
private fun ValueRow(
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
    divider: Boolean = false,
) {
    MumcordListRow(
        title = title,
        icon = icon,
        divider = divider,
        wrapTitle = true,
        trailing = {
            MumcordListRowValue(value)
            MumcordIcon(MumcordIcons.ChevronRight, contentDescription = null, tint = MumcordTheme.colors.textMuted)
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

/** The sheet of a setting with choices; picking one saves it and closes the sheet. */
@Composable
fun SettingsSheetHost(
    state: SettingsUiState,
    actions: MoreController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    when (state.sheet) {
        SettingsSheet.TalkMode -> ChoiceSheet(
            title = stringResource(R.string.voice_talk_mode),
            options = TalkMode.entries.map { Choice(talkModeText(it), MumcordIcons.Mic, it == state.talkMode) },
            onSelect = { actions.selectTalkMode(TalkMode.entries[it]) },
            onDismiss = actions::closeSheet,
            topInsets = topInsets,
            bottomInsets = bottomInsets,
        )
        SettingsSheet.DefaultOutput -> ChoiceSheet(
            title = stringResource(R.string.settings_default_output),
            options = DefaultOutput.entries.map {
                Choice(defaultOutputText(it), outputIcon(it), it == state.mobile.defaultOutput)
            },
            onSelect = { actions.selectDefaultOutput(DefaultOutput.entries[it]) },
            onDismiss = actions::closeSheet,
            note = stringResource(R.string.settings_output_note),
            topInsets = topInsets,
            bottomInsets = bottomInsets,
        )
        SettingsSheet.HistoryAge -> ChoiceSheet(
            title = stringResource(R.string.settings_history),
            options = HISTORY_AGE_DAYS.map { Choice(historyAgeText(it), MumcordIcons.Trash, it == state.app.historyMaxAgeDays) },
            onSelect = { actions.selectHistoryAge(HISTORY_AGE_DAYS[it]) },
            onDismiss = actions::closeSheet,
            note = stringResource(R.string.settings_history_note),
            topInsets = topInsets,
            bottomInsets = bottomInsets,
        )
        null -> Unit
    }
}

private data class Choice(val title: String, val icon: ImageVector, val selected: Boolean)

@Composable
private fun ChoiceSheet(
    title: String,
    options: List<Choice>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    note: String? = null,
) {
    SheetOverlay(title, onDismiss, topInsets, bottomInsets) {
        MumcordList(
            horizontalMargin = 0.dp,
            background = MumcordTheme.colors.bgHover,
            modifier = Modifier.selectableGroup(),
        ) {
            options.forEachIndexed { index, option ->
                MumcordOptionRow(
                    title = option.title,
                    icon = option.icon,
                    selected = option.selected,
                    onClick = { onSelect(index) },
                    divider = index > 0,
                    modifier = Modifier.testTag(settingsOptionTestTag(index)),
                )
            }
        }
        if (note != null) MumcordNote(note)
    }
}

@Composable
private fun talkModeText(mode: TalkMode): String = when (mode) {
    TalkMode.VOICE_ACTIVITY -> stringResource(R.string.talk_mode_voice_activity)
    TalkMode.PUSH_TO_TALK -> stringResource(R.string.talk_mode_push_to_talk)
}

@Composable
private fun defaultOutputText(output: DefaultOutput): String = when (output) {
    DefaultOutput.ASK -> stringResource(R.string.settings_output_ask)
    DefaultOutput.SPEAKER -> stringResource(R.string.output_question_speaker)
    DefaultOutput.EARPIECE -> stringResource(R.string.output_question_earpiece)
}

private fun outputIcon(output: DefaultOutput): ImageVector = when (output) {
    DefaultOutput.ASK -> MumcordIcons.Info
    DefaultOutput.SPEAKER -> MumcordIcons.Speaker
    DefaultOutput.EARPIECE -> MumcordIcons.Volume
}

@Composable
private fun historyAgeText(days: UInt?): String =
    days?.let { pluralStringResource(R.plurals.settings_history_days, it.toInt(), it.toInt()) }
        ?: stringResource(R.string.settings_history_never)

private val LEVEL_METER_WIDTH = 120.dp
