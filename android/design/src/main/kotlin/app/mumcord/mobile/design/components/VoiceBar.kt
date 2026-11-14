// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.tokenShadow
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/** What TalkBack reads for the parts of the voice bar; the caller supplies translated text. */
@Immutable
data class MumcordVoiceBarLabels(
    val open: String,
    val talking: String,
    val notTalking: String,
    val mute: String,
    val unmute: String,
    val deafen: String,
    val undeafen: String,
    val leave: String,
)

/**
 * Slim voice bar above the tabs (`.voicebar`): talk indicator, channel name with a status line,
 * and the mute, deafen and leave buttons. Tapping the text opens the full voice screen.
 * [muteEnabled] is false while the user cannot change the mute themselves (muted by the
 * server); [MumcordVoiceBarLabels.unmute] then says why.
 */
@Composable
fun MumcordVoiceBar(
    channelName: String,
    status: String,
    talking: Boolean,
    muted: Boolean,
    deafened: Boolean,
    labels: MumcordVoiceBarLabels,
    onOpen: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    muteEnabled: Boolean = true,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.bar)
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .tokenShadow(MumcordTheme.shadows.raised, shape)
            .defaultMinSize(minHeight = 56.dp)
            .background(colors.bgElevated, shape)
            .border(1.dp, colors.border, shape)
            // 1 dp of each side is the border.
            .padding(start = 13.dp, top = 7.dp, end = 7.dp, bottom = 7.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = labels.open, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    stateDescription = if (talking) labels.talking else labels.notTalking
                },
        ) {
            TalkIndicator(talking)
            Column(Modifier.weight(1f)) {
                LineText(channelName, MumcordTheme.typography.bodyTight, colors.text)
                LineText(status, MumcordTheme.typography.captionTight, colors.textMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MumcordIconButton(
                icon = if (muted) MumcordIcons.MicOff else MumcordIcons.Mic,
                contentDescription = if (muted) labels.unmute else labels.mute,
                onClick = onToggleMute,
                style = if (muted) MumcordIconButtonStyle.Off else MumcordIconButtonStyle.Tonal,
                enabled = muteEnabled,
            )
            MumcordIconButton(
                icon = if (deafened) MumcordIcons.HeadphonesOff else MumcordIcons.Headphones,
                contentDescription = if (deafened) labels.undeafen else labels.deafen,
                onClick = onToggleDeafen,
                style = if (deafened) MumcordIconButtonStyle.Off else MumcordIconButtonStyle.Tonal,
            )
            MumcordIconButton(
                icon = MumcordIcons.PhoneOff,
                contentDescription = labels.leave,
                onClick = onLeave,
                style = MumcordIconButtonStyle.Danger,
            )
        }
    }
}

@Composable
private fun TalkIndicator(talking: Boolean) {
    val colors = MumcordTheme.colors
    val glow = if (talking) Modifier.tokenShadow(MumcordTheme.shadows.talking, CircleShape) else Modifier
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .then(glow)
            .background(if (talking) colors.talking else colors.bgHover, CircleShape),
    ) {
        MumcordIcon(
            icon = MumcordIcons.Mic,
            contentDescription = null,
            tint = if (talking) colors.onTalking else colors.textMuted,
            size = MumcordSize.iconMd,
        )
    }
}

@Preview(name = "Voice bar dark")
@Composable
private fun VoiceBarDarkPreview() {
    PreviewFrame(darkTheme = true) { VoiceBarSample() }
}

@Preview(name = "Voice bar light")
@Composable
private fun VoiceBarLightPreview() {
    PreviewFrame(darkTheme = false) { VoiceBarSample() }
}

@Composable
internal fun VoiceBarSample() {
    val labels = MumcordVoiceBarLabels(
        open = "Open voice controls",
        talking = "Talking",
        notTalking = "Not talking",
        mute = "Mute microphone",
        unmute = "Unmute microphone",
        deafen = "Deafen",
        undeafen = "Undeafen",
        leave = "Leave server",
    )
    MumcordVoiceBar("Lobby", "Nordlicht · Voice connected", false, false, false, labels, {}, {}, {}, {})
    MumcordVoiceBar("Lobby", "Nordlicht · Voice connected", true, true, true, labels, {}, {}, {}, {})
}
