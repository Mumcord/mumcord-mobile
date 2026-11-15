// SPDX-License-Identifier: AGPL-3.0-only

//! The person who shares a screen in the end-to-end stream test (e2e/run.sh): the engine itself,
//! run on the host next to the test server, sharing its moving test pattern with VP9 as the
//! desktop app shares a screen. It runs on the test machine only and is part of no app.
//!
//! Usage: `stream-peer <server address> <server port>` (`SERVER_PASSWORD` for a server with a
//! password). It prints, one per line:
//!
//! - `CONNECTED` once it is on the server and `SHARING` once the share runs,
//! - `VIEWERS <n>` whenever the number of people watching the share changes,
//! - `MARK <word> viewers=<n>` for every line `mark <word>` it reads, so that the test can have
//!   the number of viewers written down at a moment of its choosing (before the tap),
//!
//! and ends when its input ends. Direct connections are allowed, port mapping on the router is
//! off, and like every engine it asks no outside server for its addresses.

#[cfg(feature = "sharer")]
mod sharer;

#[cfg(feature = "sharer")]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    sharer::main()
}

#[cfg(not(feature = "sharer"))]
fn main() {
    eprintln!("built without the `sharer` feature: e2e/run.sh builds it with libvpx");
    std::process::exit(2);
}
