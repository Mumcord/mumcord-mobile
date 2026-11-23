// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.StreamWatch
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAppBarSubtitle
import app.mumcord.mobile.design.components.MumcordAvatar
import app.mumcord.mobile.design.components.MumcordAvatarSize
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordLiveBadge
import app.mumcord.mobile.design.components.MumcordSlider
import app.mumcord.mobile.design.components.MumcordStageButton
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.chat.ChatComposer
import app.mumcord.mobile.ui.chat.ChatController
import app.mumcord.mobile.ui.chat.ChatRow
import app.mumcord.mobile.ui.chat.ChatUiState
import app.mumcord.mobile.ui.chat.keyboardPadding
import app.mumcord.mobile.ui.connected.avatarSlot
import app.mumcord.mobile.ui.connected.initials

const val STREAM_COLUMN_TEST_TAG = "stream-column"
const val STREAM_FILL_SCREEN_TEST_TAG = "stream-fill-screen"
const val STREAM_CHAT_STRIP_TEST_TAG = "stream-chat-strip"

/** How many of the newest messages the chat strip under a stream shows. */
private const val STRIP_MESSAGES = 2

/** The volume slider on the stage (`.slider` of mockup 22). */
private val STAGE_SLIDER_WIDTH = 140.dp

/**
 * The newest messages of [rows] for the chat strip under a stream, oldest first. Messages of
 * blocked people are not in the rows at all.
 */
fun stripMessages(rows: List<ChatRow>, count: Int = STRIP_MESSAGES): List<ChatMessage> =
    rows.filterIsInstance<ChatRow.Message>().map { it.message }.takeLast(count)

/**
 * The opened stream in the right column of two columns (FR-5.71, mockup 22): the sharer and
 * their channel above, the stage with LIVE, viewers, volume and the buttons, and below it a
 * strip with the newest messages of the user's channel chat and its input bar, so the chat
 * stays usable while watching. The strip's arrow and the mini window button make room for the
 * whole chat; the full screen button opens the whole screen.
 *
 * @param chat the user's channel chat; null for the moment it is not known yet.
 */
@Composable
fun StreamColumn(
    stream: StreamUi,
    actions: StreamsController,
    chat: ChatUiState?,
    chatActions: ChatController,
    topInsets: WindowInsets,
) {
    if (chat != null) {
        LifecycleResumeEffect(chatActions) {
            chatActions.setResumed(true)
            onPauseOrDispose { chatActions.setResumed(false) }
        }
    }
    Column(Modifier.fillMaxSize().testTag(STREAM_COLUMN_TEST_TAG).keyboardPadding()) {
        MumcordAppBar(
            title = stream.title(),
            border = true,
            windowInsets = topInsets,
            leading = { MumcordAvatar(stream.name.initials(1), avatarSlot(stream.name)) },
            subtitle = stream.channel?.let { channel -> { MumcordAppBarSubtitle(channel) } },
        )
        // At the top: in portrait the column is narrow and the stage leaves room below it.
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = MumcordSpace.s4, top = MumcordSpace.s4, end = MumcordSpace.s4),
        ) {
            Stage(stream, actions)
        }
        if (chat != null) {
            ChatStrip(chat, onShowChat = actions::collapse)
            ChatComposer(chat, chatActions)
        }
    }
}

/** The 16:9 stage (`.stage`), as large as the space allows; dark in both themes like the full screen. */
@Composable
private fun Stage(stream: StreamUi, actions: StreamsController) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    Box(
        modifier = Modifier
            .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
            .clip(shape)
            .background(colors.stageBg)
            .border(1.dp, colors.border, shape)
            .reportStreamBounds(LocalStreamBounds.current),
    ) {
        if (stream.watch is StreamWatch.Watching && !stream.paused) {
            StreamPicture(stream.videoId, Modifier.fillMaxSize())
        }
        stream.openStatus()?.let { status ->
            StageStatus(status, retry = (stream.tile as? TileLook.Unreachable)?.let { { actions.retry(stream.key) } })
        }
        MumcordLiveBadge(stringResource(R.string.stream_live), Modifier.align(Alignment.TopStart).padding(14.dp))
        stream.viewers?.let { viewers ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s1),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .background(colors.mediaScrim, RoundedCornerShape(MumcordRadius.sm))
                    .padding(horizontal = MumcordSpace.s2, vertical = MumcordSpace.s1),
            ) {
                MumcordIcon(MumcordIcons.User, contentDescription = null, tint = colors.mediaText, size = MumcordSize.iconSm)
                BasicText(
                    text = pluralStringResource(R.plurals.stream_watching, viewers, viewers),
                    style = MumcordTheme.typography.captionStrong.copy(color = colors.mediaText),
                    maxLines = 1,
                )
            }
        }
        StageControls(stream, actions, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun StageControls(stream: StreamUi, actions: StreamsController, modifier: Modifier) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.stageBg)))
            .padding(horizontal = MumcordSpace.s3, vertical = MumcordSpace.s2),
    ) {
        // Only a share that sends sound has a volume (FR-5.72). The slider takes what the
        // buttons leave, at most its width of the mockup, so "Stop watching" always fits.
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            if (stream.hasSound) {
                MumcordIcon(MumcordIcons.Volume, contentDescription = null, tint = colors.mediaText)
                MumcordSlider(
                    value = stream.volume / MAX_STREAM_VOLUME.toFloat(),
                    onValueChange = { actions.changeVolume(stream.key, Math.round(it * MAX_STREAM_VOLUME)) },
                    contentDescription = stringResource(R.string.stream_volume),
                    onMedia = true,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = STAGE_SLIDER_WIDTH)
                        .testTag(STREAM_VOLUME_TEST_TAG),
                )
            }
        }
        MumcordStageButton(
            icon = MumcordIcons.Monitor,
            contentDescription = stringResource(R.string.stream_collapse),
            onClick = actions::collapse,
            size = 40.dp,
            modifier = Modifier.testTag(STREAM_COLLAPSE_TEST_TAG),
        )
        MumcordStageButton(
            icon = MumcordIcons.Maximize,
            contentDescription = stringResource(R.string.stream_expand),
            onClick = actions::fillScreen,
            size = 40.dp,
            modifier = Modifier.testTag(STREAM_FILL_SCREEN_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.stream_stop),
            onClick = actions::close,
            style = MumcordButtonStyle.Danger,
            small = true,
            modifier = Modifier.testTag(STREAM_STOP_TEST_TAG),
        )
    }
}

/** The strip under the stage (`.strip`): the chat's name and its newest messages. */
@Composable
private fun ChatStrip(chat: ChatUiState, onShowChat: () -> Unit) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s1),
        modifier = Modifier
            .padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s3)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bgElevated)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = MumcordSpace.s3, vertical = MumcordSpace.s2)
            .testTag(STREAM_CHAT_STRIP_TEST_TAG),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 36.dp),
        ) {
            MumcordIcon(MumcordIcons.Hash, contentDescription = null, tint = colors.textSecondary, size = MumcordSize.iconSm)
            BasicText(
                text = stringResource(R.string.stream_chat_strip, chat.title),
                style = MumcordTheme.typography.bodyStrong.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            MumcordIconButton(MumcordIcons.ChevronUp, stringResource(R.string.stream_chat_strip_open), onShowChat)
        }
        stripMessages(chat.rows).forEach { message -> StripMessage(message) }
    }
}

// The sender's name and the message's text come from the server: plain characters only.
@Composable
private fun StripMessage(message: ChatMessage) {
    val colors = MumcordTheme.colors
    val text = message.quote.snippet.ifEmpty { stringResource(R.string.chat_image_word) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        MumcordAvatar(message.senderName.initials(1), avatarSlot(message.senderName), size = MumcordAvatarSize.Small)
        BasicText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = MumcordTheme.typography.bodyStrong.fontWeight)) { append(message.senderName) }
                append("  ")
                append(text)
            },
            style = MumcordTheme.typography.body.copy(color = colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
