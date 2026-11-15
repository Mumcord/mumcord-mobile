// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.video

import java.util.concurrent.ConcurrentHashMap

/**
 * Passes "a stream has a new picture" on to the one view that shows that stream. The engine says
 * so again only after the picture was taken, so a view that starts showing a stream takes the
 * waiting picture itself (see [VideoView]).
 */
class VideoRouter {
    private val views = ConcurrentHashMap<Long, Runnable>()

    /** Called on the bridge's video thread: only schedules a redraw. */
    fun frameReady(videoId: Long) {
        views[videoId]?.run()
    }

    /** [onReady] is now the one to hear about [videoId]; a view shown before it is not told any more. */
    fun listen(videoId: Long, onReady: Runnable) {
        views[videoId] = onReady
    }

    /** Stops telling [onReady], unless another view took [videoId] over meanwhile. */
    fun stop(videoId: Long, onReady: Runnable) {
        views.remove(videoId, onReady)
    }
}

/**
 * The part of a surface of [surfaceWidth] x [surfaceHeight] that shows a picture of
 * [pictureWidth] x [pictureHeight] whole and undistorted, centred: x, y, width, height.
 */
fun fitViewport(surfaceWidth: Int, surfaceHeight: Int, pictureWidth: Int, pictureHeight: Int): IntArray {
    if (pictureWidth <= 0 || pictureHeight <= 0) return intArrayOf(0, 0, surfaceWidth, surfaceHeight)
    val byWidth = surfaceWidth.toLong() * pictureHeight <= surfaceHeight.toLong() * pictureWidth
    val width = if (byWidth) surfaceWidth else (surfaceHeight.toLong() * pictureWidth / pictureHeight).toInt()
    val height = if (byWidth) (surfaceWidth.toLong() * pictureHeight / pictureWidth).toInt() else surfaceHeight
    return intArrayOf((surfaceWidth - width) / 2, (surfaceHeight - height) / 2, width, height)
}
