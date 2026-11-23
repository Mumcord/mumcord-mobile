// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.video

/**
 * Where the pictures of the watched streams come from: I420 pictures (BT.601, limited range),
 * uploaded into three single-channel textures. Called on a render thread whose OpenGL ES
 * context is current, never on an audio thread.
 */
fun interface VideoFrames {
    /**
     * Uploads the newest picture of [videoId] into [textures] (Y, U and V). [sizes] holds the
     * width and height of each texture as last uploaded (6 values) and is updated. Returns the
     * picture's size packed with [packSize], or 0 when there is no new picture.
     */
    fun upload(videoId: Long, textures: IntArray, sizes: IntArray): Long
}

/** Width and height in one value, so that a picture costs no allocation. */
fun packSize(width: Int, height: Int): Long = (width.toLong() shl 32) or height.toLong()

fun packedWidth(size: Long): Int = (size ushr 32).toInt()

fun packedHeight(size: Long): Int = (size and 0xffff_ffffL).toInt()

/**
 * The engine's pictures, through the C functions of `bridge/include/mumcord_video.h`.
 *
 * @param handle the engine's video handle (`MumcordEngine.videoHandle()`); 0 while there is no
 *   engine, which gives no pictures.
 */
class EngineVideoFrames(private val handle: () -> Long) : VideoFrames {
    private val bound: Boolean by lazy {
        System.loadLibrary("mumcord_video_gl")
        nativeBind()
    }

    override fun upload(videoId: Long, textures: IntArray, sizes: IntArray): Long {
        if (!bound) return 0
        return nativeUpload(handle(), videoId, textures, sizes)
    }

    private external fun nativeBind(): Boolean

    private external fun nativeUpload(handle: Long, videoId: Long, textures: IntArray, sizes: IntArray): Long
}
