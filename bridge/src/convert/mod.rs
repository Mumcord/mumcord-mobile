// SPDX-License-Identifier: AGPL-3.0-only

//! Mapping from engine types to the bridge's own types, so no engine type reaches the bindings.

mod error;
mod state;

pub(crate) use state::{certificate_prompt, channel, connection_state, person, server_state};
