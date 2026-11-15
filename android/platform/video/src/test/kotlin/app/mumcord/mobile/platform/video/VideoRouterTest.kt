// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRouterTest {
    @Test
    fun `news of a picture reaches only the view that shows the stream now`() {
        val router = VideoRouter()
        val heard = mutableListOf<String>()
        val first = Runnable { heard += "first" }
        val second = Runnable { heard += "second" }
        router.listen(7, first)
        router.frameReady(7)
        router.listen(7, second)
        router.frameReady(7)
        // The first view going away later must not silence the second.
        router.stop(7, first)
        router.frameReady(7)
        router.frameReady(8)
        router.stop(7, second)
        router.frameReady(7)
        assertEquals(listOf("first", "second", "second"), heard)
    }

    @Test
    fun `sizes are packed without loss`() {
        val packed = packSize(2560, 1440)
        assertEquals(2560, packedWidth(packed))
        assertEquals(1440, packedHeight(packed))
    }

    @Test
    fun `a wide picture is letterboxed and a tall one pillarboxed, centred`() {
        assertArrayEquals(intArrayOf(0, 300, 1000, 562), fitViewport(1000, 1162, 1920, 1080))
        assertArrayEquals(intArrayOf(400, 0, 200, 400), fitViewport(1000, 400, 1080, 2160))
        assertArrayEquals(intArrayOf(0, 0, 640, 360), fitViewport(640, 360, 1280, 720))
        assertArrayEquals(intArrayOf(0, 0, 10, 10), fitViewport(10, 10, 0, 0))
    }
}
