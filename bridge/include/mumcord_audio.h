/* SPDX-License-Identifier: AGPL-3.0-only */

/*
 * Mumcord mobile bridge: the audio hot path.
 *
 * The app owns the platform audio (Android: Oboe/AAudio or AudioRecord/AudioTrack in
 * voice-communication mode; iOS: the voice-processing audio unit). Its microphone callback
 * pushes what it captured, its speaker callback pulls what to play. These four functions are
 * exported by the bridge library itself (`libmumcord_mobile.so`, `libmumcord_mobile.a`) and are
 * called directly from the real-time audio threads, never through the generated Kotlin or Swift
 * bindings (those allocate).
 *
 * HANDLES
 *   `MumcordEngine.audioEndpoints()` (generated bindings) returns two 64-bit handles, `capture`
 *   and `playback`. Pass `capture` to the push functions and `playback` to the pull functions,
 *   never the other way round and never a value from anywhere else. 0 is accepted and means
 *   "no engine".
 *
 * LIFETIME
 *   A handle is valid from the creation of its engine object until the process ends. After the
 *   engine object has been destroyed the functions are still safe to call: a push is dropped, a
 *   pull writes silence. The audio threads therefore need no ordering against the engine's
 *   teardown and no lock around these calls.
 *
 * FORMAT
 *   Interleaved PCM. Sample rate and channel count of each direction are fixed when the engine
 *   object is created (`EngineConfig.audio`; without it 48000 Hz mono in both directions) and
 *   every call must use them. 48000 Hz needs no resampling inside the engine. Captured
 *   channels are mixed down to mono. The i16 and f32 (-1.0 … 1.0) variants are equivalent; use
 *   the one the platform delivers.
 *   `sample_count` is the number of samples, not frames: frames × channels. Any block size up
 *   to 100 ms per call.
 *
 * THREADING
 *   - Real-time safe: no allocation, no lock, no logging, no system call that waits. They
 *     never call back into the app.
 *   - One caller at a time per handle: one thread pushes, one thread pulls (they may be
 *     different threads, and the thread may change between calls, e.g. after a route change).
 *     A call that overlaps another call on the same handle does not wait: the push is dropped,
 *     the pull writes silence.
 *
 * WHEN TO CALL
 *   Open and close the platform audio following the bridge's "audio wanted" state
 *   (`BridgeEvent.AudioWantedChanged`, `BridgeSnapshot.audio`): run the speaker callback while
 *   `playback` is wanted and the microphone callback while `capture` is wanted. Calling at any
 *   other time is harmless: while the engine's voice is not running a push is dropped and a
 *   pull writes silence. When pulling pauses for more than 250 ms the engine drops what queued
 *   up meanwhile, so playback resumes with current audio.
 *
 * A null `samples` pointer makes every function do nothing.
 */

#ifndef MUMCORD_AUDIO_H
#define MUMCORD_AUDIO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Hands `sample_count` captured samples to the engine (microphone callback). */
void mumcord_audio_capture_push_i16(uint64_t handle, const int16_t *samples, size_t sample_count);
void mumcord_audio_capture_push_f32(uint64_t handle, const float *samples, size_t sample_count);

/* Fills `samples` with `sample_count` samples to play (speaker callback). Always writes the
 * whole buffer; silence where the engine has nothing to play. */
void mumcord_audio_playback_pull_i16(uint64_t handle, int16_t *samples, size_t sample_count);
void mumcord_audio_playback_pull_f32(uint64_t handle, float *samples, size_t sample_count);

#ifdef __cplusplus
}
#endif

#endif /* MUMCORD_AUDIO_H */
