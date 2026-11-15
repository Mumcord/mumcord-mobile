/* SPDX-License-Identifier: AGPL-3.0-only */

/*
 * Mumcord mobile bridge: the pictures of the user's own camera.
 *
 * The engine encodes and sends the camera (VP9); the app captures it. The engine starts and
 * stops the app's camera through `CameraCapture` (generated bindings, set with
 * `MumcordEngine.setCameraCapture`). While it runs, the app's camera thread hands every picture
 * to `mumcord_camera_push_frame`. Nothing is sent before the user starts sharing in the
 * preview. Picture bytes never pass through the generated bindings.
 *
 * HANDLE
 *   `MumcordEngine.cameraHandle()` (generated bindings). A handle is valid from the creation of
 *   its engine object until the process ends; 0 is accepted and refuses every picture.
 *
 * FORMAT
 *   YUV 4:2:0, 8 bit, as the camera delivers it (Android's YUV_420_888): a Y plane with its row
 *   stride (pixel stride 1) and U and V planes at half width and half height (rounded up) with a
 *   shared row stride and a pixel stride of 1 (I420) or 2 (NV12, NV21). `rotation` is the
 *   clockwise turn in degrees (0, 90, 180, 270) that makes the picture upright. The bridge turns
 *   it upright, cuts out the middle 16:9 and scales it down to at most 640 x 360 (the software
 *   encoder's size). Each side at most 4096. The size may change from one picture to the next
 *   (front and back camera).
 *
 * BACKPRESSURE
 *   Pictures are never queued in the app and never waited for: when the encoder is behind, or
 *   the camera is just opening or closing, the picture is dropped (MUMCORD_CAMERA_DROPPED).
 *
 * THREADING
 *   Call from one camera thread at a time. The call never blocks, allocates, logs or calls back
 *   into the app; the planes are only read during the call.
 */

#ifndef MUMCORD_CAMERA_H
#define MUMCORD_CAMERA_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* One camera picture. Row `r` of the Y plane starts at `y + r * y_stride`; chroma sample `c`
 * of chroma row `r` is at `u + r * uv_stride + c * uv_pixel_stride` (V likewise). Each plane is
 * readable for its `_len` bytes. */
typedef struct MumcordCameraImage {
    uint32_t width;
    uint32_t height;
    uint32_t rotation;
    const uint8_t *y;
    size_t y_len;
    uint32_t y_stride;
    const uint8_t *u;
    size_t u_len;
    const uint8_t *v;
    size_t v_len;
    uint32_t uv_stride;
    uint32_t uv_pixel_stride;
} MumcordCameraImage;

/* What `mumcord_camera_push_frame` did. */
enum MumcordCameraPush {
    /* The picture went to the encoder. */
    MUMCORD_CAMERA_SENT = 0,
    /* The encoder is behind or the camera is opening or closing; the picture was dropped. */
    MUMCORD_CAMERA_DROPPED = 1,
    /* The engine has not opened the camera (or `handle` is 0); stop capturing. */
    MUMCORD_CAMERA_NOT_OPEN = 2,
    /* The description is not usable: a missing or too short plane, a wrong rotation or size. */
    MUMCORD_CAMERA_INVALID = 3,
};

/* Hands one picture to the engine; returns a `MumcordCameraPush` value. */
int32_t mumcord_camera_push_frame(uint64_t handle, const MumcordCameraImage *image);

#ifdef __cplusplus
}
#endif

#endif /* MUMCORD_CAMERA_H */
