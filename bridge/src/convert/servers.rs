// SPDX-License-Identifier: AGPL-3.0-only

use mumcord_engine::store::TalkMode as StoreTalkMode;
use mumcord_engine::{PasswordChange, ServerFields, ServerQuery, ServerView};

use crate::types::{PasswordInput, SavedServer, ServerInput, ServerPing, TalkMode};

pub(crate) fn saved_server(server: ServerView) -> SavedServer {
    SavedServer {
        id: server.id,
        name: server.label,
        address: server.address,
        port: server.port,
        username: server.username,
        has_password: server.has_password,
        identity_id: server.identity_id,
    }
}

pub(crate) fn server_fields(input: ServerInput) -> ServerFields {
    ServerFields {
        label: input.name,
        address: input.address,
        port: input.port,
        username: input.username,
        password: match input.password {
            PasswordInput::Keep => PasswordChange::Keep,
            PasswordInput::Clear => PasswordChange::Clear,
            PasswordInput::Set { value } => PasswordChange::Set(value),
        },
    }
}

pub(crate) fn server_ping(answer: &ServerQuery) -> ServerPing {
    ServerPing {
        latency_ms: answer.ping_ms,
        people_online: answer.users,
        max_people: answer.max_users,
    }
}

pub(crate) fn talk_mode(mode: StoreTalkMode) -> TalkMode {
    match mode {
        StoreTalkMode::VoiceActivation => TalkMode::VoiceActivity,
        StoreTalkMode::PushToTalk => TalkMode::PushToTalk,
    }
}

pub(crate) fn store_talk_mode(mode: TalkMode) -> StoreTalkMode {
    match mode {
        TalkMode::VoiceActivity => StoreTalkMode::VoiceActivation,
        TalkMode::PushToTalk => StoreTalkMode::PushToTalk,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn server_input_maps_onto_the_engine_fields() {
        let fields = server_fields(ServerInput {
            name: "Home".into(),
            address: "mumble.example".into(),
            port: 64738,
            username: "ada".into(),
            password: PasswordInput::Set {
                value: "secret".into(),
            },
            identity_id: Some(3),
        });
        assert_eq!(fields.label, "Home");
        assert_eq!(fields.password, PasswordChange::Set("secret".into()));
        assert_eq!(
            server_fields(ServerInput {
                name: String::new(),
                address: "a".into(),
                port: 1,
                username: "u".into(),
                password: PasswordInput::Clear,
                identity_id: None,
            })
            .password,
            PasswordChange::Clear
        );
    }

    #[test]
    fn talk_modes_map_both_ways() {
        for mode in [TalkMode::VoiceActivity, TalkMode::PushToTalk] {
            assert_eq!(talk_mode(store_talk_mode(mode)), mode);
        }
    }
}
