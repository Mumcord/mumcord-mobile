// SPDX-License-Identifier: AGPL-3.0-only

// Takes the newest picture of a watched stream from the engine (bridge/include/mumcord_video.h)
// and uploads its Y, U and V planes into three single-channel textures of the calling thread's
// OpenGL ES context. The planes are read straight from the engine's memory, so the picture is
// copied once, into the textures, and given back right after.
//
// Called only on a render thread of the app, never on an audio thread.

#include <GLES3/gl3.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <cstdint>

#include "mumcord_video.h"

namespace {

constexpr char kLogTag[] = "Mumcord";
constexpr char kEngineLibrary[] = "libmumcord_mobile.so";

using AcquireFunction = bool (*)(uint64_t, uint64_t, MumcordVideoFrame *);
using ReleaseFunction = void (*)(uint64_t, uint64_t);

std::atomic<AcquireFunction> g_acquire{nullptr};
std::atomic<ReleaseFunction> g_release{nullptr};

// Uploads one plane. A texture whose size differs from the plane's is allocated anew; otherwise
// only its pixels are replaced.
void upload_plane(GLuint texture, jint *size, uint32_t width, uint32_t height, uint32_t stride,
                  const uint8_t *pixels) {
    glBindTexture(GL_TEXTURE_2D, texture);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, static_cast<GLint>(stride));
    const auto w = static_cast<GLsizei>(width);
    const auto h = static_cast<GLsizei>(height);
    if (size[0] != w || size[1] != h) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, w, h, 0, GL_RED, GL_UNSIGNED_BYTE, pixels);
        size[0] = w;
        size[1] = h;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RED, GL_UNSIGNED_BYTE, pixels);
    }
}

} // namespace

extern "C" {

// Looks the engine's picture functions up in its library. Done at run time instead of linking
// against the library: this module then builds without the engine, and a missing function is an
// error the app can log instead of a crash while loading.
JNIEXPORT jboolean JNICALL
Java_app_mumcord_mobile_platform_video_EngineVideoFrames_nativeBind(JNIEnv *, jobject) {
    if (g_acquire.load() != nullptr && g_release.load() != nullptr) return JNI_TRUE;
    void *library = dlopen(kEngineLibrary, RTLD_NOW);
    if (library == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "video: the engine library is missing");
        return JNI_FALSE;
    }
    auto acquire = reinterpret_cast<AcquireFunction>(dlsym(library, "mumcord_video_frame_acquire"));
    auto release = reinterpret_cast<ReleaseFunction>(dlsym(library, "mumcord_video_frame_release"));
    if (acquire == nullptr || release == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "video: the engine has no picture functions");
        return JNI_FALSE;
    }
    g_acquire.store(acquire);
    g_release.store(release);
    return JNI_TRUE;
}

// `textures`: the Y, U and V texture names. `sizes`: width and height of each texture as last
// uploaded (6 values), updated here. Returns the picture's width and height packed as
// (width << 32 | height), or 0 when there is no new picture.
JNIEXPORT jlong JNICALL
Java_app_mumcord_mobile_platform_video_EngineVideoFrames_nativeUpload(
    JNIEnv *env, jobject, jlong handle, jlong video_id, jintArray textures, jintArray sizes) {
    AcquireFunction acquire = g_acquire.load();
    ReleaseFunction release = g_release.load();
    if (acquire == nullptr || release == nullptr) return 0;
    MumcordVideoFrame frame{};
    if (!acquire(static_cast<uint64_t>(handle), static_cast<uint64_t>(video_id), &frame)) return 0;
    jint names[3];
    env->GetIntArrayRegion(textures, 0, 3, names);
    jint size[6];
    env->GetIntArrayRegion(sizes, 0, 6, size);
    const uint32_t chroma_width = (frame.width + 1) / 2;
    const uint32_t chroma_height = (frame.height + 1) / 2;
    upload_plane(static_cast<GLuint>(names[0]), &size[0], frame.width, frame.height, frame.stride_y, frame.y);
    upload_plane(static_cast<GLuint>(names[1]), &size[2], chroma_width, chroma_height, frame.stride_u, frame.u);
    upload_plane(static_cast<GLuint>(names[2]), &size[4], chroma_width, chroma_height, frame.stride_v, frame.v);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    // Without a pixel unpack buffer OpenGL has read the client memory when the upload returns,
    // so the picture can go back to the engine at once.
    release(static_cast<uint64_t>(handle), frame.token);
    env->SetIntArrayRegion(sizes, 0, 6, size);
    return (static_cast<jlong>(frame.width) << 32) | static_cast<jlong>(frame.height);
}

} // extern "C"
