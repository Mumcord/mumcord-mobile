// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.connect.messageRes

const val TALK_MODE_VOICE_ACTIVITY_TEST_TAG = "talk-mode-voice-activity"
const val TALK_MODE_PUSH_TO_TALK_TEST_TAG = "talk-mode-push-to-talk"
const val TALK_MODE_CONTINUE_TEST_TAG = "talk-mode-continue"

/**
 * First start: explains both talk modes and lets the user pick one. Nothing is picked for the
 * user, so "Continue" works only after a choice.
 */
@Composable
fun TalkModeChoiceScreen(
    selected: TalkMode?,
    saving: Boolean,
    error: ConnectionError?,
    onSelect: (TalkMode) -> Unit,
    onContinue: () -> Unit,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    MumcordScreen {
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s4),
            modifier = Modifier
                .windowInsetsPadding(topInsets)
                .windowInsetsPadding(bottomInsets)
                .padding(start = MumcordSpace.s4, top = MumcordSpace.s5, end = MumcordSpace.s4, bottom = MumcordSpace.s3),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MumcordSpace.s4),
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            ) {
                Intro()
                Column(
                    verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
                    modifier = Modifier.padding(top = MumcordSpace.s3).selectableGroup(),
                ) {
                    TalkModeCard(
                        icon = MumcordIcons.Activity,
                        title = stringResource(R.string.talk_mode_voice_activity),
                        text = stringResource(R.string.talk_mode_voice_activity_text),
                        perks = listOf(
                            stringResource(R.string.talk_mode_perk_noise_suppression),
                            stringResource(R.string.talk_mode_perk_hands_free),
                        ),
                        selected = selected == TalkMode.VOICE_ACTIVITY,
                        onClick = { onSelect(TalkMode.VOICE_ACTIVITY) },
                        modifier = Modifier.testTag(TALK_MODE_VOICE_ACTIVITY_TEST_TAG),
                    )
                    TalkModeCard(
                        icon = MumcordIcons.Mic,
                        title = stringResource(R.string.talk_mode_push_to_talk),
                        text = stringResource(R.string.talk_mode_push_to_talk_text),
                        perks = listOf(stringResource(R.string.talk_mode_perk_hold)),
                        selected = selected == TalkMode.PUSH_TO_TALK,
                        onClick = { onSelect(TalkMode.PUSH_TO_TALK) },
                        modifier = Modifier.testTag(TALK_MODE_PUSH_TO_TALK_TEST_TAG),
                    )
                }
                if (error != null) {
                    MumcordBanner(
                        text = stringResource(error.messageRes()),
                        icon = MumcordIcons.Alert,
                        tone = MumcordBannerTone.Danger,
                        horizontalMargin = 0.dp,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MumcordButton(
                    text = stringResource(R.string.talk_mode_continue),
                    onClick = onContinue,
                    enabled = selected != null && !saving,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).testTag(TALK_MODE_CONTINUE_TEST_TAG),
                )
                BasicText(
                    text = stringResource(R.string.talk_mode_change_later),
                    style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textMuted),
                )
            }
        }
    }
}

@Composable
private fun Intro() {
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = Modifier.padding(top = MumcordSpace.s4),
    ) {
        BasicText(
            text = stringResource(R.string.talk_mode_title),
            // The mockup's intro title: the display face of the title style at 30 sp. Android
            // draws the face a hair wider than the browser, so the letters sit a little closer
            // to keep the title on one line at the standard font size, as in the mockup.
            style = MumcordTheme.typography.title.copy(
                color = MumcordTheme.colors.text,
                fontSize = 30.sp,
                lineHeight = 1.15.em,
                letterSpacing = (-0.02).em,
            ),
            modifier = Modifier.semantics { heading() },
        )
        Paragraph(stringResource(R.string.talk_mode_subtitle))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TalkModeCard(
    icon: ImageVector,
    title: String,
    text: String,
    perks: List<String>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.bgSelected else colors.bgElevated)
            .border(2.dp, if (selected) colors.accent else colors.border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // The 2 dp border takes its own room, as the CSS border of the mockup's card.
            .padding(MumcordSpace.s4 + 2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .background(if (selected) colors.accent else colors.bgHover, RoundedCornerShape(16.dp)),
            ) {
                MumcordIcon(
                    icon = icon,
                    contentDescription = null,
                    tint = if (selected) colors.onAccent else colors.text,
                    size = MumcordSize.iconLg,
                )
            }
            BasicText(
                text = title,
                style = MumcordTheme.typography.talkButton.copy(color = colors.text),
                modifier = Modifier.weight(1f),
            )
            RadioMark(selected)
        }
        Paragraph(text)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        ) {
            perks.forEach { Perk(it) }
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val colors = MumcordTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .background(if (selected) colors.accent else Color.Transparent, CircleShape)
            .border(2.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
    ) {
        if (selected) {
            MumcordIcon(MumcordIcons.Check, contentDescription = null, tint = colors.onAccent, size = MumcordSize.iconSm)
        }
    }
}

@Composable
private fun Perk(text: String) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .defaultMinSize(minHeight = 32.dp)
            .background(colors.bgHover, CircleShape)
            .padding(horizontal = 12.dp),
    ) {
        MumcordIcon(MumcordIcons.Check, contentDescription = null, tint = colors.textSecondary, size = MumcordSize.iconSm)
        BasicText(text = text, style = MumcordTheme.typography.smallStrong.copy(color = colors.textSecondary))
    }
}
