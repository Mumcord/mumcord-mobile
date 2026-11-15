// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.ImageRef
import app.mumcord.mobile.bridge.LinkKind
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.bridge.Span
import app.mumcord.mobile.session.messageContent
import app.mumcord.mobile.session.span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {
    private val styles = ChatTextStyles(
        link = SpanStyle(color = Color.Blue),
        mention = SpanStyle(color = Color.Red),
        code = codeStyle(Color.Gray),
        host = SpanStyle(color = Color.Green),
    )

    private val tapped = mutableListOf<SafeLink>()

    private fun render(vararg spans: Span) = spans.toList().toAnnotatedString(styles) { tapped += it }

    private fun link(url: String, host: String, misleading: Boolean = false, kind: LinkKind = LinkKind.WEB) =
        SafeLink(url, host, kind, misleading)

    @Test
    fun runsAreJoinedInOrderAsPlainText() {
        assertEquals("Hello world\nnext line", render(span("Hello "), span("world\nnext line")).text)
    }

    @Test
    fun stylesOfARunBecomeSpanStyles() {
        val text = render(
            span("bold").copy(bold = true),
            span("italic").copy(italic = true),
            span("both").copy(underline = true, strikethrough = true),
            span("code").copy(code = true),
        )
        val byText = text.spanStyles.associate { text.text.substring(it.start, it.end) to it.item }
        assertEquals(FontWeight.Bold, byText.getValue("bold").fontWeight)
        assertEquals(FontStyle.Italic, byText.getValue("italic").fontStyle)
        assertEquals(
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)),
            byText.getValue("both").textDecoration,
        )
        assertEquals(Color.Gray, byText.getValue("code").background)
    }

    @Test
    fun aMentionOfTheUserIsMarkedOverExactlyItsCharacters() {
        val text = render(span("@Lea").copy(mention = true), span(" can you host?"))
        val mention = text.getStringAnnotations(MENTION_TAG, 0, text.length).single()
        assertEquals("@Lea", text.text.substring(mention.start, mention.end))
        assertEquals("@Lea can you host?", text.text)
    }

    @Test
    fun aLinkIsATapTargetThatOnlyReportsTheTap() {
        val target = link("https://nordlicht.example/rules", "nordlicht.example")
        val text = render(span("Rules: "), span("nordlicht.example/rules").copy(link = target))
        assertEquals("Rules: nordlicht.example/rules", text.text)
        val range = text.getLinkAnnotations(0, text.length).single()
        assertEquals("nordlicht.example/rules", text.text.substring(range.start, range.end))
        val annotation = range.item as LinkAnnotation.Clickable
        assertTrue("nothing is opened before a tap", tapped.isEmpty())
        annotation.linkInteractionListener!!.onClick(annotation)
        assertSame(target, tapped.single())
    }

    @Test
    fun noLinkIsEverHandedToTheSystemByTheTextItself() {
        val text = render(
            span("a").copy(link = link("https://a.example/", "a.example")),
            span("https://b.example/x").copy(link = link("https://b.example/x", "b.example")),
            span("javascript:alert(1) https://c.example www.d.example mumble://e"),
        )
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals("only the engine's links are links; addresses in plain text stay text", 2, links.size)
        assertTrue(links.none { it.item is LinkAnnotation.Url })
    }

    @Test
    fun aLinkWhoseTextDoesNotNameItsHostShowsTheRealHost() {
        val text = render(span("click here").copy(link = link("https://files.example/a", "files.example")))
        assertEquals("click here (files.example)", text.text)
        val range = text.getLinkAnnotations(0, text.length).single()
        assertEquals("the added host is not part of the tap target", "click here", text.text.substring(range.start, range.end))
    }

    @Test
    fun aMisleadingLinkAlwaysShowsTheRealHost() {
        val text = render(
            span("https://bank.example/login").copy(link = link("https://bank.example.evil.test/login", "bank.example.evil.test", misleading = true)),
        )
        assertEquals("https://bank.example/login (bank.example.evil.test)", text.text)
    }

    @Test
    fun aLinkThatNamesItsHostIsShownAsWritten() {
        val text = render(span("NORDLICHT.example/rules").copy(link = link("https://nordlicht.example/rules", "nordlicht.example")))
        assertEquals("NORDLICHT.example/rules", text.text)
    }

    /** Text that looks like markup, in every flavour a renderer might interpret. */
    private val adversarial = listOf(
        "<script>alert('x')</script>",
        "<img src=x onerror=alert(1)>",
        "<a href=\"javascript:alert(1)\">click</a>",
        "<b>bold</b> <i>i</i> <u>u</u> <font color=red size=99>big</font>",
        "&lt;script&gt; &amp; &#x3C;svg/onload=alert(1)&#x3E; &nbsp;",
        "**bold** _italic_ `code` ~~strike~~ # heading",
        "[text](https://evil.example) ![img](https://evil.example/x.png)",
        "<iframe src=\"https://evil.example\"></iframe><style>*{display:none}</style>",
        "%s %d %1\$s {0} \${name} \\n \\u202e",
        "<!-- comment --><![CDATA[x]]><?xml version=\"1.0\"?>",
        "intent://evil#Intent;scheme=http;end content://x file:///etc/passwd tel:112",
        "@everyone @Lea <@1234> :smile: \u0000 �",
    )

    @Test
    fun textThatLooksLikeMarkupIsShownCharacterForCharacter() {
        for (payload in adversarial) {
            val text = render(span(payload))
            assertEquals(payload, text.text)
            assertTrue(payload, text.getLinkAnnotations(0, text.length).isEmpty())
            assertTrue(payload, text.getStringAnnotations(0, text.length).isEmpty())
            assertTrue("an unstyled run adds no visible style: $payload", text.spanStyles.all { it.item == SpanStyle() })
        }
    }

    @Test
    fun markupInALinksTextOrInAMentionStaysText() {
        for (payload in adversarial) {
            val target = link("https://safe.example/", "safe.example")
            val linked = render(span(payload).copy(link = target))
            assertTrue(payload, linked.text.startsWith(payload))
            val range = linked.getLinkAnnotations(0, linked.length).single()
            assertEquals(payload, linked.text.substring(range.start, range.end))
            assertTrue(range.item is LinkAnnotation.Clickable)

            val mention = render(span(payload).copy(mention = true))
            assertEquals(payload, mention.text)
            assertTrue(mention.getLinkAnnotations(0, mention.length).isEmpty())
        }
    }

    @Test
    fun theUiAddsNoInvisibleOrDirectionChangingCharacters() {
        val text = render(
            span("plain "),
            span("link").copy(link = link("https://x.example/", "x.example")),
            span("@Lea").copy(mention = true),
            span("code").copy(code = true),
        )
        val invisible = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
        assertTrue(invisible.find(text.text) == null)
        assertEquals("plain link (x.example)@Leacode", text.text)
    }

    @Test
    fun onlyImageAndGifLinksAreShownInline() {
        val gif = link("https://gifs.example/a.gif", "gifs.example", kind = LinkKind.GIF)
        val image = link("https://img.example/a.png", "img.example", kind = LinkKind.IMAGE)
        val web = link("https://web.example/", "web.example")
        val media = listOf(
            span("a").copy(link = gif),
            span("b").copy(link = web),
            span("c").copy(link = image),
            span("again").copy(link = gif),
            span("text"),
        ).inlineMedia()
        assertEquals(listOf(gif, image), media)
    }

    @Test
    fun theOneLineTextOfAMessageLeavesPicturesOut() {
        val content = messageContent(
            blocks = listOf(
                ContentBlock.Paragraph(listOf(span("Look"), span(" here\nplease"))),
                ContentBlock.Image(ImageRef.Remote(link("https://img.example/a.png", "img.example", kind = LinkKind.IMAGE))),
                ContentBlock.Code("val x = 1"),
                ContentBlock.Quote(listOf(span("quoted"))),
            ),
        )
        assertEquals("Look here please val x = 1 quoted", content.plainText())
        assertEquals("", messageContent(blocks = emptyList()).plainText())
    }
}
