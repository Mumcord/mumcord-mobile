// SPDX-License-Identifier: AGPL-3.0-only

//! The public server list ("Browse public servers"). The engine downloads and checks the list
//! and pings its servers; the bridge keeps the last download and hands it out in pages, so a
//! list of thousands of servers never crosses the bridge in one call.

use std::sync::Arc;

use mumcord_engine::public_servers::{PublicListError, PublicServer as Listed};

use crate::convert;
use crate::engine::MumcordEngine;
use crate::error::BridgeError;
use crate::types::{PublicServerPage, PublicServerQuery, ServerPing};

/// Most servers of one page.
const MAX_PAGE: u32 = 100;
/// Most servers kept of one download; the real list has a few hundred.
const MAX_KEPT: usize = 5000;

/// The list as last downloaded; `None` before the first download.
#[derive(Clone, Default)]
pub(crate) struct PublicList(Arc<tokio::sync::Mutex<Option<Arc<Vec<Listed>>>>>);

fn matches(server: &Listed, search: &str, country_code: Option<&str>) -> bool {
    let has = |text: &str| text.to_lowercase().contains(search);
    country_code.is_none_or(|code| server.country_code.eq_ignore_ascii_case(code))
        && (search.is_empty() || has(&server.name) || has(&server.host) || has(&server.country))
}

/// The part of `list` that `query` asks for.
fn page(list: &[Listed], query: &PublicServerQuery) -> PublicServerPage {
    let search = query.search.trim().to_lowercase();
    let country = query
        .country_code
        .as_deref()
        .map(str::trim)
        .filter(|code| !code.is_empty());
    let found = || {
        list.iter()
            .filter(|server| matches(server, &search, country))
    };
    PublicServerPage {
        // The list never holds more than `MAX_KEPT` servers.
        total: found().count() as u32,
        servers: found()
            .skip(query.offset as usize)
            .take(query.limit.min(MAX_PAGE) as usize)
            .map(convert::public_server)
            .collect(),
    }
}

impl From<PublicListError> for BridgeError {
    fn from(error: PublicListError) -> Self {
        let detail = error.to_string();
        match error {
            PublicListError::Fetch(_) => Self::ServerUnreachable { detail },
            PublicListError::Malformed(_) => Self::Internal { detail },
        }
    }
}

#[uniffi::export]
impl MumcordEngine {
    /// A page of the public server list, in the order of the list. The list is downloaded on
    /// the first call and when `query.refresh` is set, never in the background; other calls
    /// read the last download. A download that fails gives `ServerUnreachable` and leaves the
    /// last list in place, so the same query without `refresh` still answers. To save a listed
    /// server, call `add_server` with its name, host and port.
    pub async fn public_servers(
        &self,
        query: PublicServerQuery,
    ) -> Result<PublicServerPage, BridgeError> {
        let (engine, public) = (self.engine.clone(), self.public.clone());
        self.run(async move {
            // Held over the download, so two calls never download at the same time.
            let mut kept = public.0.lock().await;
            let list = match kept.as_ref() {
                Some(list) if !query.refresh => list.clone(),
                _ => {
                    let agent = concat!("Mumcord-Mobile/", env!("CARGO_PKG_VERSION"));
                    let mut servers = engine.public_servers(agent).await?;
                    servers.truncate(MAX_KEPT);
                    let list = Arc::new(servers);
                    *kept = Some(list.clone());
                    list
                }
            };
            drop(kept);
            Ok(page(&list, &query))
        })
        .await
    }

    /// Asks a server of the public list for its people online and the latency, without
    /// connecting; `None` when it does not answer. The engine paces these pings (one every
    /// 50 ms, a few at a time) and only asks public addresses, however many the app requests.
    pub async fn ping_public_server(
        &self,
        host: String,
        port: u16,
    ) -> Result<Option<ServerPing>, BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            let answer = engine.public_server_ping(&host, port).await;
            Ok(answer.as_ref().map(convert::server_ping))
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn listed(name: &str, host: &str, country: &str, code: &str) -> Listed {
        Listed {
            name: name.to_owned(),
            host: host.to_owned(),
            port: 64738,
            country: country.to_owned(),
            country_code: code.to_owned(),
        }
    }

    fn list() -> Vec<Listed> {
        vec![
            listed("Nordic Friends", "voice.example.org", "Sweden", "se"),
            listed("Berlin Gaming", "mumble.example.de", "Germany", "de"),
            listed("Kaffeeklatsch", "kaffee.example.de", "Germany", "de"),
        ]
    }

    fn query(search: &str, country: Option<&str>, offset: u32, limit: u32) -> PublicServerQuery {
        PublicServerQuery {
            search: search.to_owned(),
            country_code: country.map(str::to_owned),
            offset,
            limit,
            refresh: false,
        }
    }

    fn names(page: &PublicServerPage) -> Vec<&str> {
        page.servers.iter().map(|s| s.name.as_str()).collect()
    }

    #[test]
    fn the_search_looks_at_name_host_and_country_whatever_the_case() {
        let list = list();
        assert_eq!(
            names(&page(&list, &query("NORDIC", None, 0, 10))),
            ["Nordic Friends"]
        );
        assert_eq!(
            names(&page(&list, &query(" example.de ", None, 0, 10))),
            ["Berlin Gaming", "Kaffeeklatsch"]
        );
        assert_eq!(
            names(&page(&list, &query("sweden", None, 0, 10))),
            ["Nordic Friends"]
        );
        assert_eq!(page(&list, &query("nothing", None, 0, 10)).total, 0);
    }

    #[test]
    fn the_country_filter_and_the_search_both_apply() {
        let list = list();
        let germany = page(&list, &query("", Some("DE"), 0, 10));
        assert_eq!(names(&germany), ["Berlin Gaming", "Kaffeeklatsch"]);
        let both = page(&list, &query("kaffee", Some("de"), 0, 10));
        assert_eq!(names(&both), ["Kaffeeklatsch"]);
        assert_eq!(page(&list, &query("nordic", Some("de"), 0, 10)).total, 0);
        // An empty country is no filter.
        assert_eq!(page(&list, &query("", Some(" "), 0, 10)).total, 3);
    }

    #[test]
    fn pages_follow_the_list_and_name_the_total() {
        let list = list();
        let first = page(&list, &query("", None, 0, 2));
        assert_eq!(names(&first), ["Nordic Friends", "Berlin Gaming"]);
        assert_eq!(first.total, 3);
        let second = page(&list, &query("", None, 2, 2));
        assert_eq!(names(&second), ["Kaffeeklatsch"]);
        assert_eq!(second.total, 3);
        assert!(page(&list, &query("", None, 9, 2)).servers.is_empty());
    }

    #[test]
    fn a_page_never_holds_more_than_a_hundred_servers() {
        let many: Vec<Listed> = (0..250)
            .map(|n| listed(&format!("s{n}"), "h.example", "", ""))
            .collect();
        let all = page(&many, &query("", None, 0, u32::MAX));
        assert_eq!(all.servers.len(), 100);
        assert_eq!(all.total, 250);
    }
}
