// SPDX-License-Identifier: AGPL-3.0-only

//! The activity log of the connection (Spec 11, Spec 5 FR-5.94): who connected, left, moved
//! and started or stopped a stream, as the bridge observes it after the server's first sync.
//!
//! The engine does not keep this log; the desktop app collects it in its UI
//! (`ui/src/features/activity/activityStore.ts`). The bridge follows the same rules: the state
//! of the sync is the baseline and produces only the "connected" entry, later changes become
//! entries, at most [`MAX_ENTRIES`] are kept, and the log lives in memory for the connection
//! only. A kick or a ban looks like leaving, because the engine reports no actor or reason for
//! a removal. Watch parties and plugin activities do not exist on the phone.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use mumcord_engine::UserView;

use crate::engine::MumcordEngine;
use crate::events::lock;
use crate::types::{ActivityEntry, ActivityKind};

/// Entries kept per connection; the oldest goes first.
pub(crate) const MAX_ENTRIES: usize = 200;

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |since| {
            i64::try_from(since.as_millis()).unwrap_or(i64::MAX)
        })
}

/// The entries of one connection, oldest first.
#[derive(Default)]
pub(crate) struct ActivityLog {
    entries: VecDeque<ActivityEntry>,
}

impl ActivityLog {
    /// Adds an entry about `user` and returns it for the event.
    pub(crate) fn add(
        &mut self,
        kind: ActivityKind,
        user: &UserView,
        is_self: bool,
        channel: Option<(u32, Option<String>)>,
    ) -> ActivityEntry {
        let (channel_id, channel_name) =
            channel.map_or((None, None), |(id, name)| (Some(id), name));
        let entry = ActivityEntry {
            id: NEXT_ID.fetch_add(1, Ordering::Relaxed),
            kind,
            session: user.session,
            name: user.name.clone(),
            is_self,
            channel_id,
            channel_name,
            at_ms: now_ms(),
        };
        if self.entries.len() == MAX_ENTRIES {
            self.entries.pop_front();
        }
        self.entries.push_back(entry.clone());
        entry
    }

    /// Newest first, as the feed lists them.
    pub(crate) fn newest_first(&self) -> Vec<ActivityEntry> {
        self.entries.iter().rev().cloned().collect()
    }
}

#[uniffi::export]
impl MumcordEngine {
    /// The activity log of the current connection, newest first; empty while not connected.
    /// New entries arrive as [`BridgeEvent::ActivityAdded`](crate::BridgeEvent::ActivityAdded).
    /// Names are the server's text and untrusted.
    pub fn activity_log(&self) -> Vec<ActivityEntry> {
        lock(&self.connection).activity.newest_first()
    }
}
