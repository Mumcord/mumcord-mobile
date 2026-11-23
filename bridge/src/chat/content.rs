// SPDX-License-Identifier: AGPL-3.0-only

//! Turns a message body into the safe content model.
//!
//! The engine sanitises every message on receipt (`mumcord-engine`, `chat/sanitize.rs`: an
//! allow-list of tags and attributes, script and style content removed) and hands it on as
//! HTML for the desktop's web view. Phones do not render HTML. This module reads that HTML
//! token by token and keeps only what the model knows: text, a closed set of styles, checked
//! `http`/`https` links, quotes, code blocks and images by reference. It does not rely on the
//! sanitiser having run: a tag it does not know contributes nothing but its text, an attribute
//! it does not read is never looked at, and no markup is passed on in any form.

use std::cell::RefCell;

use base64::Engine as _;
use html5ever::tendril::StrTendril;
use html5ever::tokenizer::{
    BufferQueue, TagKind, Token, TokenSink, TokenSinkResult, Tokenizer, TokenizerOpts,
};

use super::images;
use super::types::{
    ContentBlock, ImageData, ImageRef, LinkKind, MessageContent, ReplyQuote, SafeLink, Span,
};
use super::wire;

/// Most characters of text one message passes on.
pub(crate) const MAX_TEXT_CHARS: usize = 20_000;
/// Most images one message passes on.
pub(crate) const MAX_IMAGES: usize = 16;
/// Most blocks one message passes on.
const MAX_BLOCKS: usize = 400;
/// Longest address of a link.
const MAX_URL_BYTES: usize = 2048;

/// A message body as the model, with the bytes of its embedded images.
pub(crate) struct Parsed {
    pub(crate) content: MessageContent,
    /// The embedded images by their `image_id`.
    pub(crate) images: Vec<(String, ImageData)>,
}

enum Node {
    Open(String, Vec<(String, String)>),
    Close(String),
    Text(String),
}

#[derive(Default)]
struct Collector(RefCell<Vec<Node>>);

impl TokenSink for Collector {
    type Handle = ();

    fn process_token(&self, token: Token, _line: u64) -> TokenSinkResult<()> {
        let mut nodes = self.0.borrow_mut();
        match token {
            Token::CharacterTokens(text) => match nodes.last_mut() {
                Some(Node::Text(last)) => last.push_str(&text),
                _ => nodes.push(Node::Text(text.to_string())),
            },
            Token::TagToken(tag) => {
                let name = tag.name.to_string();
                match tag.kind {
                    TagKind::StartTag => {
                        let attributes = tag
                            .attrs
                            .iter()
                            .map(|a| (a.name.local.to_string(), a.value.to_string()))
                            .collect();
                        nodes.push(Node::Open(name, attributes));
                    }
                    TagKind::EndTag => nodes.push(Node::Close(name)),
                }
            }
            // Comments, doctypes and null characters carry nothing to show.
            _ => {}
        }
        TokenSinkResult::Continue
    }
}

fn tokenize(html: &str) -> Vec<Node> {
    let tokenizer = Tokenizer::new(Collector::default(), TokenizerOpts::default());
    let input = BufferQueue::default();
    input.push_back(StrTendril::from_slice(html));
    let _ = tokenizer.feed(&input);
    tokenizer.end();
    tokenizer.sink.0.take()
}

/// Characters that change the direction or the order of the text around them, and controls:
/// they can make a text read as something it is not.
fn is_unsafe_char(c: char) -> bool {
    (c.is_control() && c != '\n' && c != '\t')
        || matches!(c, '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}' | '\u{FEFF}')
}

/// Invisible characters, which can hide a difference between two names or addresses.
fn is_invisible_char(c: char) -> bool {
    matches!(
        c,
        '\u{00AD}' | '\u{061C}' | '\u{200B}'..='\u{200F}' | '\u{2060}'..='\u{2064}'
    )
}

/// Text of a message as it may be shown.
pub(crate) fn clean_text(text: &str) -> String {
    text.chars().filter(|&c| !is_unsafe_char(c)).collect()
}

/// A name or the text of a link: one line, nothing invisible.
pub(crate) fn clean_label(text: &str) -> String {
    text.chars()
        .filter(|&c| !is_unsafe_char(c) && !is_invisible_char(c) && c != '\n')
        .collect()
}

fn link_kind(url: &url::Url) -> LinkKind {
    let path = url.path().to_ascii_lowercase();
    if path.ends_with(".gif") {
        LinkKind::Gif
    } else if [".png", ".jpg", ".jpeg", ".webp"]
        .iter()
        .any(|ending| path.ends_with(ending))
    {
        LinkKind::Image
    } else {
        LinkKind::Web
    }
}

/// The link for `address` when it is an absolute `http` or `https` address with a host.
pub(crate) fn safe_link(address: &str) -> Option<SafeLink> {
    let address = address.trim();
    if address.len() > MAX_URL_BYTES {
        return None;
    }
    let url = url::Url::parse(address).ok()?;
    if !matches!(url.scheme(), "http" | "https") {
        return None;
    }
    let host = url.host_str()?.to_owned();
    Some(SafeLink {
        kind: link_kind(&url),
        url: url.into(),
        host,
        misleading: false,
    })
}

fn bare_host(host: &str) -> &str {
    host.strip_prefix("www.").unwrap_or(host)
}

/// Whether the text of a link names another host than the link goes to.
fn is_misleading(text: &str, link: &SafeLink) -> bool {
    clean_label(text).split_whitespace().any(|word| {
        let word = word.trim_matches(|c: char| !c.is_alphanumeric());
        if !word.contains('.') {
            return false;
        }
        let address = if word.contains("://") {
            word.to_owned()
        } else {
            format!("http://{word}")
        };
        url::Url::parse(&address)
            .ok()
            .and_then(|u| u.host_str().map(str::to_owned))
            .is_some_and(|host| {
                host.contains('.') && !bare_host(&host).eq_ignore_ascii_case(bare_host(&link.host))
            })
    })
}

/// `<blockquote><b>Name</b>: snippet</blockquote>` as the very first thing of the message.
fn html_quote(nodes: &mut Vec<Node>) -> Option<ReplyQuote> {
    let is = |node: Option<&Node>, open: bool, tag: &str| match node {
        Some(Node::Open(name, _)) => open && name == tag,
        Some(Node::Close(name)) => !open && name == tag,
        _ => false,
    };
    if !is(nodes.first(), true, "blockquote") || !is(nodes.get(1), true, "b") {
        return None;
    }
    let (name, after) = match nodes.get(2) {
        Some(Node::Text(name)) if is(nodes.get(3), false, "b") => (name.clone(), 4),
        Some(Node::Close(tag)) if tag == "b" => (String::new(), 3),
        _ => return None,
    };
    match nodes.get(after) {
        Some(Node::Text(text)) if text.starts_with(": ") => {}
        _ => return None,
    }
    let mut depth = 1u32;
    let mut quoted = String::new();
    let mut end = None;
    for (index, node) in nodes.iter().enumerate().skip(after) {
        match node {
            Node::Text(text) => quoted.push_str(text),
            Node::Open(tag, _) if tag == "blockquote" => depth += 1,
            Node::Close(tag) if tag == "blockquote" => {
                depth -= 1;
                if depth == 0 {
                    end = Some(index);
                    break;
                }
            }
            _ => {}
        }
    }
    let end = end?;
    nodes.drain(..=end);
    Some(wire::capped(&name, &quoted[2..]))
}

/// `> Name: snippet` as the first line of plain text, ended by a line break.
fn plain_quote(nodes: &mut Vec<Node>) -> Option<ReplyQuote> {
    let Some(Node::Text(first)) = nodes.first() else {
        return None;
    };
    let rest = first.strip_prefix("> ")?;
    let (line, remove, left) = if let Some((line, left)) = rest.split_once('\n') {
        (line.to_owned(), 0, Some(left.to_owned()))
    } else if matches!(nodes.get(1), Some(Node::Open(tag, _)) if tag == "br") {
        (rest.to_owned(), 2, None)
    } else {
        return None;
    };
    let colon = line.find(": ").filter(|&at| at > 0)?;
    let quote = wire::capped(&line[..colon], &line[colon + 2..]);
    match left {
        Some(left) => nodes[0] = Node::Text(left),
        None => drop(nodes.drain(..remove)),
    }
    Some(quote)
}

#[derive(Default, Clone, Copy)]
struct Style {
    bold: u32,
    italic: u32,
    underline: u32,
    strikethrough: u32,
    code: u32,
}

struct Builder {
    blocks: Vec<ContentBlock>,
    spans: Vec<Span>,
    images: Vec<(String, ImageData)>,
    image_count: usize,
    style: Style,
    /// The open link and the index of its first span.
    link: Option<(SafeLink, usize)>,
    quote: u32,
    pre: u32,
    code: String,
    /// Inside `script` or `style`: their text is never content.
    skip: u32,
    /// The open lists; a number for a numbered one.
    lists: Vec<Option<u32>>,
    chars_left: usize,
    truncated: bool,
}

impl Builder {
    fn new() -> Self {
        Self {
            blocks: Vec::new(),
            spans: Vec::new(),
            images: Vec::new(),
            image_count: 0,
            style: Style::default(),
            link: None,
            quote: 0,
            pre: 0,
            code: String::new(),
            skip: 0,
            lists: Vec::new(),
            chars_left: MAX_TEXT_CHARS,
            truncated: false,
        }
    }

    fn push_block(&mut self, block: ContentBlock) {
        if self.blocks.len() < MAX_BLOCKS {
            self.blocks.push(block);
        } else {
            self.truncated = true;
        }
    }

    /// Ends the paragraph that is being collected.
    fn flush(&mut self) {
        self.close_link();
        let mut spans = std::mem::take(&mut self.spans);
        while spans.last().is_some_and(|s| s.text.trim().is_empty()) {
            spans.pop();
        }
        let lead = spans
            .iter()
            .take_while(|s| s.text.trim().is_empty())
            .count();
        spans.drain(..lead);
        if let Some(first) = spans.first_mut() {
            first.text = first.text.trim_start().to_owned();
        }
        if let Some(last) = spans.last_mut() {
            last.text = last.text.trim_end().to_owned();
        }
        if spans.is_empty() {
            return;
        }
        self.push_block(if self.quote > 0 {
            ContentBlock::Quote { spans }
        } else {
            ContentBlock::Paragraph { spans }
        });
    }

    fn span(&self, text: String, link: Option<SafeLink>) -> Span {
        Span {
            text,
            bold: self.style.bold > 0,
            italic: self.style.italic > 0,
            underline: self.style.underline > 0,
            strikethrough: self.style.strikethrough > 0,
            code: self.style.code > 0,
            link,
            mention: false,
        }
    }

    /// Cuts `text` to what the message may still pass on.
    fn budget(&mut self, text: &str) -> String {
        let cleaned = clean_text(text);
        let count = cleaned.chars().count();
        if count <= self.chars_left {
            self.chars_left -= count;
            return cleaned;
        }
        self.truncated = true;
        let kept: String = cleaned.chars().take(self.chars_left).collect();
        self.chars_left = 0;
        kept
    }

    fn text(&mut self, text: &str) {
        if self.skip > 0 {
            return;
        }
        let text = self.budget(text);
        if text.is_empty() {
            return;
        }
        if self.pre > 0 {
            self.code.push_str(&text);
            return;
        }
        if let Some((link, _)) = &self.link {
            let span = self.span(text, Some(link.clone()));
            self.spans.push(span);
            return;
        }
        if self.style.code > 0 {
            let span = self.span(text, None);
            self.spans.push(span);
            return;
        }
        // Addresses written out in the text are links too, as on desktop.
        let mut at = 0;
        for found in wire::find_urls(&text) {
            let Some(link) = safe_link(&text[found.range.clone()]) else {
                continue;
            };
            if found.range.start > at {
                let span = self.span(text[at..found.range.start].to_owned(), None);
                self.spans.push(span);
            }
            let span = self.span(text[found.range.clone()].to_owned(), Some(link));
            self.spans.push(span);
            at = found.range.end;
        }
        if at < text.len() {
            let span = self.span(text[at..].to_owned(), None);
            self.spans.push(span);
        }
    }

    fn close_link(&mut self) {
        let Some((link, first)) = self.link.take() else {
            return;
        };
        let shown: String = self.spans[first..]
            .iter()
            .map(|s| s.text.as_str())
            .collect();
        let misleading = is_misleading(&shown, &link);
        for span in &mut self.spans[first..] {
            span.text = clean_label(&span.text);
            if let Some(link) = &mut span.link {
                link.misleading = misleading;
            }
        }
    }

    fn image(&mut self, source: &str) {
        if self.image_count >= MAX_IMAGES {
            self.truncated = true;
            return;
        }
        let source = source.trim();
        let image = if let Some(link) = safe_link(source) {
            // Named an image by the message, whatever the address ends in.
            let kind = match link.kind {
                LinkKind::Gif => LinkKind::Gif,
                _ => LinkKind::Image,
            };
            ImageRef::Remote {
                link: SafeLink { kind, ..link },
            }
        } else if let Some(data) = embedded_image(source) {
            let image_id = self.image_count.to_string();
            let image = ImageRef::Embedded {
                image_id: image_id.clone(),
                mime: data.mime.clone(),
                width: data.width,
                height: data.height,
                byte_len: u32::try_from(data.bytes.len()).unwrap_or(u32::MAX),
                animated: data.animated,
            };
            self.images.push((image_id, data));
            image
        } else {
            return;
        };
        self.flush();
        self.image_count += 1;
        self.push_block(ContentBlock::Image { image });
    }

    fn open(&mut self, tag: &str, attributes: &[(String, String)]) {
        let attribute = |name: &str| {
            attributes
                .iter()
                .find(|(n, _)| n == name)
                .map(|(_, v)| v.as_str())
        };
        match tag {
            "script" | "style" => self.skip += 1,
            _ if self.skip > 0 => {}
            "b" | "strong" => self.style.bold += 1,
            "i" | "em" => self.style.italic += 1,
            "u" => self.style.underline += 1,
            "s" | "strike" | "del" => self.style.strikethrough += 1,
            "code" => self.style.code += 1,
            "br" => self.text("\n"),
            "a" => {
                self.close_link();
                if let Some(link) = attribute("href").and_then(safe_link) {
                    self.link = Some((link, self.spans.len()));
                }
            }
            "img" => {
                if let Some(source) = attribute("src") {
                    self.image(source);
                }
            }
            "blockquote" => {
                self.flush();
                self.quote += 1;
            }
            "pre" => {
                self.flush();
                self.pre += 1;
            }
            "ul" | "ol" => {
                self.flush();
                self.lists.push((tag == "ol").then_some(1));
            }
            "li" => {
                self.flush();
                let marker = match self.lists.last_mut() {
                    Some(Some(number)) => {
                        *number += 1;
                        format!("{}. ", *number - 1)
                    }
                    _ => "• ".to_owned(),
                };
                self.text(&marker);
            }
            "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                self.flush();
                self.style.bold += 1;
            }
            "td" | "th" => {
                if !self.spans.is_empty() {
                    self.text("\t");
                }
            }
            "p" | "div" | "table" | "tr" | "hr" => self.flush(),
            // Anything else is no markup the model knows: only its text counts.
            _ => {}
        }
    }

    fn close(&mut self, tag: &str) {
        let less = |count: &mut u32| *count = count.saturating_sub(1);
        match tag {
            "script" | "style" => less(&mut self.skip),
            _ if self.skip > 0 => {}
            "b" | "strong" => less(&mut self.style.bold),
            "i" | "em" => less(&mut self.style.italic),
            "u" => less(&mut self.style.underline),
            "s" | "strike" | "del" => less(&mut self.style.strikethrough),
            "code" => less(&mut self.style.code),
            "a" => self.close_link(),
            "blockquote" => {
                self.flush();
                less(&mut self.quote);
            }
            "pre" => {
                less(&mut self.pre);
                if self.pre == 0 {
                    let text = std::mem::take(&mut self.code);
                    let text = text.trim_matches('\n');
                    if !text.is_empty() {
                        self.push_block(ContentBlock::Code {
                            text: text.to_owned(),
                        });
                    }
                }
            }
            "ul" | "ol" => {
                self.flush();
                self.lists.pop();
            }
            "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                self.flush();
                less(&mut self.style.bold);
            }
            "p" | "div" | "table" | "tr" | "li" => self.flush(),
            _ => {}
        }
    }

    fn finish(mut self, reply: Option<ReplyQuote>) -> Parsed {
        self.flush();
        // A code block that was never closed is still code.
        if !self.code.trim().is_empty() {
            let text = self.code.trim_matches('\n').to_owned();
            self.push_block(ContentBlock::Code { text });
        }
        Parsed {
            content: MessageContent {
                reply,
                blocks: self.blocks,
                truncated: self.truncated,
            },
            images: self.images,
        }
    }
}

/// The image inside a `data:image/…;base64,` address, when it is one the apps may decode.
fn embedded_image(source: &str) -> Option<ImageData> {
    let rest = source
        .get(..11)
        .filter(|p| p.eq_ignore_ascii_case("data:image/"))
        .map(|_| &source[11..])?;
    let (_, payload) = rest.split_once(";base64,").or_else(|| {
        // The marker in any spelling.
        let at = rest.to_ascii_lowercase().find(";base64,")?;
        Some((&rest[..at], &rest[at + 8..]))
    })?;
    if payload.len() / 4 * 3 > images::MAX_IMAGE_BYTES + 3 {
        return None;
    }
    let compact: String = payload.chars().filter(|c| !c.is_whitespace()).collect();
    let bytes = base64::engine::general_purpose::STANDARD_NO_PAD
        .decode(compact.trim_end_matches('='))
        .ok()?;
    images::inspect(bytes).ok()
}

/// The content of a sanitised message body.
pub(crate) fn parse(html: &str) -> Parsed {
    let mut nodes = tokenize(html);
    // As on desktop: only a body that starts with a quote can be a reply.
    let reply = if html.starts_with("<blockquote>") {
        html_quote(&mut nodes)
    } else if html.starts_with("> ") || html.starts_with("&gt; ") {
        plain_quote(&mut nodes)
    } else {
        None
    };
    let mut builder = Builder::new();
    for node in &nodes {
        match node {
            Node::Open(tag, attributes) => builder.open(tag, attributes),
            Node::Close(tag) => builder.close(tag),
            Node::Text(text) => builder.text(text),
        }
    }
    builder.finish(reply)
}

/// The visible text of the content without its reply quote, collapsed to one line.
pub(crate) fn plain_text(content: &MessageContent) -> String {
    let mut text = String::new();
    for block in &content.blocks {
        match block {
            ContentBlock::Paragraph { spans } | ContentBlock::Quote { spans } => {
                for span in spans {
                    text.push_str(&span.text);
                }
            }
            ContentBlock::Code { text: code } => text.push_str(code),
            ContentBlock::Image { .. } => {}
        }
        text.push(' ');
    }
    wire::collapse(&text)
}

/// Marks where the content mentions one of `names` (`@name`); true when it does.
pub(crate) fn mark_mentions(content: &mut MessageContent, names: &[String]) -> bool {
    let mut any = false;
    for block in &mut content.blocks {
        let (ContentBlock::Paragraph { spans } | ContentBlock::Quote { spans }) = block else {
            continue;
        };
        let mut marked = Vec::with_capacity(spans.len());
        for span in spans.drain(..) {
            let ranges = if span.link.is_some() || span.code {
                Vec::new()
            } else {
                wire::mention_ranges(&span.text, names)
            };
            if ranges.is_empty() {
                marked.push(span);
                continue;
            }
            any = true;
            let mut at = 0;
            let piece = |range: std::ops::Range<usize>, mention: bool| Span {
                text: span.text[range].to_owned(),
                mention,
                ..span.clone()
            };
            for range in ranges {
                if range.start > at {
                    marked.push(piece(at..range.start, false));
                }
                at = range.end;
                marked.push(piece(range, true));
            }
            if at < span.text.len() {
                marked.push(piece(at..span.text.len(), false));
            }
        }
        *spans = marked;
    }
    any
}

#[cfg(test)]
mod tests;
