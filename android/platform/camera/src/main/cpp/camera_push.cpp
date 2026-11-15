// SPDX-License-Identifier: AGPL-3.0-only

// Hands one camera frame to the engine (bridge/include/mumcord_camera.h). The planes are the
// direct buffers of the camera's frame, so the engine reads them in place; nothing is copied
// here and nothing is allocated.
//
// Called on the camera's frame thread, never on an audio thread.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <cstdint>

#include "mumcord_camera.h"

namespace {

constexpr char kLogTag[] = "Mumcord";
constexpr char kEngineLibrary[] = "libmumcord_mobile.so";

using PushFunction = int32_t (*)(uint64_t, const MumcordCameraImage *);

std::atomic<PushFunction> g_push{nullptr};

// A direct buffer's memory and length; null for a heap buffer.
const uint8_t *address(JNIEnv *env, jobject buffer, size_t *length) {
    if (buffer == nullptr) return nullptr;
    auto *data = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity < 0) return nullptr;
    *length = static_cast<size_t>(capacity);
    return data;
}

} // namespace

extern "C" {

// Looks the engine's push function up in its library, as the video module does: this module
// builds without the engine, and a missing function is an error the app can log.
JNIEXPORT jboolean JNICALL
Java_app_mumcord_mobile_platform_camera_EngineCameraFrames_nativeBind(JNIEnv *, jobject) {
    if (g_push.load() != nullptr) return JNI_TRUE;
    void *library = dlopen(kEngineLibrary, RTLD_NOW);
    if (library == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "camera: the engine library is missing");
        return JNI_FALSE;
    }
    auto push = reinterpret_cast<PushFunction>(dlsym(library, "mumcord_camera_push_frame"));
    if (push == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "camera: the engine has no camera push");
        return JNI_FALSE;
    }
    g_push.store(push);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_app_mumcord_mobile_platform_camera_EngineCameraFrames_nativePush(
    JNIEnv *env, jobject, jlong handle, jint width, jint height, jint rotation, jobject y,
    jint y_stride, jobject u, jobject v, jint uv_stride, jint uv_pixel_stride) {
    const PushFunction push = g_push.load();
    if (push == nullptr) return MUMCORD_CAMERA_NOT_OPEN;
    MumcordCameraImage image{};
    image.y = address(env, y, &image.y_len);
    image.u = address(env, u, &image.u_len);
    image.v = address(env, v, &image.v_len);
    if (image.y == nullptr || image.u == nullptr || image.v == nullptr) return MUMCORD_CAMERA_INVALID;
    image.width = static_cast<uint32_t>(width);
    image.height = static_cast<uint32_t>(height);
    image.rotation = static_cast<uint32_t>(rotation);
    image.y_stride = static_cast<uint32_t>(y_stride);
    image.uv_stride = static_cast<uint32_t>(uv_stride);
    image.uv_pixel_stride = static_cast<uint32_t>(uv_pixel_stride);
    return push(static_cast<uint64_t>(handle), &image);
}

} // extern "C"
