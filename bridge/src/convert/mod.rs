// SPDX-License-Identifier: AGPL-3.0-only

//! Mapping from engine types to the bridge's own types, so no engine type reaches the bindings.

mod error;
mod link;
mod servers;
mod state;
mod voice;

pub(crate) use link::parse_mumble_link;
pub(crate) use servers::{saved_server, server_fields, server_ping, store_talk_mode, talk_mode};
pub(crate) use state::{
    PersonFlags, certificate_prompt, channel, channel_password_state, connection_state, person,
};
pub(crate) use voice::{
    audio_formats, echo_cancellation, may_send, whisper_channel, whisper_person,
};
