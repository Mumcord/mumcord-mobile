// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val decoder = QrDecoder()

    /** A QR code of [text] as a camera would see it: grey bytes, rows padded to [rowStride]. */
    private fun picture(text: String, size: Int, rowStride: Int = size): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val luma = ByteArray(rowStride * size) { 0x7f }
        for (y in 0 until size) {
            for (x in 0 until size) {
                luma[y * rowStride + x] = if (matrix.get(x, y)) 0x10 else 0xf0.toByte()
            }
        }
        return luma
    }

    @Test
    fun aQrCodeIsReadFromTheBrightnessOfAFrame() {
        assertEquals("MUMCORD-TEST-1", decoder.decode(picture("MUMCORD-TEST-1", 400), 400, 400))
    }

    @Test
    fun aDenseCodeAsATransferUsesIsRead() {
        // As long as a part of a transfer may get: far more than the 400 bytes of the default.
        val text = (1..900).joinToString("") { ('A' + it % 26).toString() }
        assertEquals(text, decoder.decode(picture(text, 720), 720, 720))
    }

    @Test
    fun rowsLongerThanThePictureAreSkippedOver() {
        assertEquals("PADDED", decoder.decode(picture("PADDED", 300, rowStride = 320), 300, 300, rowStride = 320))
    }

    @Test
    fun theLastRowMayEndWithThePicture() {
        val whole = picture("SHORT LAST ROW", 300, rowStride = 320)
        val frame = whole.copyOf(320 * 299 + 300)
        assertEquals("SHORT LAST ROW", decoder.decode(frame, 300, 300, rowStride = 320))
    }

    @Test
    fun aFrameWithoutACodeGivesNothing() {
        assertNull(decoder.decode(ByteArray(400 * 400) { 0x7f }, 400, 400))
    }

    @Test
    fun aFrameThatIsTooShortForItsSizeGivesNothing() {
        assertNull(decoder.decode(ByteArray(100), 400, 400))
        assertNull(decoder.decode(ByteArray(0), 0, 0))
    }

    @Test
    fun theDecoderReadsOneCodeAfterTheOther() {
        assertEquals("FIRST", decoder.decode(picture("FIRST", 300), 300, 300))
        assertNull(decoder.decode(ByteArray(300 * 300), 300, 300))
        assertEquals("SECOND", decoder.decode(picture("SECOND", 300), 300, 300))
    }
}
