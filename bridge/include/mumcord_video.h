/* SPDX-License-Identifier: AGPL-3.0-only */

/*
 * Mumcord mobile bridge: the pictures of watched streams.
 *
 * The engine decodes every watched screen share and camera (VP9 with libvpx, AV1 with dav1d)
 * and keeps the newest picture of each stream. `VideoSink.onFrameReady(videoId, width, height)`
 * (generated bindings, on the bridge's video thread) says that a stream has a new picture. The
 * app's render thread then takes it with `mumcord_video_frame_acquire`, uploads the three
 * planes into its textures straight from the bridge's memory and gives the picture back with
 * `mumcord_video_frame_release`. Picture bytes never pass through the generated bindings.
 *
 * HANDLE
 *   `MumcordEngine.videoHandle()` (generated bindings). 0 is accepted and means "no engine".
 *   A handle is valid from the creation of its engine object until the process ends.
 *
 * FORMAT
 *   I420 (YUV 4:2:0, 8 bit, BT.601 limited range): full-size Y plane, then U and V at half
 *   width and half height (rounded up). At most 2560 x 1440. The size can change from one
 *   picture to the next.
 *
 * BACKPRESSURE
 *   Pictures are never queued: a picture the app has not taken yet is replaced by the next one.
 *   `onFrameReady` comes again only after the waiting picture was taken. Each stream has three
 *   buffers; while the app holds all of them new pictures of that stream are dropped, so give
 *   every picture back as soon as it is uploaded.
 *
 * THREADING
 *   Both functions may be called from any thread. They never call back into the app and never
 *   wait for the decoder: the decoder copies outside the locks, which guard pointer swaps only.
 */

#ifndef MUMCORD_VIDEO_H
#define MUMCORD_VIDEO_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* One acquired picture. Row `r` of the Y plane starts at `y + r * stride_y`; U and V likewise. */
typedef struct MumcordVideoFrame {
    uint64_t token;
    uint32_t width;
    uint32_t height;
    const uint8_t *y;
    const uint8_t *u;
    const uint8_t *v;
    uint32_t stride_y;
    uint32_t stride_u;
    uint32_t stride_v;
} MumcordVideoFrame;

/* Takes the newest picture of stream `video_id` (`Stream.videoId`) and fills `*frame`. Returns
 * false and leaves `*frame` untouched when there is no new picture since the last call, the
 * stream is not watched, `handle` is 0 or `frame` is NULL. On true the planes stay valid and
 * unchanged until `mumcord_video_frame_release(handle, frame->token)`, also if the stream stops,
 * its size changes or the engine object is destroyed meanwhile. Read-only memory. */
bool mumcord_video_frame_acquire(uint64_t handle, uint64_t video_id, MumcordVideoFrame *frame);

/* Gives back the picture of `token`; its planes must not be read afterwards. A token that is 0,
 * unknown or already released does nothing, so a double release is harmless. */
void mumcord_video_frame_release(uint64_t handle, uint64_t token);

#ifdef __cplusplus
}
#endif

#endif /* MUMCORD_VIDEO_H */
