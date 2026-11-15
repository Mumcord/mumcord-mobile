// SPDX-License-Identifier: AGPL-3.0-only

//! `mumble://` links. The rules are those of the desktop app, which has its parser in its
//! shell and not in the engine.

use url::Url;

use crate::error::BridgeError;
use crate::types::MumbleLink;

/// Port of a Mumble server when the link names none.
const DEFAULT_PORT: u16 = 64738;

/// Parses `mumble://[user[:password]@]host[:port]/[channel/path][?query]`.
pub(crate) fn parse_mumble_link(input: &str) -> Result<MumbleLink, BridgeError> {
    let url = Url::parse(input.trim()).map_err(|_| BridgeError::invalid("not a valid link"))?;
    if !url.scheme().eq_ignore_ascii_case("mumble") {
        return Err(BridgeError::invalid("not a mumble:// link"));
    }
    let address = url
        .host_str()
        .map(|host| host.trim_start_matches('[').trim_end_matches(']'))
        .filter(|host| !host.is_empty())
        .ok_or_else(|| BridgeError::invalid("the link has no server address"))?;
    let decode =
        |part: &str| percent_decode(part).ok_or_else(|| BridgeError::invalid("bad escape in link"));
    let username = match url.username() {
        "" => None,
        user => Some(decode(user)?),
    };
    let channel_path = match url.path_segments() {
        Some(segments) => segments
            .filter(|segment| !segment.is_empty())
            .map(decode)
            .collect::<Result<Vec<_>, _>>()?,
        None => Vec::new(),
    };
    Ok(MumbleLink {
        address: address.to_owned(),
        port: url.port().unwrap_or(DEFAULT_PORT),
        username,
        channel_path,
    })
}

/// Decodes `%XX` escapes as UTF-8; `None` on malformed escapes or invalid UTF-8.
fn percent_decode(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = text.get(i + 1..i + 3)?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_full_link_gives_server_port_user_and_channel_path() {
        let link = parse_mumble_link(
            " mumble://ada:secret@voice.example:1234/Games/Raid%20One?version=1.5.0 ",
        )
        .expect("link");
        assert_eq!(
            link,
            MumbleLink {
                address: "voice.example".into(),
                port: 1234,
                username: Some("ada".into()),
                channel_path: vec!["Games".into(), "Raid One".into()],
            }
        );
    }

    #[test]
    fn a_bare_link_uses_the_default_port_and_no_user() {
        let link = parse_mumble_link("mumble://voice.example").expect("link");
        assert_eq!(link.address, "voice.example");
        assert_eq!(link.port, DEFAULT_PORT);
        assert_eq!(link.username, None);
        assert!(link.channel_path.is_empty());
    }

    #[test]
    fn an_ipv6_address_loses_its_brackets() {
        let link = parse_mumble_link("mumble://[::1]:64838/").expect("link");
        assert_eq!(link.address, "::1");
        assert_eq!(link.port, 64838);
    }

    #[test]
    fn other_schemes_and_broken_links_are_refused() {
        for input in [
            "https://voice.example",
            "mumble://",
            "voice.example",
            "mumble://voice.example/%zz",
            "mumble://voice.example/%ff",
        ] {
            let result = parse_mumble_link(input);
            assert!(
                matches!(result, Err(BridgeError::InvalidInput { .. })),
                "{input}: {result:?}"
            );
        }
    }
}
