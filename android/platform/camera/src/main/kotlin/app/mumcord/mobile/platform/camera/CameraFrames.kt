// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.camera

import java.nio.ByteBuffer

/**
 * Where the camera's pictures go: YUV 4:2:0 planes as the camera delivers them (direct
 * buffers), with the clockwise [rotation] in degrees that makes the picture upright. Called on
 * the camera's frame thread; never waits. Returns one of the `PUSH_` values.
 */
fun interface CameraFrames {
    fun push(
        width: Int,
        height: Int,
        rotation: Int,
        y: ByteBuffer,
        yStride: Int,
        u: ByteBuffer,
        v: ByteBuffer,
        uvStride: Int,
        uvPixelStride: Int,
    ): Int

    companion object {
        /** The picture went to the encoder. */
        const val PUSH_SENT = 0

        /** The encoder is behind; the picture was dropped. */
        const val PUSH_DROPPED = 1

        /** The engine has not opened the camera. */
        const val PUSH_NOT_OPEN = 2

        /** The picture could not be read. */
        const val PUSH_INVALID = 3
    }
}

/**
 * The engine's camera input, through the C function of `bridge/include/mumcord_camera.h`.
 *
 * @param handle the engine's camera handle (`MumcordEngine.cameraHandle()`); 0 while there is
 *   no engine, which takes no pictures.
 */
class EngineCameraFrames(private val handle: () -> Long) : CameraFrames {
    private val bound: Boolean by lazy {
        System.loadLibrary("mumcord_camera_push")
        nativeBind()
    }

    override fun push(
        width: Int,
        height: Int,
        rotation: Int,
        y: ByteBuffer,
        yStride: Int,
        u: ByteBuffer,
        v: ByteBuffer,
        uvStride: Int,
        uvPixelStride: Int,
    ): Int {
        if (!bound) return CameraFrames.PUSH_NOT_OPEN
        return nativePush(handle(), width, height, rotation, y, yStride, u, v, uvStride, uvPixelStride)
    }

    private external fun nativeBind(): Boolean

    private external fun nativePush(
        handle: Long,
        width: Int,
        height: Int,
        rotation: Int,
        y: ByteBuffer,
        yStride: Int,
        u: ByteBuffer,
        v: ByteBuffer,
        uvStride: Int,
        uvPixelStride: Int,
    ): Int
}
