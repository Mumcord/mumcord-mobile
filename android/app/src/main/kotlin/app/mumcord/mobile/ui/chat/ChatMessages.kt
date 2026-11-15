// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.ImageRef
import app.mumcord.mobile.bridge.LinkKind
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.bridge.Span
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordMessageRow
import app.mumcord.mobile.design.components.MumcordReplyQuote
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.connected.avatarSlot
import app.mumcord.mobile.ui.connected.initials
import kotlin.math.abs

const val CHAT_MESSAGE_TEST_TAG = "chat-message"
const val CHAT_PENDING_TEST_TAG = "chat-pending"
const val CHAT_RETRY_TEST_TAG = "chat-retry"
const val CHAT_IMAGE_TEST_TAG = "chat-image"

private const val MENTION_TINT = 0.28f
private val MENTION_RADIUS = 6.dp
private val MENTION_PADDING = 3.dp
private val MENTION_INSET = 1.dp
private val QUOTE_EDGE = 3.dp

@Composable
private fun chatTextStyles(): ChatTextStyles {
    val colors = MumcordTheme.colors
    return remember(colors) {
        ChatTextStyles(
            link = SpanStyle(color = colors.accentText, textDecoration = TextDecoration.Underline),
            mention = SpanStyle(color = colors.accentText, fontWeight = FontWeight.SemiBold),
            code = codeStyle(colors.bgElevated),
            host = SpanStyle(color = colors.textMuted),
        )
    }
}

/**
 * One message of somebody (or an own one that is out). Holding it starts a reply; screen
 * readers get the same as an action of the row.
 */
@Composable
fun MessageItem(row: ChatRow.Message, actions: ChatController, loader: ChatImageLoader, clock: ChatClock) {
    val message = row.message
    val haptics = LocalHapticFeedback.current
    val replyLabel = stringResource(R.string.chat_reply_action)
    val mentionWords = stringResource(R.string.chat_mentions_you)
    val highlighted = message.mentionsMe && !message.own
    MumcordMessageRow(
        // The name is untrusted text and shown as plain characters.
        author = message.senderName,
        time = clock.time(message.sentAtMs),
        initials = message.senderName.initials(1),
        colorSlot = avatarSlot(message.senderName),
        showHeader = row.showHeader,
        highlighted = highlighted,
        modifier = Modifier
            .testTag(CHAT_MESSAGE_TEST_TAG)
            .pointerInput(message.id) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.startReply(message)
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                if (highlighted) stateDescription = mentionWords
                customActions = listOf(
                    CustomAccessibilityAction(replyLabel) {
                        actions.startReply(message)
                        true
                    },
                )
            },
    ) {
        MessageBody(message, actions, loader)
    }
}

@Composable
private fun ColumnScope.MessageBody(message: ChatMessage, actions: ChatController, loader: ChatImageLoader) {
    val colors = MumcordTheme.colors
    val onLink = remember(actions) { { link: SafeLink -> actions.tapLink(link) } }
    message.content.reply?.let { reply ->
        // Both are untrusted text and shown as plain characters.
        MumcordReplyQuote(reply.name, reply.snippet.ifEmpty { stringResource(R.string.chat_image_word) })
    }
    for (block in message.content.blocks) {
        when (block) {
            is ContentBlock.Paragraph -> {
                if (block.spans.any { it.text.isNotEmpty() }) ChatParagraph(block.spans, onLink)
                for (link in block.spans.inlineMedia()) {
                    InlineImage(ChatImageSource.Remote(link.url, gif = link.kind == LinkKind.GIF), actions, loader)
                }
            }
            is ContentBlock.Quote -> ChatParagraph(
                block.spans,
                onLink,
                color = colors.textSecondary,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .drawBehind { drawRect(colors.borderStrong, size = Size(QUOTE_EDGE.toPx(), size.height)) }
                    .padding(start = MumcordSpace.s3),
            )
            is ContentBlock.Code -> BasicText(
                // Shown exactly as it was sent.
                text = block.text,
                style = MumcordTheme.typography.small.copy(color = colors.text, fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MumcordRadius.md))
                    .background(colors.bgElevated)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = MumcordSpace.s3, vertical = MumcordSpace.s2),
            )
            is ContentBlock.Image -> InlineImage(message.imageSource(block.image), actions, loader)
        }
    }
    if (message.content.truncated) {
        BasicText(
            text = stringResource(R.string.chat_truncated),
            style = MumcordTheme.typography.caption.copy(color = colors.textMuted),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun ChatMessage.imageSource(image: ImageRef): ChatImageSource = when (image) {
    is ImageRef.Embedded ->
        ChatImageSource.Embedded(chat, id, image.imageId, image.width.toInt(), image.height.toInt())
    is ImageRef.Remote -> ChatImageSource.Remote(image.link.url, gif = image.link.kind == LinkKind.GIF)
}

@Composable
private fun InlineImage(source: ChatImageSource, actions: ChatController, loader: ChatImageLoader) {
    val gif = source is ChatImageSource.Remote && source.gif
    val description = stringResource(if (gif) R.string.chat_gif_word else R.string.chat_image_word)
    val favouriteLabel = stringResource(R.string.chat_gif_add_action)
    val openLabel = stringResource(R.string.chat_image_open_action)
    val haptics = LocalHapticFeedback.current
    val addFavourite: (() -> Unit)? = (source as? ChatImageSource.Remote)?.takeIf { it.gif }?.let { remote ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            actions.addGif(remote.url)
        }
    }
    ChatInlineImage(
        source = source,
        loader = loader,
        onOpen = { actions.openViewer(source) },
        onLongPress = addFavourite,
        modifier = Modifier
            .padding(top = 6.dp)
            .testTag(CHAT_IMAGE_TEST_TAG)
            // Its own stop for screen readers inside the message, with its own actions.
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOfNotNull(
                    CustomAccessibilityAction(openLabel) {
                        actions.openViewer(source)
                        true
                    },
                    addFavourite?.let { add ->
                        CustomAccessibilityAction(favouriteLabel) {
                            add()
                            true
                        }
                    },
                )
            },
    )
}

/**
 * A paragraph of runs as styled plain text. Mentions of the user get the rounded tint of the
 * mockup, drawn behind the text from where the text was laid out.
 */
@Composable
private fun ChatParagraph(
    spans: List<Span>,
    onLink: (SafeLink) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MumcordTheme.colors.text,
) {
    val styles = chatTextStyles()
    val text = remember(spans, styles, onLink) { spans.toAnnotatedString(styles, onLink) }
    val tint = MumcordTheme.colors.accent.copy(alpha = MENTION_TINT)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text = text,
        style = MumcordTheme.typography.body.copy(color = color),
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind { layout?.let { drawMentions(text, it, tint) } },
    )
}

private fun DrawScope.drawMentions(text: AnnotatedString, layout: TextLayoutResult, tint: Color) {
    for (range in text.getStringAnnotations(MENTION_TAG, 0, text.length)) {
        if (range.end <= range.start || range.end > layout.layoutInput.text.length) continue
        val firstLine = layout.getLineForOffset(range.start)
        val lastLine = layout.getLineForOffset(range.end - 1)
        for (line in firstLine..lastLine) {
            val left = if (line == firstLine) layout.getHorizontalPosition(range.start, true) else layout.getLineLeft(line)
            val right = if (line == lastLine) layout.getHorizontalPosition(range.end, true) else layout.getLineRight(line)
            val padding = MENTION_PADDING.toPx()
            val inset = MENTION_INSET.toPx()
            drawRoundRect(
                color = tint,
                topLeft = Offset(minOf(left, right) - padding, layout.getLineTop(line) + inset),
                size = Size(abs(right - left) + 2 * padding, layout.getLineBottom(line) - layout.getLineTop(line) - 2 * inset),
                cornerRadius = CornerRadius(MENTION_RADIUS.toPx()),
            )
        }
    }
}

/** An own message on its way, or one that did not get out, with what can be done about it. */
@Composable
fun PendingItem(row: ChatRow.Pending, selfName: String, actions: ChatController) {
    val colors = MumcordTheme.colors
    val send = row.send
    val content = send.content
    MumcordMessageRow(
        author = selfName,
        time = "",
        initials = selfName.initials(1),
        colorSlot = avatarSlot(selfName),
        showHeader = row.showHeader,
        modifier = Modifier.testTag(CHAT_PENDING_TEST_TAG),
    ) {
        if (content is PendingContent.Text) {
            content.reply?.let { MumcordReplyQuote(it.name, it.snippet.ifEmpty { stringResource(R.string.chat_image_word) }) }
        }
        BasicText(
            text = when (content) {
                is PendingContent.Text -> content.text
                is PendingContent.Image -> stringResource(R.string.chat_image_word)
                is PendingContent.Gif -> stringResource(R.string.chat_gif_word)
            },
            style = MumcordTheme.typography.body.copy(color = colors.textSecondary),
        )
        val failure = send.failure
        if (failure == null) {
            BasicText(stringResource(R.string.chat_sending), style = MumcordTheme.typography.caption.copy(color = colors.textMuted))
        } else {
            BasicText(
                text = stringResource(
                    when {
                        failure == SendFailure.NotSent -> R.string.chat_not_sent
                        content is PendingContent.Image -> R.string.chat_image_refused
                        else -> R.string.chat_refused
                    },
                ),
                style = MumcordTheme.typography.caption.copy(color = colors.danger),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2), modifier = Modifier.padding(top = MumcordSpace.s1)) {
                if (failure == SendFailure.NotSent) {
                    MumcordButton(
                        text = stringResource(R.string.chat_retry),
                        onClick = { actions.retry(send.id) },
                        style = MumcordButtonStyle.Secondary,
                        small = true,
                        modifier = Modifier.testTag(CHAT_RETRY_TEST_TAG),
                    )
                }
                MumcordButton(
                    text = stringResource(R.string.chat_discard),
                    onClick = { actions.discard(send.id) },
                    style = MumcordButtonStyle.Ghost,
                    small = true,
                )
            }
        }
    }
}
