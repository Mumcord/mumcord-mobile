// SPDX-License-Identifier: AGPL-3.0-only

//! The chat conventions Mumcord shares with its desktop app: how a reply, a mention and typed
//! text look inside a Mumble text message, so both apps read each other's messages.
//!
//! The engine does not have these rules yet; the desktop app keeps them in its UI
//! (`ui/src/features/chat/reply.ts`, `mentions.ts`, `ownMention.ts`, `markdown.ts`). This file
//! is a port of exactly those rules, with the desktop's test cases, until they move into the
//! engine.

use std::ops::Range;

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

/// What the user typed as the HTML of a Mumble text message: everything is text (markup typed
/// by the user is shown, never interpreted), line breaks are `<br>`, and web addresses are
/// links. Servers that show plain text get the escaped text only. Empty input gives an empty
/// string.
pub(crate) fn text_to_html(text: &str, allow_html: bool) -> String {
    let text = text.trim();
    if !allow_html {
        return escape_html(text);
    }
    let mut out = String::new();
    let mut at = 0;
    let breaks = |plain: &str| {
        escape_html(plain)
            .replace("\r\n", "<br>")
            .replace('\n', "<br>")
    };
    for url in find_urls(text) {
        out.push_str(&breaks(&text[at..url.range.start]));
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
    out.push_str(&breaks(&text[at..]));
    out
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

    #[test]
    fn typed_text_is_never_markup() {
        assert_eq!(
            text_to_html("<script>alert(1)</script> & \"x\"", true),
            "&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;x&quot;"
        );
        assert_eq!(text_to_html(" one\ntwo ", true), "one<br>two");
        assert_eq!(text_to_html("one\ntwo", false), "one\ntwo");
        assert_eq!(text_to_html("  ", true), "");
    }

    #[test]
    fn web_addresses_become_links_and_nothing_else_does() {
        assert_eq!(
            text_to_html("see https://example.com/a?b=1&c=2.", true),
            "see <a href=\"https://example.com/a?b=1&amp;c=2\">https://example.com/a?b=1&amp;c=2</a>."
        );
        assert_eq!(
            text_to_html("(http://example.com/x_(y))", true),
            "(<a href=\"http://example.com/x_(y)\">http://example.com/x_(y)</a>)"
        );
        for plain in [
            "javascript:alert(1)",
            "xhttps://example.com",
            "https://",
            "data:text/html,x",
            "www.example.com",
        ] {
            assert_eq!(text_to_html(plain, true), escape_html(plain), "{plain}");
        }
        // An address cannot break out of its attribute.
        let html = text_to_html("https://example.com/\"onclick=\"x", true);
        assert_eq!(
            html,
            "<a href=\"https://example.com/\">https://example.com/</a>&quot;onclick=&quot;x"
        );
    }
}
