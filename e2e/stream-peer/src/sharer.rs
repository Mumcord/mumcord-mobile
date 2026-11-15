// SPDX-License-Identifier: AGPL-3.0-only

//! The person who shares a screen: the engine's moving test pattern with VP9.

use std::error::Error;

use mumcord_engine::EngineEvent;
use mumcord_engine::store::ScreenCodec;
use tokio::io::{AsyncBufReadExt, BufReader};

use crate::peer;

/// The name on the server; the phone shows "e2e-sharer's screen".
const NAME: &str = "e2e-sharer";

pub async fn share(address: String, port: u16) -> Result<(), Box<dyn Error>> {
    let mut peer = peer::connect(NAME, address, port).await?;
    peer.engine
        .screen_share_start(peer.server, false, None, Some(ScreenCodec::Vp9))
        .await?;
    println!("SHARING");
    let mut viewers = 0usize;
    let mut input = BufReader::new(tokio::io::stdin()).lines();
    loop {
        tokio::select! {
            event = peer.events.recv() => match event {
                Some(EngineEvent::ScreenSharesChanged(shares)) => {
                    let now = shares.iter().find(|s| s.session == peer.own).map_or(0, |s| s.viewers.len());
                    if now != viewers {
                        viewers = now;
                        println!("VIEWERS {viewers}");
                    }
                }
                Some(_) => {}
                None => return Err("the engine stopped".into()),
            },
            line = input.next_line() => match line? {
                Some(line) => {
                    if let Some(word) = line.strip_prefix("mark ") {
                        println!("MARK {word} viewers={viewers}");
                    }
                }
                None => break,
            },
        }
    }
    Ok(())
}
