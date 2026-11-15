// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.LinkKind
import app.mumcord.mobile.bridge.MessageContent
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.bridge.Span

/** Marks the range of an `@` mention of the user, so the row can draw its rounded background. */
const val MENTION_TAG = "mention"

/** The looks of the runs that need a colour of the theme. */
data class ChatTextStyles(
    val link: SpanStyle,
    val mention: SpanStyle,
    val code: SpanStyle,
    /** The real host that is shown after a link whose text does not name it. */
    val host: SpanStyle,
)

/**
 * The runs of a paragraph as styled plain text. Nothing in a run is interpreted: its characters
 * are appended as they are, so text that looks like markup stays text. A link becomes a range
 * that calls [onLink] when tapped and does nothing else; it is never opened from here. When
 * the shown text does not name the host the link goes to, the host is written after it.
 */
fun List<Span>.toAnnotatedString(styles: ChatTextStyles, onLink: (SafeLink) -> Unit): AnnotatedString =
    buildAnnotatedString {
        for (span in this@toAnnotatedString) {
            val style = span.style(styles)
            val link = span.link
            when {
                link != null -> {
                    val annotation = LinkAnnotation.Clickable(
                        tag = link.url,
                        styles = TextLinkStyles(style.merge(styles.link)),
                        linkInteractionListener = { onLink(link) },
                    )
                    withLink(annotation) { append(span.text) }
                    if (link.showsHost(span.text)) withStyle(styles.host) { append(" (${link.host})") }
                }
                span.mention -> {
                    val start = length
                    withStyle(style.merge(styles.mention)) { append(span.text) }
                    addStringAnnotation(MENTION_TAG, annotation = "", start = start, end = length)
                }
                else -> withStyle(style) { append(span.text) }
            }
        }
    }

private fun Span.style(styles: ChatTextStyles): SpanStyle {
    val decorations = listOfNotNull(
        TextDecoration.Underline.takeIf { underline },
        TextDecoration.LineThrough.takeIf { strikethrough },
    )
    val base = SpanStyle(
        fontWeight = FontWeight.Bold.takeIf { bold },
        fontStyle = FontStyle.Italic.takeIf { italic },
        textDecoration = TextDecoration.combine(decorations).takeIf { decorations.isNotEmpty() },
    )
    return if (code) base.merge(styles.code) else base
}

/** True when the reader cannot tell from [text] where the link goes. */
private fun SafeLink.showsHost(text: String): Boolean = misleading || !text.contains(host, ignoreCase = true)

/** The fixed-width look of inline code and code blocks; the colours come from the theme. */
fun codeStyle(background: Color): SpanStyle =
    SpanStyle(fontFamily = FontFamily.Monospace, background = background)

/** The pictures the links of a paragraph point to (image and GIF addresses), each once. */
fun List<Span>.inlineMedia(): List<SafeLink> =
    mapNotNull { span -> span.link?.takeIf { it.kind == LinkKind.IMAGE || it.kind == LinkKind.GIF } }.distinctBy { it.url }

/** The text of a message in one line, for the reply bar and screen readers. */
fun MessageContent.plainText(): String =
    blocks.mapNotNull { block ->
        when (block) {
            is ContentBlock.Paragraph -> block.spans.joinToString("") { it.text }
            is ContentBlock.Quote -> block.spans.joinToString("") { it.text }
            is ContentBlock.Code -> block.text
            is ContentBlock.Image -> null
        }
    }.joinToString(" ").replace('\n', ' ').trim()
