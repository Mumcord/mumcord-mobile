// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordIconButtonStyle
import app.mumcord.mobile.design.components.MumcordLevelMeter
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordSegmentedPill
import app.mumcord.mobile.design.components.MumcordTalkButton
import app.mumcord.mobile.design.components.MumcordVoiceAction
import app.mumcord.mobile.design.components.MumcordVoiceTile
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import kotlinx.coroutines.flow.StateFlow

const val VOICE_VIEW_TEST_TAG = "voice-view"
const val VOICE_COLLAPSE_TEST_TAG = "voice-collapse"
const val VOICE_TILE_TEST_TAG = "voice-tile"
const val VOICE_MUTE_TEST_TAG = "voice-mute"
const val VOICE_DEAFEN_TEST_TAG = "voice-deafen"
const val VOICE_LEAVE_TEST_TAG = "voice-leave"
const val VOICE_TALK_MODE_TEST_TAG = "voice-talk-mode"
const val VOICE_HOLD_TEST_TAG = "voice-hold"
const val VOICE_LEVEL_TEST_TAG = "voice-level"

/** A swipe down on the top of the voice view of at least this distance closes it. */
private val CLOSE_SWIPE = 24.dp

/** Share of the width one person takes, so that three stand in a row. */
private const val TILE_WIDTH_FRACTION = 0.31f

/** Height of the hold-to-talk button and of the level meter that takes its place. */
private val TALK_AREA_HEIGHT = 96.dp

private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * The full voice view (mockup `03-voice-expanded`): the people of the own channel, the large
 * mute, deafen and leave buttons, the talk mode switch and, in thumb reach, the hold-to-talk
 * button, or the microphone level in voice activity mode. It covers the whole screen; the
 * chevron, a swipe down on it and the back gesture close it. The banners of the connected
 * screens stay visible above the buttons.
 */
@Composable
fun VoiceScreen(
    voice: VoiceUiState,
    state: ConnectedUiState,
    actions: ConnectedController,
    onLeave: () -> Unit,
    onMicrophone: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    BackHandler(onBack = actions::closeVoice)
    val colors = MumcordTheme.colors
    val title = stringResource(R.string.voice_view)
    val talkModeLabel = stringResource(R.string.voice_talk_mode)
    MumcordScreen {
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            modifier = Modifier
                .windowInsetsPadding(topInsets)
                .fillMaxSize()
                .clip(SHEET_SHAPE)
                .background(colors.bgChat)
                .border(1.dp, colors.border, SHEET_SHAPE)
                .windowInsetsPadding(bottomInsets)
                .padding(start = MumcordSpace.s4, top = MumcordSpace.s2, end = MumcordSpace.s4, bottom = MumcordSpace.s2)
                .testTag(VOICE_VIEW_TEST_TAG)
                .semantics { paneTitle = title },
        ) {
            CollapseHandle(actions::closeVoice)
            Column {
                BasicText(
                    text = voice.channelName,
                    // The mockup's title is one step larger than the app bar title.
                    style = MumcordTheme.typography.title.copy(color = colors.text, fontSize = 24.sp, lineHeight = 28.8.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                BasicText(
                    text = voice.statusLine(),
                    style = MumcordTheme.typography.small.copy(color = colors.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            VoiceTiles(voice.tileRows, Modifier.weight(1f))
            ConnectedBanners(state, actions::dismissDenied, onMicrophone)
            voice.whisper?.let { WhisperBar(it, actions::setWhisperHeld, actions::closeWhisper) }
            VoiceActions(voice, actions, onLeave, onMicrophone)
            MumcordSegmentedPill(
                options = listOf(
                    stringResource(R.string.talk_mode_voice_activity),
                    stringResource(R.string.talk_mode_push_to_talk),
                ),
                selectedIndex = TalkMode.entries.indexOf(voice.talkMode),
                onSelect = { actions.selectTalkMode(TalkMode.entries[it]) },
                modifier = Modifier
                    .testTag(VOICE_TALK_MODE_TEST_TAG)
                    .semantics { contentDescription = talkModeLabel },
            )
            if (voice.talkMode == TalkMode.PUSH_TO_TALK) {
                MumcordTalkButton(
                    label = stringResource(if (voice.talkHeld) R.string.voice_talking else R.string.voice_hold_to_talk),
                    active = voice.talkHeld,
                    onPressedChange = withHapticTick(actions::setTalkHeld),
                    stateDescription = stringResource(
                        if (voice.talkHeld) R.string.voice_talking else R.string.voice_not_talking,
                    ),
                    wide = true,
                    tall = true,
                    modifier = Modifier.testTag(VOICE_HOLD_TEST_TAG),
                )
            } else {
                InputLevel(actions.inputLevel)
            }
        }
    }
}

/** The chevron at the top (`.vsheet__collapse`); a tap or a swipe down closes the view. */
@Composable
private fun CollapseHandle(onClose: () -> Unit) {
    val label = stringResource(R.string.voice_collapse)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, amount -> dragged += amount },
                    onDragEnd = { if (dragged >= CLOSE_SWIPE.toPx()) onClose() },
                )
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                // Drawn 28 dp high as in the mockup; Compose widens the touch area to 48 dp.
                .size(width = MumcordSize.touchVisual, height = 28.dp)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClose)
                .testTag(VOICE_COLLAPSE_TEST_TAG),
        ) {
            MumcordIcon(MumcordIcons.ChevronDown, contentDescription = label, tint = MumcordTheme.colors.textSecondary)
        }
    }
}

/** The people in rows of three, each row centred (`.vgrid`); it scrolls when there are many. */
@Composable
private fun VoiceTiles(rows: List<List<VoiceTile>>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val tileWidth = maxWidth * TILE_WIDTH_FRACTION
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Room for the talking ring, which is drawn outside the avatar.
                .padding(top = MumcordSpace.s2, bottom = MumcordSpace.s2),
        ) {
            rows.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    row.forEach { tile ->
                        MumcordVoiceTile(
                            name = if (tile.isSelf) stringResource(R.string.voice_person_you, tile.name) else tile.name,
                            initials = tile.name.initials(1),
                            colorSlot = avatarSlot(tile.name),
                            talking = tile.talking,
                            talkingDescription = stringResource(R.string.person_talking),
                            mutedDescription = stringResource(R.string.person_muted).takeIf { tile.muted },
                            modifier = Modifier.width(tileWidth).testTag(VOICE_TILE_TEST_TAG),
                        )
                    }
                }
            }
        }
    }
}

/** Mute, deafen and leave as large round buttons with their names (`.vactions`). */
@Composable
private fun VoiceActions(
    voice: VoiceUiState,
    actions: ConnectedController,
    onLeave: () -> Unit,
    onMicrophone: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        // Each action is 88 dp wide, which leaves the mockup's 24 dp between the 64 dp buttons
        // and room for a label of two lines.
        val slot = Modifier.width(88.dp)
        MumcordVoiceAction(
            icon = if (voice.mute == MuteControl.Unmuted) MumcordIcons.Mic else MumcordIcons.MicOff,
            label = stringResource(
                when (voice.mute) {
                    MuteControl.Unmuted -> R.string.voice_mute
                    MuteControl.Muted -> R.string.voice_unmute
                    MuteControl.ServerMuted -> R.string.voice_muted_by_server
                    MuteControl.NoMicrophone -> R.string.voice_no_microphone
                },
            ),
            contentDescription = voice.mute.actionLabel(),
            onClick = if (voice.mute == MuteControl.NoMicrophone) onMicrophone else actions::toggleMute,
            style = if (voice.mute == MuteControl.Unmuted) MumcordIconButtonStyle.Tonal else MumcordIconButtonStyle.Off,
            enabled = voice.mute != MuteControl.ServerMuted,
            modifier = slot.testTag(VOICE_MUTE_TEST_TAG),
        )
        MumcordVoiceAction(
            icon = if (voice.deafened) MumcordIcons.HeadphonesOff else MumcordIcons.Headphones,
            label = stringResource(if (voice.deafened) R.string.voice_undeafen else R.string.voice_deafen),
            contentDescription = stringResource(if (voice.deafened) R.string.voice_undeafen else R.string.voice_deafen),
            onClick = actions::toggleDeafen,
            style = if (voice.deafened) MumcordIconButtonStyle.Off else MumcordIconButtonStyle.Tonal,
            modifier = slot.testTag(VOICE_DEAFEN_TEST_TAG),
        )
        MumcordVoiceAction(
            icon = MumcordIcons.PhoneOff,
            label = stringResource(R.string.voice_leave),
            contentDescription = stringResource(R.string.voice_leave_server),
            onClick = onLeave,
            style = MumcordIconButtonStyle.Danger,
            modifier = slot.testTag(VOICE_LEAVE_TEST_TAG),
        )
    }
}

/**
 * The microphone level, where the hold-to-talk button is in push-to-talk mode. The level is
 * collected here and read while drawing, so its up to 20 changes a second redraw only the bar.
 */
@Composable
private fun InputLevel(level: StateFlow<Int>) {
    val percent by level.collectAsStateWithLifecycle()
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2, Alignment.CenterVertically),
        modifier = Modifier.fillMaxWidth().height(TALK_AREA_HEIGHT).padding(horizontal = MumcordSpace.s2),
    ) {
        BasicText(
            text = stringResource(R.string.voice_level_hint),
            style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textMuted),
        )
        MumcordLevelMeter(
            level = { percent / 100f },
            contentDescription = stringResource(R.string.voice_level),
            modifier = Modifier.testTag(VOICE_LEVEL_TEST_TAG),
        )
    }
}
