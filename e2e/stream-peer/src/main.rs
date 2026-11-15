// SPDX-License-Identifier: AGPL-3.0-only

//! The second person of the end-to-end stream and camera tests (e2e/run.sh): the engine itself,
//! run on the host next to the test server. It runs on the test machine only and is part of no
//! app.
//!
//! Usage: `stream-peer [--viewer] <server address> <server port>` (`SERVER_PASSWORD` for a
//! server with a password).
//!
//! As the sharer (default, name `e2e-sharer`) it shares the engine's moving test pattern with
//! VP9 as the desktop app shares a screen, and prints, one per line:
//!
//! - `CONNECTED` once it is on the server and `SHARING` once the share runs,
//! - `VIEWERS <n>` whenever the number of people watching the share changes,
//! - `MARK <word> viewers=<n>` for every line `mark <word>` it reads, so that the test can have
//!   the number of viewers written down at a moment of its choosing (before the tap).
//!
//! As the viewer (`--viewer`, name `e2e-viewer`) it watches the camera someone turns on in its
//! channel, decodes it as the desktop app does and checks every picture for the phone's test
//! pattern (`pattern.rs`). It prints:
//!
//! - `CONNECTED` once it is on the server,
//! - `CAMERA ON` and `CAMERA OFF` when a camera starts and stops in the channel,
//! - `FIRST_FRAME <w>x<h> pattern=<front|back|other> ms_after_on=<ms> ms_after_start=<ms>` for
//!   the first picture, with the time since the camera's `on` and since the line
//!   `mark start-tap` (-1 without it),
//! - `PATTERN <front|back|other> <w>x<h>` whenever the pattern or the size changes,
//! - `MARK <word> frames=<n> pattern=<p>` for every line `mark <word>`: the pictures decoded so
//!   far and the last pattern.
//!
//! Either ends when its input ends. Direct connections are allowed, port mapping on the router
//! is off, and like every engine it asks no outside server for its addresses.

#[cfg_attr(
    not(feature = "sharer"),
    allow(dead_code, reason = "used by the viewer only")
)]
mod pattern;
#[cfg(feature = "sharer")]
mod peer;
#[cfg(feature = "sharer")]
mod sharer;
#[cfg(feature = "sharer")]
mod viewer;

#[cfg(feature = "sharer")]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut arguments: Vec<String> = std::env::args().skip(1).collect();
    let viewer = arguments.first().is_some_and(|a| a == "--viewer");
    if viewer {
        arguments.remove(0);
    }
    let mut arguments = arguments.into_iter();
    let address = arguments.next().ok_or("the server address is missing")?;
    let port: u16 = arguments
        .next()
        .ok_or("the server port is missing")?
        .parse()?;
    // SAFETY: set before the runtime and the engine start any thread. Neither side sends or
    // plays sound, so the host's sound devices stay untouched.
    unsafe { std::env::set_var("MUMCORD_AUDIO_BACKEND", "null") };
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    if viewer {
        runtime.block_on(viewer::watch(address, port))
    } else {
        runtime.block_on(sharer::share(address, port))
    }
}

#[cfg(not(feature = "sharer"))]
fn main() {
    eprintln!("built without the `sharer` feature: e2e/run.sh builds it with libvpx");
    std::process::exit(2);
}
