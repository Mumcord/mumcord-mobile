// SPDX-License-Identifier: AGPL-3.0-only

//! The C audio functions must not allocate or free, whatever state the engine is in: no voice
//! running, and the engine object released. With voice running the same is checked on the
//! audio threads of the live test (`live_voice`).

mod common;

use std::sync::Arc;

use common::{Counting, heap_calls};
use mumcord_mobile::{
    AudioConfig, BridgeEvent, EngineConfig, EngineListener, MumcordEngine, SecureStorage,
    SecureStorageError, mumcord_audio_capture_push_f32, mumcord_audio_capture_push_i16,
    mumcord_audio_playback_pull_f32, mumcord_audio_playback_pull_i16,
};

#[global_allocator]
static GLOBAL: Counting = Counting;

struct NoStorage;

impl SecureStorage for NoStorage {
    fn set(&self, _account: String, _secret: Vec<u8>) -> Result<(), SecureStorageError> {
        Ok(())
    }

    fn get(&self, _account: String) -> Result<Option<Vec<u8>>, SecureStorageError> {
        Ok(None)
    }

    fn delete(&self, _account: String) -> Result<(), SecureStorageError> {
        Ok(())
    }
}

struct Ignore;

impl EngineListener for Ignore {
    fn on_event(&self, _event: BridgeEvent) {}
}

/// Pushes and pulls 100 blocks of 10 ms in both sample types; returns the heap calls it made
/// and whether everything pulled was silence.
fn run_callbacks(capture: u64, playback: u64) -> (usize, bool) {
    let captured_i16 = [1000_i16; 480];
    let captured_f32 = [0.25_f32; 480];
    let mut played_i16 = [1_i16; 960];
    let mut played_f32 = [1.0_f32; 960];
    let before = heap_calls();
    for _ in 0..100 {
        // SAFETY: the handles are those of `audio_endpoints`, the buffers are live arrays.
        unsafe {
            mumcord_audio_capture_push_i16(capture, captured_i16.as_ptr(), captured_i16.len());
            mumcord_audio_capture_push_f32(capture, captured_f32.as_ptr(), captured_f32.len());
            mumcord_audio_playback_pull_i16(playback, played_i16.as_mut_ptr(), played_i16.len());
            mumcord_audio_playback_pull_f32(playback, played_f32.as_mut_ptr(), played_f32.len());
        }
    }
    let heap = heap_calls() - before;
    let silent = played_i16.iter().all(|&s| s == 0) && played_f32.iter().all(|&s| s == 0.0);
    (heap, silent)
}

#[test]
fn the_audio_functions_never_touch_the_heap() {
    let dir = std::path::Path::new(env!("CARGO_TARGET_TMPDIR")).join("bridge-audio-no-alloc");
    let _ = std::fs::remove_dir_all(&dir);
    let engine = MumcordEngine::new(
        EngineConfig {
            data_dir: dir.to_string_lossy().into_owned(),
            audio: Some(AudioConfig {
                capture_rate: 48_000,
                capture_channels: 1,
                playback_rate: 48_000,
                playback_channels: 2,
            }),
        },
        Arc::new(NoStorage),
        Arc::new(Ignore),
    )
    .expect("engine");
    let handles = engine.audio_endpoints();
    assert_ne!(handles.capture, 0);
    assert_ne!(handles.playback, 0);
    assert_ne!(handles.capture, handles.playback);
    assert_eq!(engine.audio_endpoints(), handles);

    // Not connected: nothing is sent, silence is played.
    assert_eq!(run_callbacks(handles.capture, handles.playback), (0, true));

    // The handles outlive the engine object.
    drop(engine);
    assert_eq!(run_callbacks(handles.capture, handles.playback), (0, true));
}

#[test]
fn an_audio_layout_the_engine_cannot_use_is_refused() {
    let dir = std::path::Path::new(env!("CARGO_TARGET_TMPDIR")).join("bridge-audio-layout");
    let result = MumcordEngine::new(
        EngineConfig {
            data_dir: dir.to_string_lossy().into_owned(),
            audio: Some(AudioConfig {
                capture_rate: 48_000,
                capture_channels: 0,
                playback_rate: 48_000,
                playback_channels: 1,
            }),
        },
        Arc::new(NoStorage),
        Arc::new(Ignore),
    );
    assert!(
        matches!(
            result,
            Err(mumcord_mobile::BridgeError::InvalidInput { .. })
        ),
        "{:?}",
        result.err()
    );
}
