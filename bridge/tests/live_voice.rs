// SPDX-License-Identifier: AGPL-3.0-only

//! Voice through the bridge against the local 1.5 test server, as the apps drive it: three
//! "phones" whose audio threads call the C functions of `bridge/include/mumcord_audio.h`.
//! `scripts/testenv.sh up`, then
//! `MUMCORD_LIVE=1 cargo test -p mumcord-mobile-bridge --test live_voice -- --ignored --nocapture`.
//! `MUMCORD_PORT_BASE` selects the server's port (default 64738), `SERVER_PASSWORD` the connect
//! password if the instance has one.

mod common;

use std::collections::HashMap;
use std::f32::consts::PI;
use std::future::Future;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use common::{Counting, heap_calls};
use mumcord_mobile::{
    AudioConfig, AudioWanted, BridgeError, BridgeEvent, ConnectRequest, EchoCancellation,
    EngineConfig, EngineListener, MumcordEngine, Person, SecureStorage, SecureStorageError,
    TalkMode, WhisperTarget, mumcord_audio_capture_push_f32, mumcord_audio_capture_push_i16,
    mumcord_audio_playback_pull_f32, mumcord_audio_playback_pull_i16,
};

#[global_allocator]
static GLOBAL: Counting = Counting;

const WAIT: Duration = Duration::from_secs(20);
const SAMPLE_RATE: usize = 48_000;
/// One audio callback: 10 ms.
const BLOCK: usize = SAMPLE_RATE / 100;
/// Longest time from the microphone callback of one app to the speaker callback of another on
/// a local server. The real figure is around 100 ms; the bound leaves room for a loaded test
/// machine.
const MAX_DELAY: Duration = Duration::from_millis(500);
/// Level (RMS) speech arrives above, and the level below which playback counts as silent.
const HEARD: f32 = 0.02;
const SILENT: f32 = 0.003;
/// Time for speech in flight (jitter buffer, playback queue) to play out after sending stops.
const SETTLE: Duration = Duration::from_millis(1200);
/// The limit of "Reduced voice quality" on mobile data (FR-5.26).
const MOBILE_DATA_KBPS: u32 = 24;

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

#[derive(Default)]
struct MemoryStorage(Mutex<HashMap<String, Vec<u8>>>);

impl SecureStorage for MemoryStorage {
    fn set(&self, account: String, secret: Vec<u8>) -> Result<(), SecureStorageError> {
        lock(&self.0).insert(account, secret);
        Ok(())
    }

    fn get(&self, account: String) -> Result<Option<Vec<u8>>, SecureStorageError> {
        Ok(lock(&self.0).get(&account).cloned())
    }

    fn delete(&self, account: String) -> Result<(), SecureStorageError> {
        lock(&self.0).remove(&account);
        Ok(())
    }
}

/// Keeps every event, so the test can look for one from any point on.
#[derive(Default)]
struct Log(Mutex<Vec<BridgeEvent>>);

impl EngineListener for Log {
    fn on_event(&self, event: BridgeEvent) {
        assert_eq!(
            std::thread::current().name(),
            Some("mumcord-bridge-events"),
            "events arrive on the bridge's event thread"
        );
        lock(&self.0).push(event);
    }
}

/// What the app's audio threads share with the test.
#[derive(Default)]
struct Audio {
    /// The microphone picks up speech (otherwise silence).
    speaking: AtomicBool,
    stop: AtomicBool,
    /// Everything the speaker callback got to play.
    played: Mutex<Vec<f32>>,
    /// Heap calls made inside the C functions.
    heap_calls: AtomicUsize,
}

/// A voiced, 4 Hz pulsed signal that noise suppression and voice activation treat as speech
/// (as in the engine's tests).
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

fn rms(samples: &[f32]) -> f32 {
    (samples.iter().map(|s| s * s).sum::<f32>() / samples.len().max(1) as f32).sqrt()
}

/// The app's audio thread: every 10 ms the microphone callback pushes a block and the speaker
/// callback pulls one, through the C functions. `sixteen_bit` selects the sample type, so both
/// variants carry real audio.
fn audio_thread(audio: Arc<Audio>, capture: u64, playback: u64, sixteen_bit: bool) {
    let speech = speech_like(SAMPLE_RATE * 4, 0.5);
    let speech_i16: Vec<i16> = speech.iter().map(|s| (s * 32767.0) as i16).collect();
    let mut position = 0;
    let mut pulled_f32 = [0.0_f32; BLOCK];
    let mut pulled_i16 = [0_i16; BLOCK];
    let mut next = Instant::now();
    while !audio.stop.load(Ordering::Relaxed) {
        let speaking = audio.speaking.load(Ordering::Relaxed);
        let before = heap_calls();
        // SAFETY: the handles are those of `audio_endpoints`, the buffers are live.
        unsafe {
            if sixteen_bit {
                let block = if speaking {
                    &speech_i16[position..position + BLOCK]
                } else {
                    &[0_i16; BLOCK][..]
                };
                mumcord_audio_capture_push_i16(capture, block.as_ptr(), BLOCK);
                mumcord_audio_playback_pull_i16(playback, pulled_i16.as_mut_ptr(), BLOCK);
            } else {
                let block = if speaking {
                    &speech[position..position + BLOCK]
                } else {
                    &[0.0_f32; BLOCK][..]
                };
                mumcord_audio_capture_push_f32(capture, block.as_ptr(), BLOCK);
                mumcord_audio_playback_pull_f32(playback, pulled_f32.as_mut_ptr(), BLOCK);
            }
        }
        audio
            .heap_calls
            .fetch_add(heap_calls() - before, Ordering::Relaxed);
        if sixteen_bit {
            for (out, sample) in pulled_f32.iter_mut().zip(pulled_i16) {
                *out = f32::from(sample) / 32768.0;
            }
        }
        lock(&audio.played).extend_from_slice(&pulled_f32);
        position = (position + BLOCK) % speech.len();
        next += Duration::from_millis(10);
        std::thread::sleep(next.saturating_duration_since(Instant::now()));
    }
}

/// One "phone": an engine, connected and in the Lobby, with its audio thread.
struct Phone {
    engine: Arc<MumcordEngine>,
    log: Arc<Log>,
    audio: Arc<Audio>,
    thread: Option<std::thread::JoinHandle<()>>,
    session: u32,
    lobby: u32,
}

fn block_on<T>(future: impl Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread()
        .build()
        .expect("runtime")
        .block_on(future)
}

impl Phone {
    fn start(name: &str, sixteen_bit: bool) -> Self {
        let username = format!("mc-{name}-{:06x}", std::process::id() & 0xff_ffff);
        let dir = Path::new(env!("CARGO_TARGET_TMPDIR")).join(format!("bridge-voice-{name}"));
        let _ = std::fs::remove_dir_all(&dir);
        let log = Arc::new(Log::default());
        let engine = MumcordEngine::new(
            EngineConfig {
                data_dir: dir.to_string_lossy().into_owned(),
                audio: Some(AudioConfig {
                    capture_rate: SAMPLE_RATE as u32,
                    capture_channels: 1,
                    playback_rate: SAMPLE_RATE as u32,
                    playback_channels: 1,
                }),
            },
            Arc::new(MemoryStorage::default()),
            log.clone(),
        )
        .expect("engine");
        assert_eq!(engine.snapshot().audio, AudioWanted::default());
        let mut phone = Self {
            engine,
            log,
            audio: Arc::default(),
            thread: None,
            session: 0,
            lobby: 0,
        };

        let request = ConnectRequest {
            address: "127.0.0.1".into(),
            port: std::env::var("MUMCORD_PORT_BASE")
                .ok()
                .and_then(|port| port.parse().ok())
                .unwrap_or(64738),
            username,
            password: std::env::var("SERVER_PASSWORD")
                .ok()
                .filter(|password| !password.is_empty()),
        };
        block_on(phone.engine.connect(request)).expect("connect");
        let fingerprint = phone.wait(0, "the certificate prompt", |e| match e {
            BridgeEvent::CertificatePromptRequired { prompt } => Some(prompt.fingerprint.clone()),
            _ => None,
        });
        block_on(phone.engine.answer_certificate_prompt(fingerprint, true)).expect("trust");
        let server = phone.wait(0, "the synced server", |e| match e {
            BridgeEvent::ServerSynced { server } => Some(server.clone()),
            _ => None,
        });
        phone.session = server.self_session;
        phone.lobby = server
            .channels
            .iter()
            .find(|channel| channel.name == "Lobby")
            .expect("Lobby")
            .id;
        block_on(phone.engine.join_channel(phone.lobby, None)).expect("join Lobby");

        // The engine asks for the platform audio; only then the app opens its streams.
        phone.wait_audio(0, true, true);
        assert_eq!(
            phone.engine.snapshot().audio,
            AudioWanted {
                capture: true,
                playback: true
            }
        );
        let handles = phone.engine.audio_endpoints();
        let audio = phone.audio.clone();
        phone.thread = Some(
            std::thread::Builder::new()
                .name(format!("audio-{name}"))
                .spawn(move || audio_thread(audio, handles.capture, handles.playback, sixteen_bit))
                .expect("audio thread"),
        );
        phone
    }

    /// Number of events so far: a mark to look for later events from.
    fn mark(&self) -> usize {
        lock(&self.log.0).len()
    }

    /// Waits for an event from `from` on that `pick` accepts.
    fn wait<T>(&self, from: usize, what: &str, pick: impl Fn(&BridgeEvent) -> Option<T>) -> T {
        let deadline = Instant::now() + WAIT;
        loop {
            if let Some(found) = lock(&self.log.0)[from..].iter().find_map(&pick) {
                return found;
            }
            assert!(Instant::now() < deadline, "timed out waiting for {what}");
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    fn wait_audio(&self, from: usize, capture: bool, playback: bool) {
        let wanted = AudioWanted { capture, playback };
        self.wait(from, &format!("{wanted:?}"), |e| match e {
            BridgeEvent::AudioWantedChanged { audio } if *audio == wanted => Some(()),
            _ => None,
        });
    }

    /// Waits for a state of the person `session` that `ok` accepts.
    fn wait_person(&self, from: usize, session: u32, what: &str, ok: impl Fn(&Person) -> bool) {
        self.wait(from, what, |e| match e {
            BridgeEvent::PersonChanged { person } if person.session == session && ok(person) => {
                Some(())
            }
            _ => None,
        });
    }

    /// Position in what the speaker callback played so far.
    fn played(&self) -> usize {
        lock(&self.audio.played).len()
    }

    /// Level of what the speaker callback plays during the next `time`.
    fn level_during(&self, time: Duration) -> f32 {
        let from = self.played();
        std::thread::sleep(time);
        rms(&lock(&self.audio.played)[from..])
    }

    fn hears(&self, what: &str) {
        let level = self.level_during(Duration::from_secs(2));
        assert!(level > HEARD, "{what}: heard too little (level {level})");
    }

    fn hears_nothing(&self, what: &str) {
        std::thread::sleep(SETTLE);
        let level = self.level_during(Duration::from_millis(1500));
        assert!(level < SILENT, "{what}: heard something (level {level})");
    }

    /// Ends the audio thread and returns the heap calls it made inside the C functions.
    fn stop_audio(&mut self) -> usize {
        self.audio.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            thread.join().expect("audio thread");
        }
        self.audio.heap_calls.load(Ordering::Relaxed)
    }
}

#[test]
#[ignore = "needs testenv v15 (MUMCORD_LIVE=1)"]
fn voice_between_phones_through_the_c_audio_functions() {
    if !std::env::var("MUMCORD_LIVE").is_ok_and(|v| v == "1") {
        eprintln!("skipped: set MUMCORD_LIVE=1 and start scripts/testenv.sh up");
        return;
    }
    let mut talker = Phone::start("talk", false);
    let mut listener = Phone::start("hear", true);
    let mut third = Phone::start("third", false);
    // Everybody is in the Lobby and the voice paths are up.
    std::thread::sleep(Duration::from_secs(3));

    // Voice activity: speech into the talker's microphone callback comes out of the others'
    // speaker callbacks, soon and at a speech level.
    let settings = block_on(talker.engine.voice_settings()).expect("settings");
    assert_eq!(settings.talk_mode, TalkMode::VoiceActivity);
    assert_eq!(settings.echo_cancellation, EchoCancellation::System);
    assert!(settings.noise_suppression && settings.auto_gain);
    assert_eq!(settings.bitrate_limit_kbps, None);
    let (own_mark, mark, from) = (talker.mark(), listener.mark(), listener.played());
    talker.audio.speaking.store(true, Ordering::Relaxed);
    listener.wait_person(mark, talker.session, "the talker talking", |p| p.talking);
    talker.wait_person(own_mark, talker.session, "the own talking state", |p| {
        p.is_self && p.talking
    });
    talker.wait(own_mark, "the input level", |e| match e {
        BridgeEvent::InputLevelChanged { percent } if *percent > 0 => Some(()),
        _ => None,
    });
    std::thread::sleep(Duration::from_secs(2));
    let heard = lock(&listener.audio.played)[from..].to_vec();
    let onset = heard
        .iter()
        .position(|s| s.abs() > 0.01)
        .expect("the listener heard nothing");
    let delay = Duration::from_secs_f64(onset as f64 / SAMPLE_RATE as f64);
    eprintln!("microphone callback to speaker callback: {delay:?}");
    assert!(delay <= MAX_DELAY, "speech arrived after {delay:?}");
    let level = rms(&heard[onset..]);
    assert!(level > HEARD, "speech arrived too quiet (level {level})");
    third.hears("voice activity, third person");

    // Mute: nothing is sent and the microphone is no longer wanted; unmuting brings both back.
    let mark = talker.mark();
    block_on(talker.engine.set_self_mute(true)).expect("mute");
    talker.wait_person(mark, talker.session, "own mute", |p| p.self_muted);
    talker.wait_audio(mark, false, true);
    listener.hears_nothing("muted talker");
    let mark = talker.mark();
    block_on(talker.engine.set_self_mute(false)).expect("unmute");
    talker.wait_audio(mark, true, true);
    listener.hears("unmuted talker");

    // Deafen: the speaker callback gets silence and the user is muted too; ending it restores
    // the state from before.
    let mark = listener.mark();
    block_on(listener.engine.set_self_deaf(true)).expect("deafen");
    listener.wait_person(mark, listener.session, "own deafen", |p| {
        p.self_deafened && p.self_muted
    });
    listener.wait_audio(mark, false, true);
    listener.hears_nothing("deafened listener");
    let mark = listener.mark();
    block_on(listener.engine.set_self_deaf(false)).expect("undeafen");
    listener.wait_person(mark, listener.session, "the state from before", |p| {
        !p.self_deafened && !p.self_muted
    });
    listener.wait_audio(mark, true, true);
    listener.hears("listener after deafen");

    // Push-to-talk: sent only while the button is held; the microphone stays wanted.
    block_on(talker.engine.set_talk_mode(TalkMode::PushToTalk)).expect("talk mode");
    listener.hears_nothing("push-to-talk, button up");
    assert!(talker.engine.snapshot().audio.capture);
    talker.engine.set_push_to_talk_pressed(true);
    listener.hears("push-to-talk, button down");
    talker.engine.set_push_to_talk_pressed(false);
    listener.hears_nothing("push-to-talk, button up again");

    // Whisper to one person: only they hear it, while it is held, without the talk button.
    assert!(!talker.engine.snapshot().whispering);
    let whisper = WhisperTarget::Person {
        session: listener.session,
    };
    block_on(talker.engine.start_whisper(whisper)).expect("whisper");
    assert!(talker.engine.snapshot().whispering);
    let third_from = third.played();
    listener.hears("whisper, the target");
    let leaked = rms(&lock(&third.audio.played)[third_from..]);
    assert!(
        leaked < SILENT,
        "the third person heard the whisper ({leaked})"
    );
    talker.engine.stop_whisper();
    assert!(!talker.engine.snapshot().whispering);
    listener.hears_nothing("whisper ended");

    // Shout to a channel: everybody in it hears it.
    let shout = WhisperTarget::Channel {
        channel_id: talker.lobby,
    };
    block_on(talker.engine.start_whisper(shout)).expect("shout");
    third.hears("shout, third person");
    listener.hears("shout, listener");
    talker.engine.stop_whisper();
    listener.hears_nothing("shout ended");

    // Targets that are gone and a muted user are refused.
    let gone = block_on(
        talker
            .engine
            .start_whisper(WhisperTarget::Person { session: 9999 }),
    );
    assert!(
        matches!(gone, Err(BridgeError::NotFound { .. })),
        "{gone:?}"
    );
    let mark = talker.mark();
    block_on(talker.engine.set_self_mute(true)).expect("mute");
    talker.wait_person(mark, talker.session, "own mute", |p| p.self_muted);
    let muted = block_on(talker.engine.start_whisper(whisper));
    assert!(
        matches!(muted, Err(BridgeError::InvalidInput { .. })),
        "{muted:?}"
    );
    block_on(talker.engine.set_self_mute(false)).expect("unmute");

    // The switches of the voice settings.
    talker.engine.set_bitrate_limit_kbps(Some(MOBILE_DATA_KBPS));
    block_on(talker.engine.set_noise_suppression(false)).expect("noise suppression");
    block_on(talker.engine.set_auto_gain(false)).expect("automatic gain");
    let settings = block_on(talker.engine.voice_settings()).expect("settings");
    assert_eq!(settings.bitrate_limit_kbps, Some(MOBILE_DATA_KBPS));
    assert_eq!(settings.talk_mode, TalkMode::PushToTalk);
    assert!(!settings.noise_suppression && !settings.auto_gain);
    talker.engine.set_push_to_talk_pressed(true);
    listener.hears("limited bitrate, processing off");
    talker.engine.set_push_to_talk_pressed(false);
    talker.engine.set_bitrate_limit_kbps(None);
    block_on(talker.engine.set_noise_suppression(true)).expect("noise suppression");
    block_on(talker.engine.set_auto_gain(true)).expect("automatic gain");
    let settings = block_on(talker.engine.voice_settings()).expect("settings");
    assert_eq!(settings.bitrate_limit_kbps, None);
    assert!(settings.noise_suppression && settings.auto_gain);

    // Leaving ends the wish for audio.
    let mark = talker.mark();
    block_on(talker.engine.disconnect()).expect("disconnect");
    talker.wait_audio(mark, false, false);
    assert_eq!(talker.engine.snapshot().audio, AudioWanted::default());

    // The audio threads never touched the heap inside the C functions.
    for phone in [&mut talker, &mut listener, &mut third] {
        assert_eq!(phone.stop_audio(), 0, "heap calls on an audio thread");
    }
}
