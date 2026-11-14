// SPDX-License-Identifier: AGPL-3.0-only

//! The second person of the end-to-end voice test (`e2e/run.sh`).
//!
//! Connects to the local test server as a normal client, enters the channel "Lobby" (in the
//! channel everybody arrives in, the test server lets nobody talk), and exchanges audio through
//! the bridge's C functions exactly like an app does:
//!
//! 1. It listens until it hears the phone talk, and prints `HEARD level=<rms>`.
//! 2. From then on it talks itself, so the phone has something to play.
//!
//! It ends with exit code 1 and `NOT HEARD` when the phone stays silent for too long, and
//! otherwise runs until it is stopped. The server's port comes from `MUMCORD_PORT_BASE`.
//!
//! Every stretch of voice it hears is also reported when it is over, as `SPAN ms=<length>`, so
//! that a scenario can check how long the phone was heard (push-to-talk, mute, whisper).
//!
//! Arguments: `--name <user name>` for a further person on the server, and `--listen-only`
//! for one that never talks and never gives up waiting, and only reports what it hears.

use std::collections::HashMap;
use std::f32::consts::PI;
use std::process::ExitCode;
use std::sync::mpsc::{Receiver, channel};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use mumcord_mobile::{
    AudioConfig, BridgeEvent, ConnectRequest, EngineConfig, EngineListener, MumcordEngine,
    SecureStorage, SecureStorageError, mumcord_audio_capture_push_f32,
    mumcord_audio_playback_pull_f32,
};

const USER_NAME: &str = "e2e-peer";
const CHANNEL: &str = "Lobby";
const SAMPLE_RATE: usize = 48_000;
/// 10 ms, the block size of a typical audio callback.
const BLOCK: usize = SAMPLE_RATE / 100;
/// The level is judged over this many blocks (half a second), which spans the signal's pulses.
const WINDOW_BLOCKS: usize = 50;
/// Level (RMS) from which a window counts as the phone talking; silence is exactly 0.
const HEARD: f32 = 0.02;
/// So many windows in a row have to reach it (two seconds). The engine's own short sounds,
/// like the one for somebody entering the channel, are over sooner.
const HEARD_WINDOWS: usize = 4;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
/// How long the phone may take to connect and start talking.
const LISTEN_TIMEOUT: Duration = Duration::from_secs(240);
/// The longest the peer keeps talking when nobody stops it.
const TALK_TIMEOUT: Duration = Duration::from_secs(300);
/// A stretch of voice is over after this long without a loud block. Longer than the pauses
/// inside the test signal, so one stretch of talking is reported as one span.
const SPAN_GAP: Duration = Duration::from_millis(600);

/// How this person was started.
struct Options {
    name: String,
    /// Never talks and never gives up waiting; only reports what it hears.
    listen_only: bool,
}

fn options() -> Result<Options, String> {
    let mut options = Options {
        name: USER_NAME.into(),
        listen_only: false,
    };
    let mut arguments = std::env::args().skip(1);
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--name" => options.name = arguments.next().ok_or("--name needs a user name")?,
            "--listen-only" => options.listen_only = true,
            other => return Err(format!("unknown argument {other}")),
        }
    }
    Ok(options)
}

/// The identity of this run only; nothing of it is kept.
#[derive(Default)]
struct MemoryStorage(Mutex<HashMap<String, Vec<u8>>>);

impl SecureStorage for MemoryStorage {
    fn set(&self, account: String, secret: Vec<u8>) -> Result<(), SecureStorageError> {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(account, secret);
        Ok(())
    }

    fn get(&self, account: String) -> Result<Option<Vec<u8>>, SecureStorageError> {
        Ok(self
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .get(&account)
            .cloned())
    }

    fn delete(&self, account: String) -> Result<(), SecureStorageError> {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .remove(&account);
        Ok(())
    }
}

/// Passes the engine's events on to the main thread.
struct Events(Mutex<std::sync::mpsc::Sender<BridgeEvent>>);

impl EngineListener for Events {
    fn on_event(&self, event: BridgeEvent) {
        // The receiver is gone only while the program ends.
        let _ = self
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .send(event);
    }
}

/// A voiced, 4 Hz pulsed signal that noise suppression and voice activation treat as speech
/// (the signal of `bridge/tests/live_voice.rs`).
fn speech_like(len: usize, amplitude: f32) -> Vec<f32> {
    let mut phase = 0.0f32;
    (0..len)
        .map(|i| {
            let t = i as f32 / SAMPLE_RATE as f32;
            let f0 = 140.0 + 20.0 * (2.0 * PI * 0.7 * t).sin();
            phase += 2.0 * PI * f0 / SAMPLE_RATE as f32;
            let voiced: f32 = (1..=20)
                .map(|h| (h as f32 * phase).sin() / h as f32)
                .sum::<f32>()
                / 2.0;
            let envelope = (2.0 * PI * 4.0 * t).sin().max(0.0).sqrt();
            amplitude * envelope * voiced
        })
        .collect()
}

fn wait_for<T>(
    events: &Receiver<BridgeEvent>,
    what: &str,
    pick: impl Fn(&BridgeEvent) -> Option<T>,
) -> Result<T, String> {
    let deadline = Instant::now() + CONNECT_TIMEOUT;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        match events.recv_timeout(left) {
            Ok(event) => {
                if let Some(found) = pick(&event) {
                    return Ok(found);
                }
            }
            Err(_) => return Err(format!("timed out waiting for {what}")),
        }
    }
}

fn run() -> Result<bool, String> {
    let options = options()?;
    let port = std::env::var("MUMCORD_PORT_BASE")
        .ok()
        .and_then(|port| port.parse().ok())
        .ok_or("MUMCORD_PORT_BASE is not set to the test server's port")?;
    let data_dir = std::env::temp_dir().join(format!("mumcord-voice-peer-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&data_dir);

    let (sender, events) = channel();
    let engine = MumcordEngine::new(
        EngineConfig {
            data_dir: data_dir.to_string_lossy().into_owned(),
            audio: Some(AudioConfig {
                capture_rate: SAMPLE_RATE as u32,
                capture_channels: 1,
                playback_rate: SAMPLE_RATE as u32,
                playback_channels: 1,
            }),
        },
        Arc::new(MemoryStorage::default()),
        Arc::new(Events(Mutex::new(sender))),
    )
    .map_err(|error| format!("creating the engine failed: {error}"))?;

    let runtime = tokio::runtime::Builder::new_current_thread()
        .build()
        .map_err(|error| format!("starting the runtime failed: {error}"))?;
    runtime
        .block_on(engine.connect(ConnectRequest {
            address: "127.0.0.1".into(),
            port,
            username: options.name.clone(),
            password: None,
        }))
        .map_err(|error| format!("connecting failed: {error}"))?;
    let fingerprint = wait_for(&events, "the certificate question", |event| match event {
        BridgeEvent::CertificatePromptRequired { prompt } => Some(prompt.fingerprint.clone()),
        _ => None,
    })?;
    runtime
        .block_on(engine.answer_certificate_prompt(fingerprint, true))
        .map_err(|error| format!("trusting the certificate failed: {error}"))?;
    let channel = wait_for(&events, "the server's channels", |event| match event {
        BridgeEvent::ServerSynced { server } => Some(
            server
                .channels
                .iter()
                .find(|channel| channel.name == CHANNEL)
                .map(|channel| channel.id),
        ),
        _ => None,
    })?
    .ok_or(format!("the server has no channel {CHANNEL}"))?;
    runtime
        .block_on(engine.join_channel(channel, None))
        .map_err(|error| format!("joining {CHANNEL} failed: {error}"))?;
    wait_for(&events, "the engine to want audio", |event| match event {
        BridgeEvent::AudioWantedChanged { audio } if audio.capture && audio.playback => Some(()),
        _ => None,
    })?;
    println!("CONNECTED as {} in {CHANNEL}, listening", options.name);

    let handles = engine.audio_endpoints();
    let speech = speech_like(SAMPLE_RATE * 4, 0.5);
    let silence = [0.0_f32; BLOCK];
    let mut pulled = [0.0_f32; BLOCK];
    let mut position = 0;
    let mut window_sum = 0.0_f32;
    let mut window_blocks = 0;
    let mut loudest = 0.0_f32;
    let mut loud_windows = 0;
    let mut heard_at = None;
    // The first and the latest loud block of the stretch of voice that is being heard.
    let mut span: Option<(Instant, Instant)> = None;
    let start = Instant::now();
    let mut next = start;
    loop {
        let talking = heard_at.is_some();
        let block = if talking {
            &speech[position..position + BLOCK]
        } else {
            &silence[..]
        };
        // SAFETY: the handles are those of `audio_endpoints` in their roles, the buffers are
        // live and BLOCK samples long, and this is the only thread that calls the functions.
        unsafe {
            mumcord_audio_capture_push_f32(handles.capture, block.as_ptr(), BLOCK);
            mumcord_audio_playback_pull_f32(handles.playback, pulled.as_mut_ptr(), BLOCK);
        }
        position = (position + BLOCK) % speech.len();

        let block_sum = pulled.iter().map(|sample| sample * sample).sum::<f32>();
        let now = Instant::now();
        if (block_sum / BLOCK as f32).sqrt() >= HEARD {
            span = Some((span.map_or(now, |(first, _)| first), now));
        } else if let Some((first, last)) = span
            && now.duration_since(last) > SPAN_GAP
        {
            // The last loud block counts in full.
            println!("SPAN ms={}", (last - first).as_millis() + 10);
            span = None;
        }
        window_sum += block_sum;
        window_blocks += 1;
        if window_blocks == WINDOW_BLOCKS {
            let level = (window_sum / (WINDOW_BLOCKS * BLOCK) as f32).sqrt();
            loudest = loudest.max(level);
            window_sum = 0.0;
            window_blocks = 0;
            loud_windows = if level >= HEARD { loud_windows + 1 } else { 0 };
            if heard_at.is_none() && loud_windows == HEARD_WINDOWS && !options.listen_only {
                println!(
                    "HEARD level={level:.4} for {HEARD_WINDOWS} windows in a row, talking now"
                );
                heard_at = Some(Instant::now());
            }
        }

        match heard_at {
            None if start.elapsed() > LISTEN_TIMEOUT && !options.listen_only => {
                println!("NOT HEARD loudest={loudest:.4}");
                return Ok(false);
            }
            Some(since) if since.elapsed() > TALK_TIMEOUT => return Ok(true),
            _ => {}
        }
        next += Duration::from_millis(10);
        std::thread::sleep(next.saturating_duration_since(Instant::now()));
    }
}

fn main() -> ExitCode {
    match run() {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(message) => {
            eprintln!("voice-peer: {message}");
            ExitCode::FAILURE
        }
    }
}
