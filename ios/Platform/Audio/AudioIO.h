/* SPDX-License-Identifier: AGPL-3.0-only */

/*
 * The phone's speaker and microphone for voice: one audio unit whose two callbacks exchange
 * samples with the engine (`bridge/include/mumcord_audio.h`).
 *
 * The callbacks are written in C on purpose. They run on the system's real-time audio thread,
 * where nothing may allocate, lock, log or wait; Swift code can do any of these behind the
 * scenes (reference counting, runtime lookups), plain C cannot.
 *
 * Everything here except the callbacks is called from one thread at a time (the queue of
 * `AudioController`), never from an audio thread.
 */

#ifndef MUMCORD_AUDIO_IO_H
#define MUMCORD_AUDIO_IO_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* What `mumcord_io_open` asks for. */
typedef struct {
    /* `AudioEndpoints.playback` and `.capture` of the engine. */
    uint64_t playback_handle;
    uint64_t capture_handle;
    /* Whether the engine's audio is played; otherwise the speaker gets silence. */
    bool playback;
    /* Whether the microphone is opened and what it records goes to the engine. */
    bool capture;
    /* The system's voice processing (echo cancellation); plain input and output without it. */
    bool voice_processing;
} MumcordIORequest;

/* What a running unit got. */
typedef struct {
    /* The sample rate of the samples exchanged with the engine. */
    double sample_rate;
    /*
     * True when the microphone is open. It is also true when capture was not asked for but the
     * unit refused to run without its input; then nothing recorded leaves this file.
     */
    bool input_open;
} MumcordIOFormat;

/*
 * Creates, configures and starts the unit. Returns 0 and fills `format`, or the system's error
 * code; `failed_step` then names the step that failed (for the log only). A unit that is
 * already open is closed first.
 */
int32_t mumcord_io_open(MumcordIORequest request, MumcordIOFormat *format, int32_t *failed_step);

/* Stops and disposes of the unit. Does nothing when it is not open. */
void mumcord_io_close(void);

/*
 * Test probe for automated tests on a device without a microphone or a listener. Only debug
 * builds contain these functions; they are declared without a condition because the Swift
 * side, which is itself debug-only, has to see them.
 */

/* 0 = what the device recorded, 1 = a generated speech-like signal, 2 = silence. */
void mumcord_io_probe_replace_microphone(int32_t signal);

/* The loudest block (RMS, 0 to 1) played since the last call; 0 for silence. */
float mumcord_io_probe_take_playback_level(void);

/* How many frames the direction (0 = playback, 1 = capture) has exchanged with the engine. */
uint64_t mumcord_io_probe_frames(int32_t direction);

/*
 * Runs what the callbacks do with `frames` frames, without a unit: pushes the generated
 * speech-like signal to the engine, or pulls from the engine and returns the block's level.
 * Only while no unit is open.
 */
void mumcord_io_probe_push(uint64_t capture_handle, uint32_t frames);
float mumcord_io_probe_pull(uint64_t playback_handle, uint32_t frames);

#ifdef __cplusplus
}
#endif

#endif /* MUMCORD_AUDIO_IO_H */
