// SPDX-License-Identifier: AGPL-3.0-only

//! The person who watches the phone's camera: the engine decodes it (VP9) as the desktop app
//! does, and every picture is checked for the phone's test pattern.

use std::error::Error;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use mumcord_engine::screen_share::wire::{Codec, Frame};
use mumcord_engine::screen_share::{I420Frame, ScreenFrameSink};
use mumcord_engine::{EngineEvent, ServerId};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::mpsc::{UnboundedSender, unbounded_channel};

use crate::pattern::{Pattern, pattern};
use crate::peer::{self, lock};

/// The name on the server.
const NAME: &str = "e2e-viewer";

/// What the decoded pictures showed; the size and the pattern go to the main loop when they
/// change.
struct Pictures {
    frames: AtomicU64,
    last: Mutex<Option<(Pattern, u32, u32)>>,
    news: UnboundedSender<(Pattern, u32, u32)>,
}

impl ScreenFrameSink for Pictures {
    fn on_encoded(&self, _server_id: ServerId, _sharer: u32, _frame: &Frame) {}

    fn on_decoded(&self, _server_id: ServerId, _sharer: u32, _codec: Codec, frame: &I420Frame) {
        self.frames.fetch_add(1, Ordering::Relaxed);
        let now = (
            pattern(frame.width(), frame.height(), frame.y()),
            frame.width(),
            frame.height(),
        );
        let mut last = lock(&self.last);
        if *last != Some(now) {
            *last = Some(now);
            let _ = self.news.send(now);
        }
    }
}

pub async fn watch(address: String, port: u16) -> Result<(), Box<dyn Error>> {
    let (news, mut changes) = unbounded_channel();
    let pictures = Arc::new(Pictures {
        frames: AtomicU64::new(0),
        last: Mutex::new(None),
        news,
    });
    let mut peer = peer::connect(NAME, address, port).await?;
    peer.engine.camera_set_frame_sink(pictures.clone());
    peer.engine.camera_set_decode_codecs(&[Codec::Vp9]);
    let mut watching: Option<u32> = None;
    let mut started: Option<Instant> = None;
    let mut on_since: Option<Instant> = None;
    let mut first = true;
    let mut input = BufReader::new(tokio::io::stdin()).lines();
    loop {
        tokio::select! {
            event = peer.events.recv() => match event {
                Some(EngineEvent::CameraRoomChanged(room)) => {
                    let camera = room.tiles.iter().map(|t| t.sharer).find(|s| *s != peer.own);
                    match (watching, camera) {
                        (None, Some(sharer)) => {
                            // As a viewer who opened the room and shows this one tile.
                            peer.engine.camera_room_set_open(peer.server, true);
                            peer.engine.camera_tile_set_hidden(peer.server, sharer, false);
                            watching = Some(sharer);
                            on_since = Some(Instant::now());
                            println!("CAMERA ON");
                        }
                        (Some(_), None) => {
                            peer.engine.camera_room_set_open(peer.server, false);
                            watching = None;
                            println!("CAMERA OFF");
                        }
                        _ => {}
                    }
                }
                Some(_) => {}
                None => return Err("the engine stopped".into()),
            },
            change = changes.recv() => if let Some((seen, width, height)) = change {
                if first {
                    first = false;
                    let since = |at: Option<Instant>| at.map_or(-1, |at| at.elapsed().as_millis() as i64);
                    println!(
                        "FIRST_FRAME {width}x{height} pattern={} ms_after_on={} ms_after_start={}",
                        seen.name(),
                        since(on_since),
                        since(started),
                    );
                } else {
                    println!("PATTERN {} {width}x{height}", seen.name());
                }
            },
            line = input.next_line() => match line? {
                Some(line) => {
                    if let Some(word) = line.strip_prefix("mark ") {
                        if word == "start-tap" {
                            started = Some(Instant::now());
                        }
                        let seen = lock(&pictures.last).map_or(Pattern::Other, |(p, ..)| p);
                        println!(
                            "MARK {word} frames={} pattern={}",
                            pictures.frames.load(Ordering::Relaxed),
                            seen.name(),
                        );
                    }
                }
                None => break,
            },
        }
    }
    Ok(())
}
