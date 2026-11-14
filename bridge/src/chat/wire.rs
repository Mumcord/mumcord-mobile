// SPDX-License-Identifier: AGPL-3.0-only

//! The chat conventions Mumcord shares with its desktop app: how a reply, a mention and typed
//! text look inside a Mumble text message, so both apps read each other's messages.
//!
//! The engine does not have these rules yet; the desktop app keeps them in its UI
//! (`ui/src/features/chat/reply.ts`, `mentions.ts`, `ownMention.ts`, `markdown.ts`). This file
//! is a port of exactly those rules, with the desktop's test cases, until they move into the
//! engine.
//!
//! # Markdown
//!
//! Typed text is Markdown, as on the desktop ([`markdown_to_html`]). The desktop parses it with
//! the JavaScript library `marked` (GitHub flavour, line breaks kept), the bridge with
//! `pulldown-cmark`; both follow CommonMark, and the desktop's own rules are ported on top:
//! only `> ` with a space starts a quote, typed HTML is shown as text, a fenced code block
//! carries its language as `data-mumcord-lang`, a message of one paragraph has no `<p>` around
//! it, and the result goes through the engine's sanitiser. Known differences to the desktop:
//!
//! - Tables and task lists (`- [ ]`) are not turned into markup; they stay text.
//! - A bare web address becomes a link only when it starts with `http://` or `https://`;
//!   `marked` also links `www.` addresses and e-mail addresses.
//! - A bare web address that contains Markdown characters (`*`, `~~`) is read as Markdown
//!   first; `marked` takes the address first.
//! - A link carries `rel="noopener noreferrer nofollow"` (the engine's sanitiser) instead of
//!   the desktop's `rel="noopener noreferrer"`; receiving clients set their own.
//! - Where `marked` departs from CommonMark in details (emphasis next to punctuation, nested
//!   lists with unusual indentation), the bridge follows CommonMark.

use std::ops::Range;

use mumcord_engine::chat::sanitize_message_html;

use icu_normalizer::DecomposingNormalizerBorrowed;
use icu_properties::CodePointMapData;
use icu_properties::props::{GeneralCategory, GeneralCategoryGroup};

use super::types::{MentionCandidate, ReplyQuote};

/// Characters of the original a reply quotes.
pub(crate) const SNIPPET_CHARS: usize = 100;
/// Longest sender name kept from a quote; Mumble names are much shorter.
const NAME_CHARS: usize = 64;
const ELLIPSIS: char = '…';
/// Most suggestions of the `@` autocomplete.
pub(crate) const MAX_SUGGESTIONS: u32 = 8;
/// `@channel` for everyone in the channel, `@all` for everyone who gets its messages.
pub(crate) const GROUP_MENTIONS: [&str; 2] = ["channel", "all"];

/// Runs of whitespace become one space; none at the ends.
pub(crate) fn collapse(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn first_chars(text: &str, count: usize) -> String {
    text.chars().take(count).collect()
}

/// A quote with the lengths every reader caps it to.
pub(crate) fn capped(name: &str, snippet: &str) -> ReplyQuote {
    ReplyQuote {
        name: first_chars(&collapse(name), NAME_CHARS),
        snippet: first_chars(&collapse(snippet), SNIPPET_CHARS + 1),
    }
}

/// Shortens a message's text to what a reply quotes.
pub(crate) fn snippet(text: &str) -> String {
    let flat = collapse(text);
    if flat.chars().count() > SNIPPET_CHARS {
        format!("{}{ELLIPSIS}", first_chars(&flat, SNIPPET_CHARS).trim_end())
    } else {
        flat
    }
}

pub(crate) fn escape_html(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            c => out.push(c),
        }
    }
    out
}

/// The quote that starts a reply message, readable in every Mumble client:
/// `<blockquote><b>Name</b>: snippet</blockquote>`, or `> Name: snippet` and a line break on
/// servers that show plain text.
pub(crate) fn quote_html(quote: &ReplyQuote, allow_html: bool) -> String {
    let name = escape_html(&quote.name);
    let snippet = escape_html(&quote.snippet);
    if allow_html {
        format!("<blockquote><b>{name}</b>: {snippet}</blockquote>")
    } else {
        format!("&gt; {name}: {snippet}<br>")
    }
}

/// A web address inside plain text: where it is and how it is written.
pub(crate) struct FoundUrl {
    pub(crate) range: Range<usize>,
}

/// The `http://` and `https://` addresses written out in `text`. An address starts at a word
/// start and ends before whitespace, quotes and angle brackets; punctuation that ends the
/// sentence is not part of it.
pub(crate) fn find_urls(text: &str) -> Vec<FoundUrl> {
    let mut found = Vec::new();
    let lower = text.to_ascii_lowercase();
    let mut from = 0;
    while let Some(at) = lower[from..].find("http").map(|i| i + from) {
        let rest = &lower[at..];
        let scheme = if rest.starts_with("https://") {
            8
        } else if rest.starts_with("http://") {
            7
        } else {
            from = at + 4;
            continue;
        };
        let glued = text[..at]
            .chars()
            .next_back()
            .is_some_and(|c| c.is_alphanumeric());
        let len = text[at..]
            .find(|c: char| c.is_whitespace() || c.is_control() || "<>\"".contains(c))
            .unwrap_or(text.len() - at);
        let mut end = at + len;
        while let Some(last) = text[at..end].chars().next_back() {
            let open = text[at..end].matches('(').count();
            let close = text[at..end].matches(')').count();
            if ".,;:!?'".contains(last) || (last == ')' && close > open) {
                end -= last.len_utf8();
            } else {
                break;
            }
        }
        if !glued && end > at + scheme {
            found.push(FoundUrl { range: at..end });
        }
        from = (at + len).max(at + 4);
    }
    found
}

/// Plain text as HTML: escaped, with the written-out web addresses as links.
fn linked_text(text: &str, out: &mut String) {
    let mut at = 0;
    for url in find_urls(text) {
        out.push_str(&escape_html(&text[at..url.range.start]));
        let address = &text[url.range.clone()];
        match url::Url::parse(address) {
            Ok(parsed) if parsed.host().is_some() => {
                out.push_str(&format!(
                    "<a href=\"{}\">{}</a>",
                    escape_html(parsed.as_str()),
                    escape_html(address)
                ));
            }
            _ => out.push_str(&escape_html(address)),
        }
        at = url.range.end;
    }
    out.push_str(&escape_html(&text[at..]));
}

/// A web address as the body of a message (a GIF favourite): a link where the server shows
/// HTML. Never read as Markdown, which could take characters of the address for markup.
pub(crate) fn address_to_html(address: &str, allow_html: bool) -> String {
    if !allow_html {
        return escape_html(address);
    }
    let mut html = String::new();
    linked_text(address, &mut html);
    html
}

/// A language name as typed after a code fence: `sql`, `c++`, `objective-c`; nothing else.
fn code_language(info: &str) -> Option<String> {
    let language = info.split_whitespace().next()?.to_lowercase();
    let mut chars = language.chars();
    let first = chars.next()?;
    let valid = (first.is_ascii_lowercase() || first.is_ascii_digit())
        && language.len() <= 32
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "+#._-".contains(c));
    valid.then_some(language)
}

/// How many `> ` a line starts with, and the rest of the line after them.
fn quote_depth(line: &str) -> (usize, &str) {
    let mut depth = 0;
    let mut rest = line;
    while let Some(inner) = rest.strip_prefix("> ") {
        depth += 1;
        rest = inner;
    }
    (depth, rest)
}

/// Rewrites the typed text so that CommonMark reads quotes the way the desktop does: only
/// `> ` with a space starts a quote (`>_>`, `>.<` and `>hello` stay text), and a quote ends
/// with its last `> ` line instead of taking in the line after it.
fn desktop_quotes(source: &str) -> String {
    let mut out = String::with_capacity(source.len() + 8);
    let mut fenced = false;
    // Depth of the line before, when that line had text.
    let mut open_depth = 0;
    for line in source.lines() {
        let (depth, rest) = quote_depth(line);
        let text = rest.trim_start_matches(' ');
        let indent = rest.len() - text.len();
        if text.starts_with("```") || text.starts_with("~~~") {
            fenced = !fenced;
        }
        let blank = text.is_empty();
        if !blank && depth < open_depth {
            // Without the empty line the quote would go on ("lazy continuation").
            out.push_str("> ".repeat(depth).trim_end());
            out.push('\n');
        }
        out.push_str(&line[..line.len() - rest.len()]);
        // Four spaces or more are a code block, where a backslash would be shown.
        if !fenced && indent < 4 && text.starts_with('>') {
            out.push_str(&rest[..indent]);
            out.push('\\');
            out.push_str(text);
        } else {
            out.push_str(rest);
        }
        out.push('\n');
        open_depth = if blank { 0 } else { depth };
    }
    out
}

/// Writes the HTML of parsed Markdown: plain tags an official Mumble client renders, `<br>`
/// for every line break and nothing between the blocks (Qt's rich text would show a line
/// break there as a space).
fn write_html<'a>(events: impl Iterator<Item = pulldown_cmark::Event<'a>>) -> String {
    use pulldown_cmark::{CodeBlockKind, Event, Tag, TagEnd};
    let mut out = String::new();
    // Text inside a link is not searched for web addresses, text inside code is collected.
    let mut links = 0;
    let mut code: Option<String> = None;
    let mut image_alt: Option<String> = None;
    for event in events {
        if let Some(alt) = &mut image_alt {
            match event {
                Event::Text(text) | Event::Code(text) => alt.push_str(&text),
                Event::SoftBreak | Event::HardBreak => alt.push(' '),
                Event::End(TagEnd::Image) => {
                    out.push_str(&format!(" alt=\"{}\">", escape_html(alt)));
                    image_alt = None;
                }
                _ => {}
            }
            continue;
        }
        match event {
            Event::Text(text) => match &mut code {
                Some(code) => code.push_str(&text),
                None if links > 0 => out.push_str(&escape_html(&text)),
                None => linked_text(&text, &mut out),
            },
            Event::Code(text) => out.push_str(&format!("<code>{}</code>", escape_html(&text))),
            // Typed HTML is shown, never interpreted.
            Event::Html(html) | Event::InlineHtml(html) => out.push_str(&escape_html(&html)),
            Event::SoftBreak | Event::HardBreak => out.push_str("<br>"),
            Event::Rule => out.push_str("<hr>"),
            Event::Start(tag) => match tag {
                Tag::Paragraph => out.push_str("<p>"),
                Tag::Heading { level, .. } => out.push_str(&format!("<{level}>")),
                Tag::BlockQuote(_) => out.push_str("<blockquote>"),
                Tag::CodeBlock(kind) => {
                    let language = match &kind {
                        CodeBlockKind::Fenced(info) => code_language(info),
                        CodeBlockKind::Indented => None,
                    };
                    match language {
                        // Official clients ignore the attribute.
                        Some(language) => out.push_str(&format!(
                            "<pre data-mumcord-lang=\"{}\"><code>",
                            escape_html(&language)
                        )),
                        None => out.push_str("<pre><code>"),
                    }
                    code = Some(String::new());
                }
                Tag::List(Some(_)) => out.push_str("<ol>"),
                Tag::List(None) => out.push_str("<ul>"),
                Tag::Item => out.push_str("<li>"),
                Tag::Emphasis => out.push_str("<em>"),
                Tag::Strong => out.push_str("<strong>"),
                Tag::Strikethrough => out.push_str("<del>"),
                Tag::Link { dest_url, .. } => {
                    links += 1;
                    out.push_str(&format!("<a href=\"{}\">", escape_html(&dest_url)));
                }
                Tag::Image { dest_url, .. } => {
                    out.push_str(&format!("<img src=\"{}\"", escape_html(&dest_url)));
                    image_alt = Some(String::new());
                }
                // Not switched on in the parser.
                _ => {}
            },
            Event::End(tag) => match tag {
                TagEnd::Paragraph => out.push_str("</p>"),
                TagEnd::Heading(level) => out.push_str(&format!("</{level}>")),
                TagEnd::BlockQuote(_) => out.push_str("</blockquote>"),
                TagEnd::CodeBlock => {
                    let text = code.take().unwrap_or_default();
                    let text = text.strip_suffix('\n').unwrap_or(&text);
                    out.push_str(&format!("{}\n</code></pre>", escape_html(text)));
                }
                TagEnd::List(true) => out.push_str("</ol>"),
                TagEnd::List(false) => out.push_str("</ul>"),
                TagEnd::Item => out.push_str("</li>"),
                TagEnd::Emphasis => out.push_str("</em>"),
                TagEnd::Strong => out.push_str("</strong>"),
                TagEnd::Strikethrough => out.push_str("</del>"),
                TagEnd::Link => {
                    links -= 1;
                    out.push_str("</a>");
                }
                _ => {}
            },
            _ => {}
        }
    }
    out
}

/// What the user typed (Markdown shortcuts) as the HTML of a Mumble text message, exactly as
/// the desktop app converts it: plain inline tags, `<br>` line breaks, no wrapping paragraph
/// for a message of one paragraph. HTML typed by the user is shown as text, never
/// interpreted, and the result is sanitised like a received message. Empty input gives an
/// empty string.
pub(crate) fn markdown_to_html(source: &str) -> String {
    let source = source.trim();
    if source.is_empty() {
        return String::new();
    }
    let source = desktop_quotes(source);
    let parser =
        pulldown_cmark::Parser::new_ext(&source, pulldown_cmark::Options::ENABLE_STRIKETHROUGH);
    let html = write_html(pulldown_cmark::TextMergeStream::new(parser));
    let html = html.trim();
    let single = html
        .strip_prefix("<p>")
        .and_then(|rest| rest.strip_suffix("</p>"))
        .filter(|inner| !inner.contains("<p>"));
    sanitize_message_html(single.unwrap_or(html))
}

/// What the user typed as the body of a message: Markdown as HTML where the server shows
/// HTML, the escaped text where it shows plain text only.
pub(crate) fn typed_to_html(text: &str, allow_html: bool) -> String {
    if allow_html {
        markdown_to_html(text)
    } else {
        escape_html(text.trim())
    }
}

/// One character lower-cased and without diacritics, so "jose" finds "José".
fn fold_char(c: char, out: &mut Vec<char>) {
    if c.is_ascii() {
        out.push(c.to_ascii_lowercase());
        return;
    }
    let marks = CodePointMapData::<GeneralCategory>::new();
    let mut buffer = [0u8; 4];
    let decomposed = DecomposingNormalizerBorrowed::new_nfd().normalize(c.encode_utf8(&mut buffer));
    for part in decomposed.chars() {
        if !GeneralCategoryGroup::Mark.contains(marks.get(part)) {
            out.extend(part.to_lowercase());
        }
    }
}

pub(crate) fn fold_name(text: &str) -> Vec<char> {
    let mut out = Vec::with_capacity(text.len());
    for c in text.chars() {
        fold_char(c, &mut out);
    }
    out
}

/// `text` folded, and for every folded character the byte position of its source character.
fn fold_with_positions(text: &str) -> (Vec<char>, Vec<usize>) {
    let mut folded = Vec::with_capacity(text.len());
    let mut positions = Vec::with_capacity(text.len());
    for (at, c) in text.char_indices() {
        fold_char(c, &mut folded);
        positions.resize(folded.len(), at);
    }
    (folded, positions)
}

fn is_word(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

/// Whether `haystack[from..end]` (an `@name`) is a whole-word mention: not glued to a word
/// before it (an email address is no mention) and not continuing into a longer name.
fn is_whole_mention(haystack: &[char], from: usize, end: usize) -> bool {
    if from > 0 && is_word(haystack[from - 1]) {
        return false;
    }
    let Some(&next) = haystack.get(end) else {
        return true;
    };
    if is_word(next) {
        return false;
    }
    // "@Bob.com" is a web name, "@Bob." at a sentence end is a mention.
    !((next == '.' || next == '-') && haystack.get(end + 1).is_some_and(|&c| is_word(c)))
}

/// Where `text` mentions one of `names` as `@name` (case and diacritics ignored), as byte
/// ranges of `text` in order, without overlaps.
pub(crate) fn mention_ranges(text: &str, names: &[String]) -> Vec<Range<usize>> {
    if !text.contains('@') {
        return Vec::new();
    }
    let (haystack, positions) = fold_with_positions(text);
    let mut ranges: Vec<Range<usize>> = Vec::new();
    for name in names {
        let mut needle = vec!['@'];
        needle.extend(fold_name(name.trim()));
        if needle.len() < 2 || needle.len() > haystack.len() {
            continue;
        }
        for from in 0..=haystack.len() - needle.len() {
            let end = from + needle.len();
            if haystack[from..end] == needle[..] && is_whole_mention(&haystack, from, end) {
                let stop = positions.get(end).copied().unwrap_or(text.len());
                ranges.push(positions[from]..stop);
            }
        }
    }
    ranges.sort_by_key(|range| (range.start, std::cmp::Reverse(range.end)));
    let mut last = 0;
    ranges.retain(|range| {
        let keep = range.start >= last && range.end > range.start;
        if keep {
            last = range.end;
        }
        keep
    });
    ranges
}

/// A person the `@` autocomplete may suggest.
pub(crate) struct MentionPerson<'a> {
    pub(crate) session: u32,
    pub(crate) name: &'a str,
    pub(crate) channel_id: u32,
    /// A bot without voice; never suggested.
    pub(crate) hidden: bool,
}

/// The suggestions for what was typed after the `@`: people whose name starts with it (case
/// and diacritics ignored), those in the own channel first, each group alphabetical, never the
/// user themself; then the group mentions whose keyword starts with it.
pub(crate) fn mention_candidates(
    people: &[MentionPerson<'_>],
    query: &str,
    own_channel: Option<u32>,
    own_session: Option<u32>,
    limit: u32,
) -> Vec<MentionCandidate> {
    let wanted = fold_name(query);
    let mut matches: Vec<(bool, Vec<char>, &MentionPerson<'_>)> = people
        .iter()
        .filter(|p| Some(p.session) != own_session && !p.name.is_empty() && !p.hidden)
        .filter_map(|p| {
            let folded = fold_name(p.name);
            folded
                .starts_with(&wanted)
                .then(|| (Some(p.channel_id) == own_channel, folded, p))
        })
        .collect();
    matches.sort_by(|a, b| {
        (b.0.cmp(&a.0))
            .then_with(|| a.1.cmp(&b.1))
            .then_with(|| a.2.session.cmp(&b.2.session))
    });
    let people = matches.into_iter().map(|(_, _, p)| MentionCandidate {
        session: Some(p.session),
        name: p.name.to_owned(),
    });
    let groups = GROUP_MENTIONS
        .iter()
        .filter(|group| fold_name(group).starts_with(&wanted))
        .map(|group| MentionCandidate {
            session: None,
            name: (*group).to_owned(),
        });
    people
        .chain(groups)
        .take(limit.clamp(1, MAX_SUGGESTIONS) as usize)
        .collect()
}

/// A message as the reply rules look at it.
pub(crate) struct Candidate<'a> {
    pub(crate) id: &'a str,
    pub(crate) own: bool,
    pub(crate) sender_name: &'a str,
    /// The visible text without the message's own reply quote, collapsed.
    pub(crate) text: &'a str,
}

/// The start of the original's text a quote shows, without the ellipsis of a shortened snippet.
fn quoted_text(quote: &ReplyQuote) -> String {
    collapse(
        quote
            .snippet
            .strip_suffix(ELLIPSIS)
            .unwrap_or(&quote.snippet),
    )
}

/// Whether a message of someone else with the reply `quote` replies to one of the user's own
/// messages: the quote names the user by `own_name`, their current name. When the quoted
/// original is in `history` (it starts with the snippet and is the user's own or was sent under
/// that name), it must be one of the user's own; someone else who had the name earlier does not
/// count. Without the original the name decides.
pub(crate) fn replies_to_own(
    message_id: &str,
    quote: &ReplyQuote,
    own_name: &str,
    history: &[Candidate<'_>],
) -> bool {
    if quote.name != first_chars(&collapse(own_name), NAME_CHARS) {
        return false;
    }
    let wanted = quoted_text(quote);
    if wanted.is_empty() {
        return true;
    }
    let mut others_original = false;
    for candidate in history {
        if candidate.id == message_id {
            continue;
        }
        if !candidate.own && first_chars(&collapse(candidate.sender_name), NAME_CHARS) != quote.name
        {
            continue;
        }
        if !candidate.text.starts_with(&wanted) {
            continue;
        }
        if candidate.own {
            return true;
        }
        others_original = true;
    }
    !others_original
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(list: &[&str]) -> Vec<String> {
        list.iter().map(|n| (*n).to_owned()).collect()
    }

    fn mentions(text: &str, name: &str) -> bool {
        !mention_ranges(text, &names(&[name])).is_empty()
    }

    // The cases of the desktop's `reply.test.ts`.
    #[test]
    fn builds_a_readable_quote_for_html_and_plain_text_servers() {
        let quote = capped("Maya <3", "a & b");
        assert_eq!(
            quote_html(&quote, true),
            "<blockquote><b>Maya &lt;3</b>: a &amp; b</blockquote>"
        );
        assert_eq!(quote_html(&quote, false), "&gt; Maya &lt;3: a &amp; b<br>");
    }

    #[test]
    fn quotes_the_first_characters_of_the_text() {
        assert_eq!(
            snippet(&"a".repeat(150)),
            format!("{}…", "a".repeat(SNIPPET_CHARS))
        );
        assert_eq!(snippet("  two\n words "), "two words");
        let long = capped(&"n".repeat(100), &"s".repeat(300));
        assert_eq!(long.name.chars().count(), 64);
        assert_eq!(long.snippet.chars().count(), SNIPPET_CHARS + 1);
    }

    fn reply(name: &str, text: &str) -> ReplyQuote {
        capped(name, &snippet(text))
    }

    fn own<'a>(id: &'a str, text: &'a str) -> Candidate<'a> {
        Candidate {
            id,
            own: true,
            sender_name: "Me",
            text,
        }
    }

    #[test]
    fn a_reply_to_an_own_message_counts() {
        let history = [own("1", "lunch at noon?")];
        assert!(replies_to_own(
            "r",
            &reply("Me", "lunch at noon?"),
            "Me",
            &history
        ));
        // By the name alone when the original is not loaded.
        assert!(replies_to_own("r", &reply("Me", "old news"), "Me", &[]));
        // A quote of someone else.
        assert!(!replies_to_own(
            "r",
            &reply("Maya", "lunch at noon?"),
            "Me",
            &history
        ));
        // A loaded original someone else sent under the user's name.
        let other = [Candidate {
            id: "1",
            own: false,
            sender_name: "Me",
            text: "hello",
        }];
        assert!(!replies_to_own("r", &reply("Me", "hello"), "Me", &other));
        // A shortened snippet still finds its original.
        let long = "x".repeat(150);
        assert!(replies_to_own(
            "r",
            &reply("Me", &long),
            "Me",
            &[own("1", &long)]
        ));
    }

    // The cases of the desktop's `mentions.test.ts`.
    #[test]
    fn a_mention_is_a_whole_word_after_an_at_sign() {
        assert!(mentions("hi @Bob", "Bob"));
        assert!(mentions("@bob: hi", "Bob"));
        assert!(mentions("ask @Bob.", "Bob"));
        assert!(mentions("@josé!", "Jose"));
        assert!(mentions("@JOSE", "José"));
        assert!(mentions("hey @Anna Lena, hi", "Anna Lena"));
        assert!(!mentions("mail bob@Bob.com", "Bob"));
        assert!(!mentions("@Bobby", "Bob"));
        assert!(!mentions("@Bob.com", "Bob"));
        assert!(!mentions("@Bob-ross", "Bob"));
        assert!(!mentions("Bob", "Bob"));
        assert!(!mentions("@", ""));
        assert!(!mentions("@ ", " "));
    }

    #[test]
    fn mention_ranges_name_the_bytes_of_the_original_text() {
        let text = "é @José, and @all";
        let ranges = mention_ranges(text, &names(&["jose", "all"]));
        let found: Vec<&str> = ranges.iter().map(|r| &text[r.clone()]).collect();
        assert_eq!(found, ["@José", "@all"]);
        // The longer of two names that start alike wins; nothing overlaps.
        let text = "@Anna Lena";
        let ranges = mention_ranges(text, &names(&["Anna", "Anna Lena"]));
        assert_eq!(ranges, vec![0..text.len()]);
    }

    fn person(session: u32, name: &str, channel_id: u32) -> MentionPerson<'_> {
        MentionPerson {
            session,
            name,
            channel_id,
            hidden: false,
        }
    }

    #[test]
    fn suggestions_put_the_own_channel_first_and_leave_out_the_user_and_hidden_bots() {
        let mut people = vec![
            person(1, "Me", 5),
            person(2, "Zoe", 5),
            person(3, "Álvaro", 7),
            person(4, "anna", 7),
            person(5, "Amy", 5),
            person(6, "Abot", 5),
        ];
        people[5].hidden = true;
        let found = |query: &str| -> Vec<String> {
            mention_candidates(&people, query, Some(5), Some(1), 8)
                .into_iter()
                .map(|c| c.name)
                .collect()
        };
        assert_eq!(found("a"), ["Amy", "Álvaro", "anna", "all"]);
        assert_eq!(
            found(""),
            ["Amy", "Zoe", "Álvaro", "anna", "channel", "all"]
        );
        assert_eq!(found("ch"), ["channel"]);
        assert_eq!(found("m"), Vec::<String>::new());
        assert_eq!(
            mention_candidates(&people, "", Some(5), Some(1), 2).len(),
            2
        );
    }

    /// The text an app shows for `html`: tags gone, entities read.
    fn shown(html: &str) -> String {
        let mut text = String::new();
        let mut in_tag = false;
        for c in html.chars() {
            match c {
                '<' => in_tag = true,
                '>' if in_tag => in_tag = false,
                c if !in_tag => text.push(c),
                _ => {}
            }
        }
        text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    // The cases of the desktop's `ui/src/features/chat/markdown.test.ts`, in its order.

    #[test]
    fn converts_inline_markdown_without_a_wrapping_paragraph() {
        assert_eq!(
            markdown_to_html("hey **there** and *you* `x`"),
            "hey <strong>there</strong> and <em>you</em> <code>x</code>"
        );
    }

    #[test]
    fn turns_line_breaks_into_br() {
        assert_eq!(markdown_to_html("a\nb"), "a<br>b");
    }

    #[test]
    fn keeps_paragraphs_for_multi_paragraph_text() {
        assert_eq!(markdown_to_html("a\n\nb"), "<p>a</p><p>b</p>");
    }

    #[test]
    fn makes_safe_links() {
        // The desktop's sanitiser writes `rel="noopener noreferrer"`, the engine's adds
        // `nofollow`. Every receiving client sets its own `rel`, so nothing depends on it.
        assert_eq!(
            markdown_to_html("[site](https://x.test)"),
            "<a href=\"https://x.test\" rel=\"noopener noreferrer nofollow\">site</a>"
        );
        assert!(!markdown_to_html("[bad](javascript:alert(1))").contains("javascript"));
    }

    #[test]
    fn shows_raw_html_as_text() {
        let html = markdown_to_html("<img src=x onerror=\"alert(1)\"> <b>hi</b>");
        assert!(!html.contains("<img"), "{html}");
        assert!(!html.contains("<b>"), "{html}");
        assert!(html.contains("&lt;b&gt;hi"), "{html}");
    }

    #[test]
    fn returns_an_empty_string_for_blank_input() {
        assert_eq!(markdown_to_html("   \n "), "");
    }

    #[test]
    fn escapes_plain_text_for_servers_without_html() {
        assert_eq!(
            typed_to_html(" <b>&</b> ", false),
            "&lt;b&gt;&amp;&lt;/b&gt;"
        );
        assert_eq!(typed_to_html("one\n**two**", false), "one\n**two**");
    }

    #[test]
    fn shows_text_emoticons_and_lone_angle_brackets_as_typed() {
        for text in [">_>", ">.<", ">hello", "<_<", ">:(", "a > b"] {
            let html = markdown_to_html(text);
            assert!(!html.contains("<blockquote"), "{text}: {html}");
            assert!(!html.contains("<em>"), "{text}: {html}");
            assert_eq!(shown(&html), text);
        }
    }

    #[test]
    fn turns_lines_starting_with_a_quote_mark_and_a_space_into_one_quote_block() {
        assert_eq!(
            markdown_to_html("> one\n> two"),
            "<blockquote><p>one<br>two</p></blockquote>"
        );
        let mixed = markdown_to_html("> quoted\nreply");
        assert!(mixed.contains("<blockquote>"), "{mixed}");
        assert!(mixed.contains("reply"), "{mixed}");
        assert_ne!(markdown_to_html("> a\n>_>"), markdown_to_html("> a\n> _>"));
    }

    #[test]
    fn keeps_a_lone_quote_mark_on_a_later_line_as_text() {
        let html = markdown_to_html("a\n>_>");
        assert!(!html.contains("<blockquote"), "{html}");
        assert!(html.contains("&gt;_&gt;"), "{html}");
    }

    #[test]
    fn keeps_markdown_italic_with_asterisks() {
        assert_eq!(markdown_to_html("*x*"), "<em>x</em>");
    }

    #[test]
    fn an_address_sent_by_itself_is_a_link_and_never_markdown() {
        assert_eq!(
            address_to_html("https://example.com/a*b*c.gif", true),
            "<a href=\"https://example.com/a*b*c.gif\">https://example.com/a*b*c.gif</a>"
        );
        assert_eq!(
            address_to_html("https://example.com/?a=1&b=2", false),
            "https://example.com/?a=1&amp;b=2"
        );
    }

    // The desktop's rules that its test file leaves to the library.

    #[test]
    fn a_quote_ends_with_its_last_quoted_line() {
        assert_eq!(
            markdown_to_html("> quoted\nreply"),
            "<blockquote><p>quoted</p></blockquote><p>reply</p>"
        );
        assert_eq!(
            markdown_to_html("> a\n>_>"),
            "<blockquote><p>a</p></blockquote><p>&gt;_&gt;</p>"
        );
        assert_eq!(
            markdown_to_html("> a\n> >_>"),
            "<blockquote><p>a<br>&gt;_&gt;</p></blockquote>"
        );
        assert_eq!(
            markdown_to_html("> > deep\n> back"),
            "<blockquote><blockquote><p>deep</p></blockquote><p>back</p></blockquote>"
        );
    }

    #[test]
    fn a_fenced_code_block_keeps_its_text_and_names_its_language() {
        assert_eq!(
            markdown_to_html("```sql\nselect 1 > 0;\n>_>\n```"),
            "<pre data-mumcord-lang=\"sql\"><code>select 1 &gt; 0;\n&gt;_&gt;\n</code></pre>"
        );
        assert_eq!(
            markdown_to_html("```\n<b>x</b>\n```"),
            "<pre><code>&lt;b&gt;x&lt;/b&gt;\n</code></pre>"
        );
        // Not a language name: no attribute.
        let odd = markdown_to_html("```\"onload=x\ncode\n```");
        assert_eq!(odd, "<pre><code>code\n</code></pre>");
        assert_eq!(code_language("C++ extra"), Some("c++".to_owned()));
        assert_eq!(code_language(&"a".repeat(33)), None);
        assert_eq!(code_language("-x"), None);
    }

    #[test]
    fn lists_strike_through_and_headings_use_plain_tags() {
        assert_eq!(
            markdown_to_html("- a\n- b"),
            "<ul><li>a</li><li>b</li></ul>"
        );
        // The number a list starts with is not kept, as on the desktop.
        assert_eq!(
            markdown_to_html("3. a\n4. b"),
            "<ol><li>a</li><li>b</li></ol>"
        );
        assert_eq!(markdown_to_html("~~no~~"), "<del>no</del>");
        assert_eq!(markdown_to_html("# Title"), "<h1>Title</h1>");
    }

    #[test]
    fn typed_text_is_never_markup() {
        assert_eq!(
            markdown_to_html("<script>alert(1)</script> & \"x\""),
            "&lt;script&gt;alert(1)&lt;/script&gt; &amp; \"x\""
        );
        assert_eq!(markdown_to_html(" one\ntwo "), "one<br>two");
        assert_eq!(markdown_to_html("  "), "");
        let html = markdown_to_html("![x](https://example.com/a.png \"t\" onerror=x)");
        assert!(!html.contains("onerror=x\""), "{html}");
        for evil in [
            "[x](https://example.com/\"onclick=\"x)",
            "<a href=\"javascript:alert(1)\">x</a>",
            "<svg/onload=alert(1)>",
            "<!-- x --><iframe src=//example.com>",
        ] {
            let html = markdown_to_html(evil);
            for tag in [
                "<a href=\"javascript",
                "<svg",
                "<iframe",
                "onclick=\"x\"",
                "<!--",
            ] {
                assert!(!html.contains(tag), "{evil}: {html}");
            }
        }
    }

    #[test]
    fn web_addresses_become_links_and_nothing_else_does() {
        assert_eq!(
            markdown_to_html("see https://example.com/a?b=1&c=2."),
            "see <a href=\"https://example.com/a?b=1&amp;c=2\" rel=\"noopener noreferrer nofollow\">https://example.com/a?b=1&amp;c=2</a>."
        );
        assert_eq!(
            markdown_to_html("(http://example.com/x_(y))"),
            "(<a href=\"http://example.com/x_(y)\" rel=\"noopener noreferrer nofollow\">http://example.com/x_(y)</a>)"
        );
        for plain in [
            "javascript:alert(1)",
            "xhttps://example.com",
            "https://",
            "data:text/html,x",
            "www.example.com",
        ] {
            assert_eq!(shown(&markdown_to_html(plain)), plain);
            assert!(!markdown_to_html(plain).contains("<a"), "{plain}");
        }
        // An address inside a link or code is left alone.
        assert_eq!(
            markdown_to_html("[https://a.test](https://b.test)"),
            "<a href=\"https://b.test\" rel=\"noopener noreferrer nofollow\">https://a.test</a>"
        );
        assert_eq!(
            markdown_to_html("`https://a.test`"),
            "<code>https://a.test</code>"
        );
    }
}
