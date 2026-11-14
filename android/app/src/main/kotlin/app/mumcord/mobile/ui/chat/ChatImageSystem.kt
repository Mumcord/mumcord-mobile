// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Where pictures to send come from: the system's photo picker and the system's camera app.
 * Both hand the app one picture and need no permission.
 */
class ChatImageSystem(val pickPhoto: () -> Unit, val takePhoto: () -> Unit)

/** A picture read from the phone, in an encoding the chat can send. */
class PickedImage(val bytes: ByteArray, val mime: String)

/**
 * The real picture sources. [onImage] gets the picture the user chose; [onUnreadable] is called
 * when it could not be read or no camera app exists.
 *
 * The camera is used through the capture intent of the system's camera app, which writes the
 * photo into a file of this app. That needs no CAMERA permission, as long as the app does not
 * declare it; so the manifest does not.
 */
@Composable
fun rememberChatImageSystem(onImage: (PickedImage) -> Unit, onUnreadable: () -> Unit): ChatImageSystem {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val currentOnImage by rememberUpdatedState(onImage)
    val currentOnUnreadable by rememberUpdatedState(onUnreadable)
    val read: (Uri) -> Unit = { uri ->
        scope.launch {
            val image = withContext(Dispatchers.IO) { readImage(context, uri) }
            if (image == null) currentOnUnreadable() else currentOnImage(image)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) read(uri)
    }
    // Survives the Activity being recreated while the camera app is in front.
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = captureUri
        captureUri = null
        if (saved && uri != null) read(uri)
    }
    return remember(picker, camera) {
        ChatImageSystem(
            pickPhoto = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            takePhoto = {
                try {
                    val uri = captureTarget(context)
                    captureUri = uri
                    camera.launch(uri)
                } catch (_: ActivityNotFoundException) {
                    // A phone without a camera app.
                    captureUri = null
                    currentOnUnreadable()
                } catch (_: IOException) {
                    captureUri = null
                    currentOnUnreadable()
                }
            },
        )
    }
}

private const val CAPTURE_DIRECTORY = "camera"
private const val CAPTURE_FILE = "capture.jpg"

/** The largest picture the engine takes; it scales a large one down to the server's limit itself. */
const val MAX_IMAGE_BYTES = 25 * 1024 * 1024

private const val JPEG_QUALITY = 90
private const val TRANSCODE_MAX_EDGE = 4096
private val SENDABLE = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** The one file the camera app writes into; it is only readable through a one-time grant. */
private fun captureTarget(context: Context): Uri {
    val directory = File(context.cacheDir, CAPTURE_DIRECTORY)
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("no camera directory")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", File(directory, CAPTURE_FILE))
}

/**
 * Reads a chosen picture. Encodings the chat cannot send (such as HEIC from the camera roll)
 * are turned into JPEG. Null when it cannot be read or is larger than the engine takes.
 */
private fun readImage(context: Context, uri: Uri): PickedImage? =
    try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        if (mime in SENDABLE) {
            val bytes = resolver.openInputStream(uri)?.use { it.readAtMost(MAX_IMAGE_BYTES + 1) }
            bytes?.takeIf { it.size <= MAX_IMAGE_BYTES }?.let { PickedImage(it, checkNotNull(mime)) }
        } else {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, TRANSCODE_MAX_EDGE, TRANSCODE_MAX_EDGE))
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            PickedImage(out.toByteArray(), "image/jpeg")
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } finally {
        // The photo of the camera app has been read; nothing of it stays on the phone.
        if (uri.authority == "${context.packageName}.files") {
            File(File(context.cacheDir, CAPTURE_DIRECTORY), CAPTURE_FILE).delete()
        }
    }

/** Reads the stream to its end, but never more than [limit] bytes. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
