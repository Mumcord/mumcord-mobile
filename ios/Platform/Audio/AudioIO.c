/* SPDX-License-Identifier: AGPL-3.0-only */

#include "AudioIO.h"

#include <AudioToolbox/AudioToolbox.h>
#include <math.h>
#include <stdatomic.h>
#include <string.h>

#include "mumcord_audio.h"

/* The engine's format: 48 kHz mono needs no resampling inside the engine. The unit converts
 * between this and whatever the device runs at. */
#define SAMPLE_RATE 48000.0
/* More than the system ever asks for in one callback (4096 with a locked screen). */
#define MAX_FRAMES 4096

#define OUTPUT_BUS 0
#define INPUT_BUS 1

enum {
    STEP_FIND = 1,
    STEP_CREATE,
    STEP_ENABLE_INPUT,
    STEP_OUTPUT_FORMAT,
    STEP_INPUT_FORMAT,
    STEP_MAX_FRAMES,
    STEP_OUTPUT_CALLBACK,
    STEP_INPUT_CALLBACK,
    STEP_INITIALIZE,
    STEP_START,
};

static AudioUnit g_unit = NULL;

/* Written before the unit starts and after it stopped, read by the callbacks in between. */
static uint64_t g_playback_handle = 0;
static uint64_t g_capture_handle = 0;
static bool g_playback = false;
static bool g_capture = false;

/* Where the microphone's samples land before they go to the engine. Only the input callback
 * touches it while the unit runs. */
static float g_capture_buffer[MAX_FRAMES];

#if DEBUG
static atomic_int g_probe_signal = 0;
static _Atomic uint64_t g_playback_frames = 0;
static _Atomic uint64_t g_capture_frames = 0;
/* The level as the bits of a float, so it can be exchanged without a lock. */
static _Atomic uint32_t g_playback_level_bits = 0;
static double g_probe_phase = 0.0;

static float block_level(const float *samples, uint32_t frames) {
    if (frames == 0) return 0.0f;
    double sum = 0.0;
    for (uint32_t i = 0; i < frames; i++) sum += (double)samples[i] * (double)samples[i];
    return (float)sqrt(sum / frames);
}

/* A 150 Hz tone with overtones that swells four times a second: loud and voiced enough for
 * the engine's voice detection to take it for somebody talking. */
static void generate_speech(float *samples, uint32_t frames) {
    for (uint32_t i = 0; i < frames; i++) {
        double t = g_probe_phase / SAMPLE_RATE;
        double envelope = 0.5 + 0.5 * sin(2.0 * M_PI * 4.0 * t);
        double tone = 0.5 * sin(2.0 * M_PI * 150.0 * t) + 0.3 * sin(2.0 * M_PI * 300.0 * t) +
                      0.2 * sin(2.0 * M_PI * 450.0 * t);
        samples[i] = (float)(0.6 * envelope * tone);
        g_probe_phase += 1.0;
    }
}
#endif

/* Hands recorded samples to the engine. Real-time safe. */
static void deliver_capture(uint64_t handle, float *samples, uint32_t frames) {
#if DEBUG
    int signal = atomic_load_explicit(&g_probe_signal, memory_order_relaxed);
    if (signal == 1) {
        generate_speech(samples, frames);
    } else if (signal == 2) {
        memset(samples, 0, frames * sizeof(float));
    }
    atomic_fetch_add_explicit(&g_capture_frames, frames, memory_order_relaxed);
#endif
    mumcord_audio_capture_push_f32(handle, samples, frames);
}

/* Fills the buffer with what the engine wants played. Real-time safe. */
static void fill_playback(uint64_t handle, float *samples, uint32_t frames) {
    mumcord_audio_playback_pull_f32(handle, samples, frames);
#if DEBUG
    atomic_fetch_add_explicit(&g_playback_frames, frames, memory_order_relaxed);
    float level = block_level(samples, frames);
    uint32_t bits = atomic_load_explicit(&g_playback_level_bits, memory_order_relaxed);
    float loudest;
    memcpy(&loudest, &bits, sizeof(loudest));
    if (level > loudest) {
        memcpy(&bits, &level, sizeof(bits));
        atomic_store_explicit(&g_playback_level_bits, bits, memory_order_relaxed);
    }
#endif
}

/* The speaker asks for samples. Runs on the real-time audio thread. */
static OSStatus render_output(void *context, AudioUnitRenderActionFlags *flags,
                              const AudioTimeStamp *time, UInt32 bus, UInt32 frames,
                              AudioBufferList *data) {
    (void)context; (void)flags; (void)time; (void)bus;
    for (UInt32 i = 0; i < data->mNumberBuffers; i++) {
        AudioBuffer *buffer = &data->mBuffers[i];
        if (buffer->mData == NULL) continue;
        UInt32 available = buffer->mDataByteSize / sizeof(float);
        UInt32 count = frames < available ? frames : available;
        // The format is mono, so there is one buffer; any other gets silence.
        if (i == 0 && g_playback) {
            fill_playback(g_playback_handle, (float *)buffer->mData, count);
        } else {
            memset(buffer->mData, 0, buffer->mDataByteSize);
        }
    }
    return noErr;
}

/* The microphone has samples. Runs on the real-time audio thread. */
static OSStatus input_ready(void *context, AudioUnitRenderActionFlags *flags,
                            const AudioTimeStamp *time, UInt32 bus, UInt32 frames,
                            AudioBufferList *data) {
    (void)data;
    AudioUnit unit = (AudioUnit)context;
    if (frames > MAX_FRAMES) return noErr;
    AudioBufferList list;
    list.mNumberBuffers = 1;
    list.mBuffers[0].mNumberChannels = 1;
    list.mBuffers[0].mDataByteSize = frames * sizeof(float);
    list.mBuffers[0].mData = g_capture_buffer;
    OSStatus status = AudioUnitRender(unit, flags, time, bus, frames, &list);
    // The samples are fetched in any case, so the unit's input does not back up; they only
    // leave this function when the engine wants them.
    if (status == noErr && g_capture) {
        deliver_capture(g_capture_handle, g_capture_buffer, frames);
    }
    return status;
}

static AudioStreamBasicDescription engine_format(void) {
    AudioStreamBasicDescription format;
    memset(&format, 0, sizeof(format));
    format.mSampleRate = SAMPLE_RATE;
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kAudioFormatFlagsNativeFloatPacked;
    format.mChannelsPerFrame = 1;
    format.mBitsPerChannel = 32;
    format.mBytesPerFrame = sizeof(float);
    format.mBytesPerPacket = sizeof(float);
    format.mFramesPerPacket = 1;
    return format;
}

/* One try to build and start a unit with the microphone open or not. */
static OSStatus start_unit(bool voice_processing, bool input, int32_t *failed_step) {
    AudioComponentDescription description;
    memset(&description, 0, sizeof(description));
    description.componentType = kAudioUnitType_Output;
    description.componentSubType =
        voice_processing ? kAudioUnitSubType_VoiceProcessingIO : kAudioUnitSubType_RemoteIO;
    description.componentManufacturer = kAudioUnitManufacturer_Apple;

    *failed_step = STEP_FIND;
    AudioComponent component = AudioComponentFindNext(NULL, &description);
    if (component == NULL) return kAudioUnitErr_NoConnection;

    AudioUnit unit = NULL;
    *failed_step = STEP_CREATE;
    OSStatus status = AudioComponentInstanceNew(component, &unit);
    if (status != noErr) return status;

    UInt32 enable = input ? 1 : 0;
    AudioStreamBasicDescription format = engine_format();
    UInt32 max_frames = MAX_FRAMES;
    AURenderCallbackStruct output_callback = {render_output, NULL};
    AURenderCallbackStruct input_callback = {input_ready, unit};

    *failed_step = STEP_ENABLE_INPUT;
    status = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Input,
                                  INPUT_BUS, &enable, sizeof(enable));
    if (status == noErr) {
        *failed_step = STEP_OUTPUT_FORMAT;
        status = AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input,
                                      OUTPUT_BUS, &format, sizeof(format));
    }
    if (status == noErr && input) {
        *failed_step = STEP_INPUT_FORMAT;
        status = AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Output,
                                      INPUT_BUS, &format, sizeof(format));
    }
    if (status == noErr) {
        *failed_step = STEP_MAX_FRAMES;
        status = AudioUnitSetProperty(unit, kAudioUnitProperty_MaximumFramesPerSlice,
                                      kAudioUnitScope_Global, 0, &max_frames, sizeof(max_frames));
    }
    if (status == noErr) {
        *failed_step = STEP_OUTPUT_CALLBACK;
        status = AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback,
                                      kAudioUnitScope_Input, OUTPUT_BUS, &output_callback,
                                      sizeof(output_callback));
    }
    if (status == noErr && input) {
        *failed_step = STEP_INPUT_CALLBACK;
        status = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_SetInputCallback,
                                      kAudioUnitScope_Global, 0, &input_callback,
                                      sizeof(input_callback));
    }
    if (status == noErr) {
        *failed_step = STEP_INITIALIZE;
        status = AudioUnitInitialize(unit);
    }
    if (status == noErr) {
        *failed_step = STEP_START;
        status = AudioOutputUnitStart(unit);
        if (status != noErr) AudioUnitUninitialize(unit);
    }
    if (status != noErr) {
        AudioComponentInstanceDispose(unit);
        return status;
    }
    *failed_step = 0;
    g_unit = unit;
    return noErr;
}

int32_t mumcord_io_open(MumcordIORequest request, MumcordIOFormat *format, int32_t *failed_step) {
    mumcord_io_close();
    g_playback_handle = request.playback_handle;
    g_capture_handle = request.capture_handle;
    g_playback = request.playback;
    g_capture = request.capture;

    bool input = request.capture;
    OSStatus status = start_unit(request.voice_processing, input, failed_step);
    if (status != noErr && !input) {
        // Listening only, and the unit does not run without its microphone side: open it
        // anyway. `g_capture` stays false, so nothing recorded goes anywhere.
        input = true;
        status = start_unit(request.voice_processing, input, failed_step);
    }
    if (status != noErr) {
        g_playback = false;
        g_capture = false;
        return status;
    }
    format->sample_rate = SAMPLE_RATE;
    format->input_open = input;
    return noErr;
}

void mumcord_io_close(void) {
    if (g_unit == NULL) return;
    // Returns once no callback runs any more.
    AudioOutputUnitStop(g_unit);
    AudioUnitUninitialize(g_unit);
    AudioComponentInstanceDispose(g_unit);
    g_unit = NULL;
    g_playback = false;
    g_capture = false;
}

#if DEBUG

void mumcord_io_probe_replace_microphone(int32_t signal) {
    atomic_store_explicit(&g_probe_signal, signal, memory_order_relaxed);
}

float mumcord_io_probe_take_playback_level(void) {
    uint32_t bits = atomic_exchange_explicit(&g_playback_level_bits, 0, memory_order_relaxed);
    float level;
    memcpy(&level, &bits, sizeof(level));
    return level;
}

uint64_t mumcord_io_probe_frames(int32_t direction) {
    return atomic_load_explicit(direction == 0 ? &g_playback_frames : &g_capture_frames,
                                memory_order_relaxed);
}

void mumcord_io_probe_push(uint64_t capture_handle, uint32_t frames) {
    if (g_unit != NULL) return;
    uint32_t count = frames < MAX_FRAMES ? frames : MAX_FRAMES;
    generate_speech(g_capture_buffer, count);
    deliver_capture(capture_handle, g_capture_buffer, count);
}

float mumcord_io_probe_pull(uint64_t playback_handle, uint32_t frames) {
    if (g_unit != NULL) return 0.0f;
    uint32_t count = frames < MAX_FRAMES ? frames : MAX_FRAMES;
    fill_playback(playback_handle, g_capture_buffer, count);
    return block_level(g_capture_buffer, count);
}

#endif
