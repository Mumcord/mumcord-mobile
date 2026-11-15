// SPDX-License-Identifier: AGPL-3.0-only

//! Moderation basics (Spec 5 FR-5.90 … FR-5.93): move, server mute and deafen, kick and ban.
//!
//! The server decides. The app offers an action only where the server's permission answer
//! allows it: [`Channel::permissions`](crate::Channel::permissions) (move and mute in that
//! channel; kick and ban are the rights of the root channel), which changes arrive with as
//! [`BridgeEvent::ChannelsChanged`](crate::BridgeEvent::ChannelsChanged). A refusal of the
//! server fails the command with `PermissionDenied` and the server's own text.
//!
//! The commands that change a person return `true` when the server confirmed the change and
//! `false` when it sent no answer in time (the request went out; the change then shows in the
//! tree or not).

use mumcord_engine::{ActionOutcome, BanOutcome, BanRequest, PersonView};

use crate::engine::{MumcordEngine, connected_server};
use crate::error::BridgeError;
use crate::events::lock;
use crate::types::{BanInput, BanResult, BanTarget};

fn confirmed(outcome: ActionOutcome) -> bool {
    outcome == ActionOutcome::Confirmed
}

/// A reason as the server gets it: `None` for none or a blank one.
fn reason(reason: Option<String>) -> Option<String> {
    reason
        .map(|reason| reason.trim().to_owned())
        .filter(|reason| !reason.is_empty())
}

pub(crate) fn ban_target(view: PersonView) -> BanTarget {
    BanTarget {
        address: view.address.map(|address| address.to_string()),
        online_secs: view.online_secs,
        ban_list_readable: view.ban_list_readable,
        has_certificate: view.cert_hash.is_some(),
        can_kick: view.can_kick,
        can_ban: view.can_ban,
    }
}

pub(crate) fn ban_request(input: BanInput, address_is_v6: bool) -> BanRequest {
    BanRequest {
        reason: reason(input.reason),
        duration_secs: input.duration_secs,
        by_cert: input.by_certificate,
        by_ip: input.by_address,
        // The single address: subnets are left to the desktop's ban list.
        mask: if address_is_v6 { 128 } else { 32 },
        remove_now: input.remove_now,
        force: input.force,
        quick: input.quick,
    }
}

pub(crate) fn ban_result(outcome: BanOutcome) -> BanResult {
    match outcome {
        BanOutcome::Saved { removed, .. } => BanResult::Saved { removed },
        BanOutcome::QuickBanned => BanResult::QuickBanned,
        BanOutcome::Conflict { .. } => BanResult::Conflict,
    }
}

#[uniffi::export]
impl MumcordEngine {
    /// Moves a person to another channel. Fails with `NotFound` for an unknown person or
    /// channel.
    pub async fn move_person(&self, session: u32, channel_id: u32) -> Result<bool, BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            Ok(confirmed(
                engine.person_move(server, session, channel_id).await?,
            ))
        })
        .await
    }

    /// Server-mutes a person or lifts it.
    pub async fn set_server_mute(&self, session: u32, muted: bool) -> Result<bool, BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            Ok(confirmed(
                engine
                    .person_set_server_mute(server, session, muted)
                    .await?,
            ))
        })
        .await
    }

    /// Server-deafens a person, which also mutes them, or lifts it; the mute set before stays.
    pub async fn set_server_deafen(
        &self,
        session: u32,
        deafened: bool,
    ) -> Result<bool, BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            Ok(confirmed(
                engine
                    .person_set_server_deafen(server, session, deafened)
                    .await?,
            ))
        })
        .await
    }

    /// Kicks a person; they see the reason and can connect again. The user themselves cannot
    /// be kicked (`InvalidInput`).
    pub async fn kick_person(
        &self,
        session: u32,
        reason: Option<String>,
    ) -> Result<bool, BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            let reason = self::reason(reason);
            Ok(confirmed(
                engine
                    .kick_person(server, session, reason.as_deref())
                    .await?,
            ))
        })
        .await
    }

    /// What the ban dialog needs about a person; asks the server for their address and
    /// whether its ban list can be read (up to a few seconds). Fails with `PermissionDenied`
    /// without the kick and the ban right.
    pub async fn ban_target(&self, session: u32) -> Result<BanTarget, BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            let view = engine.admin_target(server, session).await?;
            if let Some(address) = view.address {
                lock(&connection)
                    .ban_addresses_v6
                    .insert(session, address.is_ipv6());
            }
            Ok(ban_target(view))
        })
        .await
    }

    /// Bans a person: one entry on the server's ban list with the dialog's length and targets,
    /// or the server's default ban with `quick`. Fails with `InvalidInput` when both targets are
    /// off, when the server did not report the person's address (call
    /// [`MumcordEngine::ban_target`] first) or for the user themselves.
    pub async fn ban_person(&self, session: u32, ban: BanInput) -> Result<BanResult, BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            // The mask follows the address family the server reported to `ban_target`.
            let address_is_v6 = lock(&connection)
                .ban_addresses_v6
                .get(&session)
                .copied()
                .unwrap_or(false);
            let request = ban_request(ban, address_is_v6);
            Ok(ban_result(
                engine.ban_person(server, session, &request).await?,
            ))
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use super::*;

    fn input() -> BanInput {
        BanInput {
            reason: Some("  spam  ".into()),
            duration_secs: 3600,
            by_certificate: true,
            by_address: false,
            remove_now: true,
            force: false,
            quick: false,
        }
    }

    #[test]
    fn a_ban_names_the_single_address_of_its_family() {
        let request = ban_request(input(), false);
        assert_eq!(request.mask, 32);
        assert_eq!(request.reason.as_deref(), Some("spam"));
        assert_eq!(request.duration_secs, 3600);
        assert!(request.by_cert && !request.by_ip && request.remove_now);
        assert!(!request.force && !request.quick);
        assert_eq!(ban_request(input(), true).mask, 128);
    }

    #[test]
    fn a_blank_reason_is_none() {
        assert_eq!(reason(Some("   ".into())), None);
        assert_eq!(reason(None), None);
        assert_eq!(reason(Some(" bye ".into())).as_deref(), Some("bye"));
    }

    #[test]
    fn the_target_says_what_the_dialog_may_offer() {
        let target = ban_target(PersonView {
            session: 4,
            address: Some(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 7))),
            online_secs: Some(90),
            ban_list_readable: false,
            cert_hash: None,
            can_kick: true,
            can_ban: false,
        });
        assert_eq!(target.address.as_deref(), Some("192.0.2.7"));
        assert_eq!(target.online_secs, Some(90));
        assert!(!target.ban_list_readable && !target.has_certificate);
        assert!(target.can_kick && !target.can_ban);
    }

    #[test]
    fn ban_outcomes_keep_only_what_the_app_shows() {
        assert_eq!(
            ban_result(BanOutcome::Saved {
                key: "k".into(),
                removed: true
            }),
            BanResult::Saved { removed: true }
        );
        assert_eq!(ban_result(BanOutcome::QuickBanned), BanResult::QuickBanned);
        assert_eq!(
            ban_result(BanOutcome::Conflict {
                key: "k".into(),
                fresh: Vec::new()
            }),
            BanResult::Conflict
        );
    }
}
