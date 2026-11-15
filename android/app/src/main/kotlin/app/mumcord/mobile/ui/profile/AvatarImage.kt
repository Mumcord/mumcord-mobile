// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import app.mumcord.mobile.ui.chat.ChatImageSystem
import app.mumcord.mobile.ui.chat.rememberChatImageSystem
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The longest edge of the picture handed to the engine, which scales it to 128 pixels itself. */
const val AVATAR_MAX_EDGE = 512

private const val TAG = "MumcordAvatar"
private const val PNG_QUALITY = 100

/**
 * Turns a chosen photo into the avatar to send: upright, at most [AVATAR_MAX_EDGE] pixels, as a
 * PNG that holds nothing but the pixels. Null when the bytes are no picture.
 *
 * Why on the phone: a camera photo carries EXIF data, among it the place it was taken. The
 * engine re-encodes avatars too, which drops that data, but it ignores the orientation flag, so a
 * photo taken upright would arrive lying on its side. The platform decoder applies that flag.
 * Runs off the main thread.
 */
fun avatarImage(bytes: ByteArray): ByteArray? {
    val decoded = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // A coarse step down while decoding, so a 50-megapixel photo never fills the memory.
            var sample = 1
            while (max(info.size.width, info.size.height) / (sample * 2) >= AVATAR_MAX_EDGE) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (e: IOException) {
        Log.w(TAG, "the chosen picture could not be read: ${e.javaClass.simpleName}")
        return null
    }
    val edge = max(decoded.width, decoded.height)
    val bitmap = if (edge > AVATAR_MAX_EDGE) {
        val scale = AVATAR_MAX_EDGE.toFloat() / edge
        val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        decoded.scale(width, height).also { decoded.recycle() }
    } else {
        decoded
    }
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        out.toByteArray()
    }
}

/**
 * An avatar's bytes as a picture to draw, or null for none or bytes that are no picture.
 * Decoded while drawing: avatars are small (the server's at most a few hundred pixels, a new one
 * at most [AVATAR_MAX_EDGE]), and the result is kept for as long as the bytes stay the same.
 */
@Composable
fun rememberAvatarBitmap(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
}

/**
 * The picture sources of the profile picture: the chat's photo picker and camera, with every
 * picture made upright and free of its metadata ([avatarImage]) before the editor gets it.
 */
@Composable
fun rememberAvatarImageSystem(
    profile: ProfileController,
    cameraGranted: () -> Boolean,
    onCameraNeeded: () -> Unit,
): ChatImageSystem {
    val scope = rememberCoroutineScope()
    val use: (ByteArray) -> Unit = { picked ->
        scope.launch {
            val image = withContext(Dispatchers.Default) { avatarImage(picked) }
            if (image == null) profile.avatarUnreadable() else profile.pickAvatar(image)
        }
    }
    val sources = rememberChatImageSystem(
        onImage = { use(it.bytes) },
        onUnreadable = profile::avatarUnreadable,
        cameraGranted = cameraGranted,
        onCameraNeeded = onCameraNeeded,
    )
    return remember(sources) {
        ChatImageSystem(
            pickPhoto = {
                profile.closeSheet()
                sources.pickPhoto()
            },
            takePhoto = {
                profile.closeSheet()
                sources.takePhoto()
            },
        )
    }
}
