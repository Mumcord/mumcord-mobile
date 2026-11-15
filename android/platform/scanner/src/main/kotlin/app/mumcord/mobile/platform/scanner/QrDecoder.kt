// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.scanner

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds a QR code in a grey picture and reads its text. Not safe for use from several threads
 * at once; the camera hands its frames out one after the other.
 */
class QrDecoder {
    private val reader = QRCodeReader()

    // The codes of a transfer are dense; looking harder costs a little time per frame.
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * [luma] holds one brightness byte per pixel, row after row; a row is [rowStride] bytes long,
     * of which the first [width] are the picture. Null when no QR code is found, which is the
     * normal case for most frames.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        if (width <= 0 || height <= 0 || rowStride < width || luma.size < rowStride * (height - 1) + width) return null
        // The source wants whole rows; the last row of a camera frame may end with the picture.
        val data = if (luma.size >= rowStride * height) luma else luma.copyOf(rowStride * height)
        val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
