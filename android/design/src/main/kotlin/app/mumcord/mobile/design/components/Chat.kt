// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.icons.filled
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Chat message (`.msg`): avatar, author with time, and the text. [attachment] is shown under
 * the text (an image). The text is plain: it comes from the server and is shown as it is, never
 * interpreted here.
 */
@Composable
fun MumcordMessageRow(
    author: String,
    time: String,
    text: String,
    initials: String,
    colorSlot: Int,
    modifier: Modifier = Modifier,
    attachment: (@Composable () -> Unit)? = null,
) {
    MumcordMessageRow(author, time, initials, colorSlot, modifier) {
        BasicText(text = text, style = MumcordTheme.typography.body.copy(color = MumcordTheme.colors.text))
        if (attachment != null) {
            Box(Modifier.padding(top = MumcordSpace.s2)) { attachment() }
        }
    }
}

/**
 * Chat message (`.msg`) with free [content] under the author line: text runs, a reply quote,
 * images. With [showHeader] off the message continues the one above it: no avatar and no
 * author line, the content stays in the same column. [highlighted] is the tinted row with the
 * accent edge of a message that mentions the user (`.msg.is-mention`).
 */
@Composable
fun MumcordMessageRow(
    author: String,
    time: String,
    initials: String,
    colorSlot: Int,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (highlighted) {
                    Modifier
                        .clip(shape)
                        .background(colors.accent.copy(alpha = MENTION_ROW_TINT))
                        .drawBehind { drawRect(colors.accent, size = Size(MENTION_EDGE.toPx(), size.height)) }
                        .padding(horizontal = MumcordSpace.s2, vertical = 6.dp)
                } else {
                    // The highlighted row is 8 dp wider on both sides; the others line up with it.
                    Modifier.padding(horizontal = MumcordSpace.s2)
                },
            ),
    ) {
        if (showHeader) {
            MumcordAvatar(initials, colorSlot, size = MumcordAvatarSize.Large, modifier = Modifier.padding(top = 2.dp))
        } else {
            Spacer(Modifier.width(MumcordAvatarSize.Large.diameter))
        }
        Column(Modifier.weight(1f)) {
            if (showHeader) {
                Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
                    LineText(author, MumcordTheme.typography.bodyStrong, colors.text, Modifier.alignByBaseline().weight(1f, fill = false))
                    LineText(time, MumcordTheme.typography.caption, colors.textMuted, Modifier.alignByBaseline())
                }
            }
            content()
        }
    }
}

/** Day separator of a chat (`.daysep`): the day between two hairlines. */
@Composable
fun MumcordDaySeparator(text: String, modifier: Modifier = Modifier) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = MumcordSpace.s2).semantics { heading() },
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.border))
        LineText(text, MumcordTheme.typography.captionStrong, colors.textMuted)
        Box(Modifier.weight(1f).height(1.dp).background(colors.border))
    }
}

/**
 * The quoted original above a reply (`.quote`): who wrote it and one line of it, with the
 * accent edge. Both texts are plain.
 */
@Composable
fun MumcordReplyQuote(name: String, text: String, modifier: Modifier = Modifier) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = modifier
            .padding(top = 2.dp, bottom = MumcordSpace.s1)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topEnd = MumcordRadius.md, bottomEnd = MumcordRadius.md))
            .background(colors.bgElevated)
            .drawBehind { drawRect(colors.accent, size = Size(QUOTE_EDGE.toPx(), size.height)) }
            .padding(start = 10.dp + QUOTE_EDGE, top = 6.dp, end = 10.dp, bottom = 6.dp),
    ) {
        // The name is capped at 64 characters by the engine; the quoted line gives way to it.
        LineText(name, MumcordTheme.typography.smallStrong, colors.text, Modifier.alignByBaseline().widthIn(max = QUOTE_NAME_MAX))
        LineText(text, MumcordTheme.typography.small, colors.textSecondary, Modifier.alignByBaseline().weight(1f, fill = false))
    }
}

/** A calm, centred line in a chat for something the app cannot show (`.hint`). */
@Composable
fun MumcordChatHint(text: String, modifier: Modifier = Modifier, icon: ImageVector = MumcordIcons.Paperclip) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = MumcordSpace.s2),
    ) {
        MumcordIcon(icon, contentDescription = null, tint = colors.textMuted, size = MumcordSize.iconSm)
        BasicText(
            text = text,
            style = MumcordTheme.typography.small.copy(color = colors.textMuted, textAlign = TextAlign.Center),
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * The bar above the input bar while a reply is being written: [title] ("Replying to Anna"),
 * one line of the original and the button that gives the reply up.
 */
@Composable
fun MumcordReplyBar(
    title: String,
    text: String,
    closeContentDescription: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MumcordTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgElevated)
            .drawBehind { drawRect(colors.accent, size = Size(QUOTE_EDGE.toPx(), size.height)) }
            .padding(start = MumcordSpace.s4, end = MumcordSpace.s1),
    ) {
        Column(Modifier.weight(1f).padding(vertical = MumcordSpace.s2)) {
            LineText(title, MumcordTheme.typography.smallStrong, colors.text)
            LineText(text, MumcordTheme.typography.small, colors.textSecondary)
        }
        MumcordIconButton(MumcordIcons.X, closeContentDescription, onClick = onClose)
    }
}

/** A small floating pill over a list, such as "New messages" in a chat. */
@Composable
fun MumcordPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .defaultMinSize(minHeight = MumcordSize.touchTargetAndroid)
            .padding(vertical = (MumcordSize.touchTargetAndroid - PILL_HEIGHT) / 2)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MumcordSpace.s3),
    ) {
        if (icon != null) MumcordIcon(icon, contentDescription = null, tint = colors.onAccent, size = MumcordSize.iconSm)
        LineText(text, MumcordTheme.typography.smallStrong, colors.onAccent)
    }
}

/**
 * A GIF of the favourites grid (`.gif`): the picture ([content]) in a rounded square with the
 * "GIF" tag and the star that takes it out of the favourites. Tapping the tile picks it.
 */
@Composable
fun MumcordGifTile(
    contentDescription: String,
    tag: String,
    removeContentDescription: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.card)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(colors.bgElevated)
            .border(1.dp, colors.border, shape)
            .clickable(onClickLabel = contentDescription, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        content()
        LineText(
            tag,
            MumcordTheme.typography.captionStrong,
            colors.text,
            Modifier
                .align(Alignment.BottomStart)
                .padding(MumcordSpace.s2)
                .background(colors.bgElevated, RoundedCornerShape(MumcordRadius.sm))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        // The star is drawn at 28 dp as in the mockup; its touch area is the full 48 dp.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(MumcordSize.touchTargetAndroid)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { this.contentDescription = removeContentDescription },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(GIF_STAR).background(colors.bgElevated, CircleShape)) {
                MumcordIcon(remember { MumcordIcons.Star.filled() }, contentDescription = null, tint = colors.warning, size = MumcordSize.iconSm)
            }
        }
    }
}

private const val MENTION_ROW_TINT = 0.12f
private val MENTION_EDGE = 3.dp
private val QUOTE_EDGE = 3.dp
private val QUOTE_NAME_MAX = 120.dp
private val PILL_HEIGHT = 32.dp
private val GIF_STAR = 28.dp

/**
 * Text input bar at the bottom of a chat (`.inputbar`): buttons before the field ([leading],
 * such as image and GIF), the field, and buttons after it ([trailing], such as send). When it
 * is the last element of a screen, [windowInsets] keeps it clear of the system bar and the
 * keyboard.
 */
@Composable
fun MumcordInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(),
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    // The cursor is kept here; a caller that needs it uses the overload below.
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    MumcordInputBar(
        value = field,
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        placeholder = placeholder,
        modifier = modifier,
        windowInsets = windowInsets,
        leading = leading,
        trailing = trailing,
    )
}

/** The input bar for a caller that needs the cursor position (the `@` autocomplete). */
@Composable
fun MumcordInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(),
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = MumcordTheme.colors
    val textStyle = MumcordTheme.typography.body.copy(color = colors.text)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgChat)
            .drawBehind {
                val y = 0.5.dp.toPx()
                drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .windowInsetsPadding(windowInsets)
            // 1 dp of the top padding is the border line.
            .padding(start = MumcordSpace.s2, top = MumcordSpace.s2 + 1.dp, end = MumcordSpace.s2, bottom = MumcordSpace.s2),
    ) {
        leading()
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accent),
            maxLines = 4,
            modifier = Modifier.weight(1f),
            decorationBox = { field ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .defaultMinSize(minHeight = MumcordSize.touchVisual)
                        .background(colors.bgElevated, RoundedCornerShape(MumcordSize.touchVisual / 2))
                        .border(1.dp, colors.border, RoundedCornerShape(MumcordSize.touchVisual / 2))
                        .padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s2),
                ) {
                    if (value.text.isEmpty()) {
                        LineText(placeholder, MumcordTheme.typography.body, colors.textMuted)
                    }
                    field()
                }
            },
        )
        trailing()
    }
}

@Preview(name = "Chat dark")
@Composable
private fun ChatDarkPreview() {
    PreviewFrame(darkTheme = true) { ChatSample() }
}

@Preview(name = "Chat light")
@Composable
private fun ChatLightPreview() {
    PreviewFrame(darkTheme = false) { ChatSample() }
}

@Composable
internal fun ChatSample() {
    MumcordDaySeparator("Today", Modifier.padding(horizontal = MumcordSpace.s2))
    MumcordMessageRow(
        author = "Anna",
        time = "20:14",
        text = "Anyone up for a round tonight? I can host after nine.",
        initials = "A",
        colorSlot = 4,
        modifier = Modifier.padding(horizontal = MumcordSpace.s2),
    )
    MumcordMessageRow("Lea", "20:15", "L", colorSlot = 2, highlighted = true, modifier = Modifier.padding(horizontal = MumcordSpace.s2)) {
        MumcordReplyQuote("Anna", "Anyone up for a round tonight?")
        BasicText("Count me in.", style = MumcordTheme.typography.body.copy(color = MumcordTheme.colors.text))
    }
    MumcordChatHint("Jonas shared a file · available in the desktop app")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        MumcordPill("New messages", onClick = {}, icon = MumcordIcons.ChevronDown)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2), modifier = Modifier.padding(horizontal = MumcordSpace.s4)) {
        repeat(2) {
            MumcordGifTile("GIF", "GIF", "Remove from favourites", onClick = {}, onRemove = {}, modifier = Modifier.weight(1f)) {}
        }
    }
    MumcordReplyBar("Replying to Anna", "Anyone up for a round tonight?", "Cancel reply", onClose = {})
    MumcordInputBar(
        value = "",
        onValueChange = {},
        placeholder = "Message Lobby…",
        leading = {
            MumcordIconButton(MumcordIcons.Image, contentDescription = "Add image", onClick = {})
            MumcordIconButton(MumcordIcons.Gif, contentDescription = "Add GIF", onClick = {})
        },
        trailing = {
            MumcordIconButton(MumcordIcons.Send, "Send", onClick = {}, style = MumcordIconButtonStyle.Accent)
        },
    )
}
