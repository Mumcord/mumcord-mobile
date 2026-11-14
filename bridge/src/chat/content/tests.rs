// SPDX-License-Identifier: AGPL-3.0-only

//! What hostile and broken messages turn into. The bodies are given both raw, as a server could
//! send them, and after the engine's sanitiser: the model must be harmless in both cases, so it
//! holds even if the sanitiser missed something.

use mumcord_engine::chat::sanitize_html;

use super::*;
use crate::chat::wire::{SNIPPET_CHARS, capped, quote_html};

fn spans(block: &ContentBlock) -> &[Span] {
    match block {
        ContentBlock::Paragraph { spans } | ContentBlock::Quote { spans } => spans,
        _ => &[],
    }
}

/// Every text the apps would show, in order.
fn texts(content: &MessageContent) -> Vec<String> {
    let mut out = Vec::new();
    for block in &content.blocks {
        match block {
            ContentBlock::Code { text } => out.push(text.clone()),
            ContentBlock::Image { .. } => {}
            block => out.extend(spans(block).iter().map(|s| s.text.clone())),
        }
    }
    out
}

fn links(content: &MessageContent) -> Vec<SafeLink> {
    content
        .blocks
        .iter()
        .flat_map(|block| spans(block).iter().filter_map(|s| s.link.clone()))
        .collect()
}

fn image_refs(content: &MessageContent) -> Vec<ImageRef> {
    content
        .blocks
        .iter()
        .filter_map(|block| match block {
            ContentBlock::Image { image } => Some(image.clone()),
            _ => None,
        })
        .collect()
}

/// The model of a body as a server sent it, and of the same body after the engine's sanitiser.
fn both(raw: &str) -> [MessageContent; 2] {
    [parse(raw).content, parse(&sanitize_html(raw)).content]
}

/// Nothing an app shows or opens may be anything but text and a checked web address.
fn assert_harmless(content: &MessageContent) {
    for link in links(content) {
        let url = url::Url::parse(&link.url).expect("a link is a valid address");
        assert!(matches!(url.scheme(), "http" | "https"), "{}", link.url);
        assert_eq!(url.host_str(), Some(link.host.as_str()));
    }
    for image in image_refs(content) {
        if let ImageRef::Remote { link } = image {
            assert!(link.url.starts_with("http://") || link.url.starts_with("https://"));
        }
    }
}

const PNG: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

#[test]
fn plain_text_and_basic_styles() {
    let content = parse("hi <b>bold <i>both</i></b> <u>u</u> <s>s</s> <code>c</code>").content;
    let [ContentBlock::Paragraph { spans }] = &content.blocks[..] else {
        panic!("one paragraph: {content:?}");
    };
    let styled: Vec<(&str, bool, bool, bool, bool, bool)> = spans
        .iter()
        .map(|s| {
            (
                s.text.as_str(),
                s.bold,
                s.italic,
                s.underline,
                s.strikethrough,
                s.code,
            )
        })
        .collect();
    assert_eq!(
        styled,
        [
            ("hi ", false, false, false, false, false),
            ("bold ", true, false, false, false, false),
            ("both", true, true, false, false, false),
            (" ", false, false, false, false, false),
            ("u", false, false, true, false, false),
            (" ", false, false, false, false, false),
            ("s", false, false, false, true, false),
            (" ", false, false, false, false, false),
            ("c", false, false, false, false, true),
        ]
    );
    assert!(content.reply.is_none() && !content.truncated);
}

#[test]
fn script_is_never_content_and_never_markup() {
    for raw in [
        "<script>alert(1)</script>hello",
        "<SCRIPT SRC=//evil.example/x.js></SCRIPT>hello",
        "<style>body{display:none}</style>hello",
        "<script>alert(1)hello",
    ] {
        for content in both(raw) {
            assert_harmless(&content);
            let shown = texts(&content).concat();
            assert!(
                !shown.contains("alert") && !shown.contains("display"),
                "{raw}: {shown}"
            );
        }
    }
    // Escaped markup is text, shown as it was typed.
    for content in both("&lt;script&gt;alert(1)&lt;/script&gt;") {
        assert_eq!(texts(&content), ["<script>alert(1)</script>"]);
        assert!(links(&content).is_empty());
    }
}

#[test]
fn event_handlers_styles_and_unknown_tags_leave_only_their_text() {
    for raw in [
        "<b onclick=\"alert(1)\" style=\"position:fixed;top:0\">hello</b>",
        "<div onmouseover=alert(1)><span style='background:url(http://t.example/x)'>hello</span></div>",
        "<iframe src=\"https://evil.example\"></iframe><object data=x></object><embed src=x>hello",
        "<form action=\"https://evil.example\"><input name=p><button>hello</button></form>",
        "<marquee><blink>hello</blink></marquee>",
        "<meta http-equiv=refresh content=\"0;url=https://evil.example\">hello",
        "<link rel=stylesheet href=\"https://evil.example/x.css\">hello",
        "<base href=\"https://evil.example/\">hello",
    ] {
        for content in both(raw) {
            assert_harmless(&content);
            assert_eq!(texts(&content).concat(), "hello", "{raw}");
            assert!(
                links(&content).is_empty() && image_refs(&content).is_empty(),
                "{raw}"
            );
        }
    }
}

#[test]
fn foreign_markup_carries_no_handlers_or_links() {
    for raw in [
        "<svg onload=alert(1)><text>hello</text></svg>",
        "<math><mi xlink:href=\"javascript:alert(1)\">hello</mi></math>",
        "<svg><a xlink:href=\"javascript:alert(1)\"><text>hello</text></a></svg>",
    ] {
        for content in both(raw) {
            assert_harmless(&content);
            // The sanitiser drops such content altogether; without it only the text is left.
            assert!(
                ["", "hello"].contains(&texts(&content).concat().as_str()),
                "{raw}"
            );
            assert!(
                links(&content).is_empty() && image_refs(&content).is_empty(),
                "{raw}"
            );
        }
    }
}

#[test]
fn only_web_addresses_are_links() {
    for address in [
        "javascript:alert(1)",
        "JaVaScRiPt:alert(1)",
        " javascript:alert(1)",
        "java\tscript:alert(1)",
        "data:text/html,<script>alert(1)</script>",
        "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
        "vbscript:msgbox(1)",
        "file:///etc/passwd",
        "intent://evil#Intent;scheme=http;end",
        "content://media/external",
        "tel:+491234",
        "mailto:a@example.com",
        "mumble://server.example/",
        "//evil.example/x",
        "/relative",
        "http://",
        "",
    ] {
        let raw = format!("<a href=\"{}\">open</a>", address.replace('"', "&quot;"));
        for content in both(&raw) {
            assert_harmless(&content);
            assert_eq!(texts(&content).concat(), "open", "{address}");
            assert!(links(&content).is_empty(), "{address}");
        }
    }
    for content in both("<a href=\"HTTPS://Example.com/a b?q=1#f\" onclick=\"x()\">open</a>") {
        let [link] = &links(&content)[..] else {
            panic!("one link");
        };
        assert_eq!(link.url, "https://example.com/a%20b?q=1#f");
        assert_eq!(
            (link.host.as_str(), link.kind),
            ("example.com", LinkKind::Web)
        );
        assert!(!link.misleading);
    }
}

#[test]
fn a_link_that_shows_another_address_is_marked() {
    let misleading = |raw: &str| -> bool {
        let content = parse(raw).content;
        assert_harmless(&content);
        links(&content)
            .first()
            .unwrap_or_else(|| panic!("a link: {raw}"))
            .misleading
    };
    assert!(misleading(
        "<a href=\"https://evil.example/\">https://bank.example/login</a>"
    ));
    assert!(misleading(
        "<a href=\"https://evil.example/\">bank.example</a>"
    ));
    assert!(misleading(
        "<a href=\"https://evil.example/\">go to <b>www.bank.example</b> now</a>"
    ));
    // The real host is told, whatever the address pretends to be.
    assert!(misleading(
        "<a href=\"https://bank.example@evil.example/\">bank.example</a>"
    ));
    assert!(!misleading(
        "<a href=\"https://bank.example/login\">bank.example</a>"
    ));
    assert!(!misleading(
        "<a href=\"https://www.bank.example/\">https://bank.example</a>"
    ));
    assert!(!misleading(
        "<a href=\"https://bank.example/\">click here</a>"
    ));
    assert!(!misleading(
        "<a href=\"https://bank.example/\">v1.2 notes</a>"
    ));
    let host = links(&parse("<a href=\"https://bank.example@evil.example/\">x</a>").content)[0]
        .host
        .clone();
    assert_eq!(host, "evil.example");
}

#[test]
fn direction_and_invisible_characters_cannot_disguise_a_link_or_text() {
    // Right-to-left override: "exe.gpj" would read as "jpg.exe".
    let content = parse("photo\u{202E}gpj.exe and \u{2066}x\u{2069}\u{0007}").content;
    assert_eq!(texts(&content), ["photogpj.exe and x"]);
    // Zero-width characters split a host name so it does not look like one.
    let raw = "<a href=\"https://evil.example/\">bank\u{200B}.exa\u{200D}mple\u{202E}</a>";
    let content = parse(raw).content;
    assert_eq!(texts(&content), ["bank.example"]);
    assert!(links(&content)[0].misleading);
    // An international name is told in its ASCII form, so look-alike letters show.
    let link = safe_link("https://bаnk.example/").expect("link");
    assert!(link.host.starts_with("xn--"), "{}", link.host);
    // A joiner inside an emoji is text and stays.
    assert_eq!(texts(&parse("👨\u{200D}👩").content), ["👨\u{200D}👩"]);
}

#[test]
fn addresses_written_out_are_links_too() {
    let content = parse("see https://example.com/cat.gif, ok? javascript:alert(1)").content;
    assert_eq!(
        texts(&content),
        [
            "see ",
            "https://example.com/cat.gif",
            ", ok? javascript:alert(1)"
        ]
    );
    let [link] = &links(&content)[..] else {
        panic!("one link");
    };
    assert_eq!(
        (link.url.as_str(), link.kind),
        ("https://example.com/cat.gif", LinkKind::Gif)
    );
    assert_eq!(
        safe_link("https://example.com/a.PNG?x=1").map(|l| l.kind),
        Some(LinkKind::Image)
    );
    assert_eq!(
        safe_link("https://example.com/a.jpeg").map(|l| l.kind),
        Some(LinkKind::Image)
    );
    assert_eq!(
        safe_link("https://example.com/gif").map(|l| l.kind),
        Some(LinkKind::Web)
    );
    assert!(safe_link(&format!("https://example.com/{}", "a".repeat(3000))).is_none());
}

#[test]
fn images_come_by_reference_and_remote_ones_are_never_loaded_here() {
    let raw = format!(
        "a<img src=\"data:image/png;base64,{PNG}\" onerror=\"alert(1)\">b\
         <img src=\"https://tracker.example/pixel.gif?user=42\">\
         <img src=\"javascript:alert(1)\"><img src=\"data:image/svg+xml;base64,PHN2Zy8+\">\
         <img src=\"data:text/html;base64,PHNjcmlwdD4=\"><img src=\"file:///etc/passwd\">\
         <img src=\"data:image/png;base64,AAAA\"><img>"
    );
    let parsed = parse(&raw);
    assert_harmless(&parsed.content);
    let refs = image_refs(&parsed.content);
    let [
        ImageRef::Embedded {
            image_id,
            mime,
            width,
            height,
            animated,
            ..
        },
        ImageRef::Remote { link },
    ] = &refs[..]
    else {
        panic!("an embedded and a remote image: {refs:?}");
    };
    assert_eq!(
        (mime.as_str(), *width, *height, *animated),
        ("image/png", 1, 1, false)
    );
    assert_eq!(link.url, "https://tracker.example/pixel.gif?user=42");
    assert_eq!(link.kind, LinkKind::Gif);
    // The bytes are kept for `chat_image`; what is no image the apps may decode is dropped.
    let [(kept, data)] = &parsed.images[..] else {
        panic!("one embedded image");
    };
    assert_eq!(kept, image_id);
    assert!(data.bytes.starts_with(b"\x89PNG"));
    assert_eq!(texts(&parsed.content), ["a", "b"]);
}

#[test]
fn nested_and_broken_markup_ends_as_text() {
    for raw in [
        "<b><i><u>hello",
        "</b></i>hello</p></div></blockquote>",
        "<a href=\"https://a.example\"><a href=\"https://b.example\">hello",
        "<b <i>hello",
        "hello<",
        "hello<b",
        "<<<>>>hello",
        "<p><p><p><table><tr><td><li>hello",
        "<blockquote><blockquote><pre><code>hello",
        "hello<!-- <script>alert(1)</script> -->",
        "<![CDATA[x]]>hello",
        "\u{0}hel\u{0}lo",
        "<a href=\"https://a.example\"><img src=\"https://a.example/i.png\">hello</a>",
    ] {
        for content in both(raw) {
            assert_harmless(&content);
            assert!(
                texts(&content).concat().contains("hello"),
                "{raw}: {content:?}"
            );
            assert!(!texts(&content).concat().contains("alert"), "{raw}");
        }
    }
    let deep = format!(
        "{}hello{}",
        "<b><blockquote>".repeat(5000),
        "</blockquote></b>".repeat(5000)
    );
    for content in both(&deep) {
        assert_eq!(texts(&content).concat(), "hello");
    }
}

#[test]
fn blocks_lists_tables_and_code() {
    let content = parse(
        "<h1>Title</h1><p>one<br>two</p><ul><li>a</li><li>b</li></ul><ol><li>x</li><li>y</li></ol>\
         <blockquote>quoted</blockquote><pre data-mumcord-lang=\"rust\"><code>fn main() {\n    1 &lt; 2;\n}\n</code></pre>\
         <table><tr><td>1</td><td>2</td></tr><tr><th>3</th><th>4</th></tr></table><hr>end",
    )
    .content;
    let kinds: Vec<&str> = content
        .blocks
        .iter()
        .map(|block| match block {
            ContentBlock::Paragraph { .. } => "p",
            ContentBlock::Quote { .. } => "q",
            ContentBlock::Code { .. } => "c",
            ContentBlock::Image { .. } => "i",
        })
        .collect();
    assert_eq!(
        kinds,
        ["p", "p", "p", "p", "p", "p", "q", "c", "p", "p", "p"]
    );
    assert!(spans(&content.blocks[0])[0].bold);
    let lines: Vec<String> = content
        .blocks
        .iter()
        .map(|block| match block {
            ContentBlock::Code { text } => text.clone(),
            block => spans(block).iter().map(|s| s.text.as_str()).collect(),
        })
        .collect();
    assert_eq!(
        lines,
        [
            "Title",
            "one\ntwo",
            "• a",
            "• b",
            "1. x",
            "2. y",
            "quoted",
            "fn main() {\n    1 < 2;\n}",
            "1\t2",
            "3\t4",
            "end"
        ]
    );
}

#[test]
fn a_huge_message_is_cut_and_says_so() {
    let content = parse(&"<b>word</b> ".repeat(200_000)).content;
    assert!(content.truncated);
    let shown: usize = texts(&content).iter().map(|t| t.chars().count()).sum();
    assert!(shown <= MAX_TEXT_CHARS, "{shown}");
    assert!(content.blocks.len() <= MAX_BLOCKS);

    let content = parse(&"<p>x</p>".repeat(5000)).content;
    assert!(content.truncated && content.blocks.len() == MAX_BLOCKS);

    let many = format!("<img src=\"data:image/png;base64,{PNG}\">").repeat(40);
    let parsed = parse(&many);
    assert!(parsed.content.truncated);
    assert_eq!(image_refs(&parsed.content).len(), MAX_IMAGES);
    assert_eq!(parsed.images.len(), MAX_IMAGES);

    // One megabyte of one word, and of markup that never closes.
    assert!(parse(&"a".repeat(1 << 20)).content.truncated);
    assert!(parse(&"<a href=\"".repeat(100_000)).content.blocks.len() <= 1);
}

#[test]
fn an_embedded_image_above_the_limits_is_dropped() {
    // A header that claims 60,000 × 60,000 pixels.
    let bomb = "iVBORw0KGgoAAAANSUhEUgAA6mAAAOpgCAIAAAAPsOIV";
    let parsed = parse(&format!("<img src=\"data:image/png;base64,{bomb}\">x"));
    assert!(image_refs(&parsed.content).is_empty() && parsed.images.is_empty());
    assert_eq!(texts(&parsed.content), ["x"]);
}

// The cases of the desktop's `reply.test.ts`, on the bodies its `quoteHtml` produces.
#[test]
fn reads_the_desktop_reply_quote_in_both_forms() {
    let html = format!(
        "{}<b>yes</b>",
        quote_html(&capped("Maya", "hi there"), true)
    );
    assert_eq!(
        html,
        "<blockquote><b>Maya</b>: hi there</blockquote><b>yes</b>"
    );
    let content = parse(&sanitize_html(&html)).content;
    assert_eq!(content.reply, Some(capped("Maya", "hi there")));
    assert_eq!(texts(&content), ["yes"]);
    assert!(spans(&content.blocks[0])[0].bold);

    let content = parse(&sanitize_html("&gt; Maya: hi<br>yes")).content;
    assert_eq!(content.reply, Some(capped("Maya", "hi")));
    assert_eq!(texts(&content), ["yes"]);

    // After a plain-text server turned the line break into a newline.
    let content = parse(&sanitize_html("> Maya: hi there\nyes")).content;
    assert_eq!(content.reply, Some(capped("Maya", "hi there")));
    assert_eq!(texts(&content), ["yes"]);

    let escaped = quote_html(&capped("Maya <3", "a & b"), true);
    let content = parse(&sanitize_html(&format!("{escaped}ok"))).content;
    assert_eq!(content.reply, Some(capped("Maya <3", "a & b")));
}

#[test]
fn ordinary_quotes_and_messages_are_no_replies() {
    for raw in [
        "<blockquote><p><strong>Maya</strong>: hi</p></blockquote>",
        "> just a quote",
        "hello",
        "<blockquote>Maya: hi</blockquote>yes",
        "<blockquote><b>Maya</b> hi</blockquote>yes",
        "x<blockquote><b>Maya</b>: hi</blockquote>yes",
        "> Maya: hi",
        "> : hi\nyes",
    ] {
        assert_eq!(parse(&sanitize_html(raw)).content.reply, None, "{raw}");
    }
    let content = parse("<blockquote>a quote</blockquote>and text").content;
    assert!(matches!(content.blocks[0], ContentBlock::Quote { .. }));
}

#[test]
fn a_quote_is_plain_text_with_capped_lengths() {
    let evil = format!(
        "<blockquote><b>Maya</b>: &lt;script&gt;alert(1)&lt;/script&gt;{}</blockquote>ok",
        "x".repeat(500)
    );
    let content = parse(&sanitize_html(&evil)).content;
    let quote = content.reply.clone().expect("a reply");
    assert!(quote.snippet.starts_with("<script>alert(1)</script>"));
    assert_eq!(quote.snippet.chars().count(), SNIPPET_CHARS + 1);
    assert_eq!(texts(&content), ["ok"]);

    let content = parse(
        "<blockquote><b>M</b>: a <i>b</i> <a href=\"https://x.example\">c</a></blockquote>ok",
    )
    .content;
    assert_eq!(content.reply, Some(capped("M", "a b c")));
    assert!(links(&content).is_empty());
}

#[test]
fn mentions_are_marked_in_text_only() {
    let names = vec!["Jonas".to_owned(), "all".to_owned()];
    let mut content = parse(
        "hey @jonas, <b>@Jonas!</b> <a href=\"https://x.example/@Jonas\">@Jonas</a> <code>@Jonas</code> mail@jonas.example @all",
    )
    .content;
    assert!(mark_mentions(&mut content, &names));
    let marked: Vec<(&str, bool)> = spans(&content.blocks[0])
        .iter()
        .filter(|s| s.mention)
        .map(|s| (s.text.as_str(), s.bold))
        .collect();
    assert_eq!(
        marked,
        [("@jonas", false), ("@Jonas", true), ("@all", false)]
    );
    assert_eq!(
        plain_text(&content),
        "hey @jonas, @Jonas! @Jonas @Jonas mail@jonas.example @all"
    );

    let mut content = parse("hello Jonas").content;
    assert!(!mark_mentions(&mut content, &names));
}

#[test]
fn plain_text_is_one_line_without_the_reply_quote() {
    let content =
        parse("<blockquote><b>Maya</b>: hi</blockquote><p>one<br>two</p><pre>code</pre>").content;
    assert_eq!(plain_text(&content), "one two code");
    assert_eq!(
        plain_text(&parse(&format!("<img src=\"data:image/png;base64,{PNG}\">")).content),
        ""
    );
}
