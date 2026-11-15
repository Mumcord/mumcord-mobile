// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import android.graphics.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import app.mumcord.mobile.bridge.VideoSink
import app.mumcord.mobile.platform.video.VideoFrames
import app.mumcord.mobile.platform.video.VideoRouter
import app.mumcord.mobile.platform.video.VideoView

const val STREAM_PICTURE_TEST_TAG = "stream-picture"

/**
 * Where the pictures of watched streams come from and how news of them reaches the views.
 * The engine's [sink] is called on the bridge's video thread and only schedules a redraw.
 */
class StreamVideo(val frames: VideoFrames, val router: VideoRouter = VideoRouter()) {
    val sink: VideoSink = object : VideoSink {
        override fun onFrameReady(videoId: ULong, width: UInt, height: UInt) = router.frameReady(videoId.toLong())
    }

    companion object {
        /** No pictures at all (tests that do not look at them). */
        val None = StreamVideo(VideoFrames { _, _, _ -> 0L })
    }
}

/** Draws the live picture of one stream; replaced by a drawn stand-in for screenshots on the computer. */
val LocalStreamPicture = staticCompositionLocalOf<@Composable (videoId: Long, modifier: Modifier) -> Unit> {
    { _, _ -> }
}

/** The real picture: a [VideoView] that takes the stream's pictures from [video]. */
fun streamPicture(video: StreamVideo): @Composable (Long, Modifier) -> Unit = { videoId, modifier ->
    AndroidView(
        factory = { VideoView(it) },
        update = { it.show(video.frames, video.router, videoId) },
        onRelease = { it.release() },
        modifier = modifier.testTag(STREAM_PICTURE_TEST_TAG),
    )
}

@Composable
fun StreamPicture(videoId: Long, modifier: Modifier = Modifier) {
    LocalStreamPicture.current(videoId, modifier)
}

/**
 * Told where the opened stream is on screen (in window pixels), so the system's
 * picture-in-picture window can grow out of it (FR-5.76).
 */
val LocalStreamBounds = staticCompositionLocalOf<(Rect) -> Unit> { {} }

/** Reports this element's place on screen as the opened stream's to [report]. */
fun Modifier.reportStreamBounds(report: (Rect) -> Unit): Modifier =
    onGloballyPositioned { report(it.boundsInWindow().toAndroidRect()) }
