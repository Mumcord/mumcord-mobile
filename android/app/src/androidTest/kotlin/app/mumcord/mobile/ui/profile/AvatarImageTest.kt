// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A camera photo becomes the avatar without its EXIF data (among it the place it was taken) and
 * upright, on the device's own image decoder.
 */
class AvatarImageTest {
    private val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    /** A JPEG 400 x 200 pixels, left half red, with a GPS position and "rotate 90°" in its EXIF data. */
    private fun cameraPhoto(): ByteArray {
        val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
        for (x in 0 until PHOTO_WIDTH) {
            for (y in 0 until PHOTO_HEIGHT) bitmap.setPixel(x, y, if (x < PHOTO_WIDTH / 2) Color.RED else Color.BLUE)
        }
        val file = File(cache, "avatar-test.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,31/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "13/1,24/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_MAKE, "Test camera")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    @Test
    fun thePhotosLocationAndCameraDataAreNotSent() {
        val photo = cameraPhoto()
        assertTrue("The test photo has a location", ExifInterface(ByteArrayInputStream(photo)).getLatLong(FloatArray(2)))

        val avatar = checkNotNull(avatarImage(photo))

        val exif = ExifInterface(ByteArrayInputStream(avatar))
        assertFalse(exif.getLatLong(FloatArray(2)))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertFalse("No EXIF block at all", String(avatar, Charsets.ISO_8859_1).contains("Exif"))
    }

    @Test
    fun thePhotoArrivesUpright() {
        val avatar = checkNotNull(avatarImage(cameraPhoto()))
        val decoded = BitmapFactory.decodeByteArray(avatar, 0, avatar.size)
        assertEquals("Turned by 90°: as high as the photo was wide", PHOTO_HEIGHT, decoded.width)
        assertEquals(PHOTO_WIDTH, decoded.height)
        // Turned clockwise, the red left half of the photo is now at the top.
        val top = decoded.getPixel(decoded.width / 2, decoded.height / 4)
        val bottom = decoded.getPixel(decoded.width / 2, decoded.height * 3 / 4)
        assertTrue("Red at the top", Color.red(top) > Color.blue(top))
        assertTrue("Blue at the bottom", Color.blue(bottom) > Color.red(bottom))
    }

    @Test
    fun aLargePhotoIsScaledDown() {
        val big = Bitmap.createBitmap(3000, 1000, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            big.compress(Bitmap.CompressFormat.PNG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        val avatar = checkNotNull(avatarImage(bytes))
        val decoded = BitmapFactory.decodeByteArray(avatar, 0, avatar.size)
        assertEquals(AVATAR_MAX_EDGE, decoded.width)
        assertTrue(decoded.height in 170..171)
    }

    @Test
    fun bytesThatAreNoPictureAreRefused() {
        assertNull(avatarImage("hello".toByteArray()))
    }

    private companion object {
        const val PHOTO_WIDTH = 400
        const val PHOTO_HEIGHT = 200
        const val JPEG_QUALITY = 95
    }
}
