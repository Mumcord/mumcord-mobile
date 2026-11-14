// SPDX-License-Identifier: AGPL-3.0-only

// The phone's microphone and speaker streams for voice, on AAudio.
//
// The data callbacks run on the system's real-time audio threads. They only call the engine's C
// functions (bridge/include/mumcord_audio.h), which never wait: no JNI, no allocation, no lock
// and no logging happens there. Everything else (opening, closing, reporting a lost stream) runs
// on the threads of the Kotlin controller or on AAudio's own error thread.

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstdint>

#include "mumcord_audio.h"

namespace {

constexpr char kLogTag[] = "Mumcord";
constexpr char kEngineLibrary[] = "libmumcord_mobile.so";

// The format the engine uses without further configuration. AAudio converts when the device
// runs at something else, so the engine never has to be told about a device change.
constexpr int32_t kSampleRate = 48000;
constexpr int32_t kChannels = 1;

constexpr int kPlayback = 0;
constexpr int kCapture = 1;

// Result of open() when the engine's library or its functions are missing; AAudio's own error
// codes are small negative numbers.
constexpr jint kNoEngine = -10000;

using PushFunction = decltype(&mumcord_audio_capture_push_i16);
using PullFunction = decltype(&mumcord_audio_playback_pull_i16);

// Set once before any stream exists and never changed afterwards.
PushFunction g_push = nullptr;
PullFunction g_pull = nullptr;

struct Stream {
    AAudioStream *stream = nullptr;
    // Fixed while the stream is open: the data callback reads it without synchronisation.
    uint64_t handle = 0;
    // Counts the opens, so a late error of an earlier stream is told apart from the current one.
    int32_t generation = 0;
};

// One per direction, only touched by the controller's thread (and read by the callbacks).
Stream g_streams[2];

JavaVM *g_vm = nullptr;
jclass g_native_audio = nullptr;
jmethodID g_on_stream_error = nullptr;

#ifdef MUMCORD_AUDIO_PROBE

// Test probe, debug builds only. Atomics, so the callbacks stay free of locks.
// What the microphone callback sends in place of the device's input: 0 the device's own
// input, 1 the generated speech-like signal, 2 silence.
std::atomic<int> g_probe_signal{0};
// Loudest block the speaker callback pulled since the level was last read (RMS, 0 … 1).
std::atomic<float> g_probe_level{0.0f};
std::atomic<int64_t> g_probe_frames[2];

// State of the generated signal; only the microphone callback touches it.
float g_probe_phase = 0.0f;
int64_t g_probe_position = 0;

// A voiced, 4 Hz pulsed signal that noise suppression and voice activation treat as speech; a
// plain sine tone would be removed as noise. The same signal as in bridge/tests/live_voice.rs.
void probe_fill(int16_t *samples, int32_t count) {
    constexpr float kTwoPi = 6.28318530718f;
    constexpr float kAmplitude = 0.5f;
    for (int32_t i = 0; i < count; i++) {
        const float t = static_cast<float>(g_probe_position++) / kSampleRate;
        const float pitch = 140.0f + 20.0f * std::sin(kTwoPi * 0.7f * t);
        g_probe_phase += kTwoPi * pitch / kSampleRate;
        if (g_probe_phase > kTwoPi) g_probe_phase -= kTwoPi;
        float voiced = 0.0f;
        for (int harmonic = 1; harmonic <= 20; harmonic++) {
            voiced += std::sin(static_cast<float>(harmonic) * g_probe_phase) / static_cast<float>(harmonic);
        }
        const float pulse = std::sin(kTwoPi * 4.0f * t);
        const float envelope = pulse > 0.0f ? std::sqrt(pulse) : 0.0f;
        float value = kAmplitude * envelope * voiced / 2.0f;
        if (value > 1.0f) value = 1.0f;
        if (value < -1.0f) value = -1.0f;
        samples[i] = static_cast<int16_t>(value * 32767.0f);
    }
}

void probe_meter(const int16_t *samples, int32_t count) {
    if (count <= 0) return;
    float sum = 0.0f;
    for (int32_t i = 0; i < count; i++) {
        const float value = static_cast<float>(samples[i]) / 32768.0f;
        sum += value * value;
    }
    const float level = std::sqrt(sum / static_cast<float>(count));
    if (level > g_probe_level.load(std::memory_order_relaxed)) {
        g_probe_level.store(level, std::memory_order_relaxed);
    }
}

#endif // MUMCORD_AUDIO_PROBE

// Speaker callback (real-time thread).
aaudio_data_callback_result_t on_playback(AAudioStream *, void *user, void *audio, int32_t frames) {
    const auto *stream = static_cast<const Stream *>(user);
    auto *samples = static_cast<int16_t *>(audio);
    g_pull(stream->handle, samples, static_cast<size_t>(frames) * kChannels);
#ifdef MUMCORD_AUDIO_PROBE
    probe_meter(samples, frames * kChannels);
    g_probe_frames[kPlayback].fetch_add(frames, std::memory_order_relaxed);
#endif
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// Microphone callback (real-time thread).
aaudio_data_callback_result_t on_capture(AAudioStream *, void *user, void *audio, int32_t frames) {
    const auto *stream = static_cast<const Stream *>(user);
    auto *samples = static_cast<int16_t *>(audio);
#ifdef MUMCORD_AUDIO_PROBE
    const int signal = g_probe_signal.load(std::memory_order_relaxed);
    if (signal == 1) {
        probe_fill(samples, frames * kChannels);
    } else if (signal == 2) {
        for (int32_t i = 0; i < frames * kChannels; i++) samples[i] = 0;
    }
    g_probe_frames[kCapture].fetch_add(frames, std::memory_order_relaxed);
#endif
    g_push(stream->handle, samples, static_cast<size_t>(frames) * kChannels);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// Tells the Kotlin controller that a stream is lost. Never called from a data callback.
void report_error(int direction, int32_t generation, aaudio_result_t error) {
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "audio: cannot report a lost stream");
            return;
        }
        attached = true;
    }
    env->CallStaticVoidMethod(g_native_audio, g_on_stream_error, direction, generation, error);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attached) g_vm->DetachCurrentThread();
}

// A stream stopped working, typically because its device went away (headset plugged or
// unplugged). AAudio calls this on a thread of its own, where the stream must not be closed:
// the controller closes and reopens it on its thread.
void on_error(AAudioStream *, void *user, aaudio_result_t error) {
    const auto packed = reinterpret_cast<intptr_t>(user);
    report_error(static_cast<int>(packed & 1), static_cast<int32_t>(packed >> 1), error);
}

void close_stream(Stream &stream) {
    if (stream.stream == nullptr) return;
    AAudioStream_requestStop(stream.stream);
    // Returns after the last data callback has finished.
    AAudioStream_close(stream.stream);
    stream.stream = nullptr;
}

// Opens and starts the stream of one direction. Returns its generation (> 0) or an error (< 0).
jint open_stream(int direction, uint64_t handle, jint *info) {
    if (g_push == nullptr || g_pull == nullptr) return kNoEngine;
    Stream &stream = g_streams[direction];
    close_stream(stream);
    stream.handle = handle;
    const int32_t generation = ++stream.generation;

    AAudioStreamBuilder *builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return result;
    AAudioStreamBuilder_setSampleRate(builder, kSampleRate);
    AAudioStreamBuilder_setChannelCount(builder, kChannels);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    if (direction == kCapture) {
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
        // Voice communication: the phone applies its own echo canceller and noise handling.
        AAudioStreamBuilder_setInputPreset(builder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
        // The low-latency input path bypasses that processing on many phones, and the 10 ms
        // and longer voice packets outweigh what it would save.
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
        AAudioStreamBuilder_setDataCallback(builder, on_capture, &stream);
    } else {
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_VOICE_COMMUNICATION);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SPEECH);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setDataCallback(builder, on_playback, &stream);
    }
    AAudioStreamBuilder_setErrorCallback(
        builder, on_error, reinterpret_cast<void *>((static_cast<intptr_t>(generation) << 1) | direction));
    result = AAudioStreamBuilder_openStream(builder, &stream.stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        stream.stream = nullptr;
        return result;
    }

    // The engine was told this format; anything else would play at the wrong speed.
    if (AAudioStream_getSampleRate(stream.stream) != kSampleRate ||
        AAudioStream_getChannelCount(stream.stream) != kChannels ||
        AAudioStream_getFormat(stream.stream) != AAUDIO_FORMAT_PCM_I16) {
        close_stream(stream);
        return AAUDIO_ERROR_INVALID_FORMAT;
    }
    const int32_t burst = AAudioStream_getFramesPerBurst(stream.stream);
    if (direction == kPlayback) {
        // Two bursts: the smallest buffer that survives one late callback without a gap.
        AAudioStream_setBufferSizeInFrames(stream.stream, burst * 2);
    }
    result = AAudioStream_requestStart(stream.stream);
    if (result != AAUDIO_OK) {
        close_stream(stream);
        return result;
    }
    info[0] = AAudioStream_getSampleRate(stream.stream);
    info[1] = burst;
    info[2] = AAudioStream_getPerformanceMode(stream.stream) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY ? 1 : 0;
    return generation;
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("app/mumcord/mobile/platform/audio/NativeAudio");
    if (local == nullptr) return JNI_ERR;
    g_native_audio = static_cast<jclass>(env->NewGlobalRef(local));
    g_on_stream_error = env->GetStaticMethodID(g_native_audio, "onStreamError", "(III)V");
    if (g_on_stream_error == nullptr) return JNI_ERR;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// Looks the engine's audio functions up in its library. Done at run time instead of linking
// against the library: this module then builds and tests without the engine, and a missing
// function is an error the app can show instead of a crash while loading.
JNIEXPORT jboolean JNICALL
Java_app_mumcord_mobile_platform_audio_NativeAudio_nativeBind(JNIEnv *, jobject) {
    if (g_push != nullptr && g_pull != nullptr) return JNI_TRUE;
    void *library = dlopen(kEngineLibrary, RTLD_NOW);
    if (library == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "audio: the engine library is missing");
        return JNI_FALSE;
    }
    auto push = reinterpret_cast<PushFunction>(dlsym(library, "mumcord_audio_capture_push_i16"));
    auto pull = reinterpret_cast<PullFunction>(dlsym(library, "mumcord_audio_playback_pull_i16"));
    if (push == nullptr || pull == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "audio: the engine has no audio functions");
        return JNI_FALSE;
    }
    g_push = push;
    g_pull = pull;
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_app_mumcord_mobile_platform_audio_NativeAudio_nativeOpen(
    JNIEnv *env, jobject, jint direction, jlong handle, jintArray info) {
    jint values[3] = {0, 0, 0};
    const jint result = open_stream(direction == kCapture ? kCapture : kPlayback, static_cast<uint64_t>(handle), values);
    if (result > 0) env->SetIntArrayRegion(info, 0, 3, values);
    return result;
}

JNIEXPORT void JNICALL
Java_app_mumcord_mobile_platform_audio_NativeAudio_nativeClose(JNIEnv *, jobject, jint direction) {
    close_stream(g_streams[direction == kCapture ? kCapture : kPlayback]);
}

#ifdef MUMCORD_AUDIO_PROBE

JNIEXPORT void JNICALL
Java_app_mumcord_mobile_platform_audio_AudioProbe_nativeReplaceMicrophone(JNIEnv *, jobject, jint signal) {
    g_probe_signal.store(signal, std::memory_order_relaxed);
}

JNIEXPORT jfloat JNICALL
Java_app_mumcord_mobile_platform_audio_AudioProbe_nativeTakeLevel(JNIEnv *, jobject) {
    return g_probe_level.exchange(0.0f, std::memory_order_relaxed);
}

JNIEXPORT jlong JNICALL
Java_app_mumcord_mobile_platform_audio_AudioProbe_nativeFrames(JNIEnv *, jobject, jint direction) {
    return g_probe_frames[direction == kCapture ? kCapture : kPlayback].load(std::memory_order_relaxed);
}

// Reports the current stream of a direction as lost, the way AAudio does after a device change.
JNIEXPORT void JNICALL
Java_app_mumcord_mobile_platform_audio_AudioProbe_nativeLoseStream(JNIEnv *, jobject, jint direction) {
    const int index = direction == kCapture ? kCapture : kPlayback;
    report_error(index, g_streams[index].generation, AAUDIO_ERROR_DISCONNECTED);
}

#endif // MUMCORD_AUDIO_PROBE

} // extern "C"
