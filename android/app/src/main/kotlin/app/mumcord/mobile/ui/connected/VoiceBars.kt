// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordIconButtonStyle
import app.mumcord.mobile.design.components.MumcordTalkButton
import app.mumcord.mobile.design.components.MumcordVoiceBar
import app.mumcord.mobile.design.components.MumcordVoiceBarLabels
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.tokens.MumcordSpace

const val VOICE_BAR_TEST_TAG = "voice-bar"
const val WHISPER_BAR_TEST_TAG = "whisper-bar"
const val WHISPER_HOLD_TEST_TAG = "whisper-hold"
const val WHISPER_CLOSE_TEST_TAG = "whisper-close"

/** A swipe up on the voice bar of at least this distance opens the full voice view. */
private val OPEN_SWIPE = 24.dp

/** The line under the channel name: which server, and whether voice runs. */
@Composable
fun VoiceUiState.statusLine(): String = stringResource(
    if (connected) R.string.voice_status_connected else R.string.voice_status_reconnecting,
    serverName,
)

/** What TalkBack reads for the mute button, which also says why it cannot be used. */
@Composable
fun MuteControl.actionLabel(): String = stringResource(
    when (this) {
        MuteControl.Unmuted -> R.string.voice_mute_microphone
        MuteControl.Muted -> R.string.voice_unmute_microphone
        MuteControl.ServerMuted -> R.string.voice_muted_by_server
        MuteControl.NoMicrophone -> R.string.voice_no_microphone_action
    },
)

/**
 * The slim voice bar above the tabs (mockup `02-channels`). A tap or a swipe up opens the full
 * voice view. Without the microphone, the mute button leads to the permission instead.
 */
@Composable
fun VoiceBar(
    voice: VoiceUiState,
    actions: ConnectedController,
    onLeave: () -> Unit,
    onMicrophone: () -> Unit,
) {
    val muteLabel = voice.mute.actionLabel()
    MumcordVoiceBar(
        channelName = voice.channelName,
        status = voice.statusLine(),
        talking = voice.talking,
        muted = voice.mute != MuteControl.Unmuted,
        deafened = voice.deafened,
        labels = MumcordVoiceBarLabels(
            open = stringResource(R.string.voice_open),
            talking = stringResource(R.string.voice_talking),
            notTalking = stringResource(R.string.voice_not_talking),
            mute = muteLabel,
            unmute = muteLabel,
            deafen = stringResource(R.string.voice_deafen),
            undeafen = stringResource(R.string.voice_undeafen),
            leave = stringResource(R.string.voice_leave_server),
        ),
        onOpen = actions::openVoice,
        onToggleMute = if (voice.mute == MuteControl.NoMicrophone) onMicrophone else actions::toggleMute,
        onToggleDeafen = actions::toggleDeafen,
        onLeave = onLeave,
        muteEnabled = voice.mute != MuteControl.ServerMuted,
        modifier = Modifier
            .testTag(VOICE_BAR_TEST_TAG)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, amount -> dragged += amount },
                    onDragEnd = { if (dragged <= -OPEN_SWIPE.toPx()) actions.openVoice() },
                )
            },
    )
}

/**
 * The whisper bar above the voice bar: who the whisper or shout goes to, as the label of the
 * button that sends it while it is held, and a button that puts the bar away.
 */
@Composable
fun WhisperBar(whisper: WhisperBarState, onHeldChange: (Boolean) -> Unit, onClose: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MumcordSpace.s2, end = MumcordSpace.s2, bottom = MumcordSpace.s2)
            .testTag(WHISPER_BAR_TEST_TAG),
    ) {
        MumcordTalkButton(
            label = stringResource(
                if (whisper.shout) R.string.whisper_to_channel else R.string.whisper_to_person,
                whisper.name,
            ),
            active = whisper.held,
            onPressedChange = withHapticTick(onHeldChange),
            stateDescription = stringResource(if (whisper.held) R.string.whisper_active else R.string.whisper_inactive),
            wide = true,
            icon = MumcordIcons.Users,
            modifier = Modifier.weight(1f).testTag(WHISPER_HOLD_TEST_TAG),
        )
        MumcordIconButton(
            icon = MumcordIcons.X,
            contentDescription = stringResource(R.string.whisper_close),
            onClick = onClose,
            style = MumcordIconButtonStyle.Tonal,
            modifier = Modifier.testTag(WHISPER_CLOSE_TEST_TAG),
        )
    }
}

/** [onPressedChange] with a short tick of the phone when the button goes down. */
@Composable
fun withHapticTick(onPressedChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptics = LocalHapticFeedback.current
    val change by rememberUpdatedState(onPressedChange)
    return { pressed ->
        if (pressed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        change(pressed)
    }
}
