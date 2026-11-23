// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.chat

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.mumcord.mobile.bridge.ImageData
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.session.ChatRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** A decoded picture, ready to draw. An animated one plays while it is started. */
class LoadedImage(val drawable: Drawable, val width: Int, val height: Int)

/** Gets the pictures of the chat. The screens only know this seam. */
fun interface ChatImageLoader {
    /**
     * The picture, decoded no larger than needed to fill [maxWidthPx] x [maxHeightPx], or null
     * when it cannot or may not be shown.
     */
    suspend fun load(source: ChatImageSource, maxWidthPx: Int, maxHeightPx: Int): LoadedImage?
}

/**
 * Reads pictures through the bridge (never from the network itself) and decodes them with the
 * platform decoder, which also plays animated GIFs and WebPs. The encoded bytes of recent
 * pictures are kept, so scrolling back does not fetch them again.
 *
 * @param log receives the kind of a failure only, never an address or a name.
 */
class BridgeImageLoader(
    private val chats: ChatRepository,
    private val log: (String) -> Unit,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ChatImageLoader {
    private val cache = object : LruCache<ChatImageSource, ImageData>(CACHE_BYTES) {
        override fun sizeOf(key: ChatImageSource, value: ImageData): Int = value.bytes.size
    }

    override suspend fun load(source: ChatImageSource, maxWidthPx: Int, maxHeightPx: Int): LoadedImage? {
        val data = cache.get(source) ?: fetch(source)?.also { cache.put(source, it) } ?: return null
        return withContext(decodeDispatcher) {
            try {
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data.bytes))) { decoder, info, _ ->
                    decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, maxWidthPx, maxHeightPx))
                }
                LoadedImage(drawable, drawable.intrinsicWidth, drawable.intrinsicHeight)
            } catch (e: IOException) {
                log("decoding a chat image failed (${e.javaClass.simpleName})")
                null
            }
        }
    }

    private suspend fun fetch(source: ChatImageSource): ImageData? = when (source) {
        is ChatImageSource.Embedded -> chats.image(source.chat, source.messageId, source.imageId)
        is ChatImageSource.Remote -> chats.remoteImage(source.url)
    }

    private companion object {
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}

/**
 * By how much a picture of [width] x [height] is scaled down while decoding (1, 2, 4, …) so
 * that it still fills [maxWidth] x [maxHeight] but does not take more memory than that needs.
 */
fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
    if (maxWidth <= 0 || maxHeight <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= maxWidth && height / (sample * 2) >= maxHeight) sample *= 2
    return sample
}

/** The size of a [width] x [height] picture fitted into [maxWidth] x [maxHeight], never enlarged past it. */
fun fittedSize(width: Int, height: Int, maxWidth: Dp, maxHeight: Dp): DpSize {
    if (width <= 0 || height <= 0) return DpSize(maxWidth, maxWidth * 9 / 16)
    val scale = minOf(maxWidth.value / width, maxHeight.value / height)
    return DpSize((width * scale).dp, (height * scale).dp)
}

private val INLINE_MAX_WIDTH = 200.dp
private val INLINE_MAX_HEIGHT = 240.dp

private sealed interface ImageLoad {
    data object Loading : ImageLoad

    data object Unavailable : ImageLoad

    class Ready(val image: LoadedImage) : ImageLoad
}

@Composable
private fun loadImage(source: ChatImageSource, loader: ChatImageLoader, maxWidth: Dp, maxHeight: Dp): ImageLoad {
    val density = LocalDensity.current
    val load by produceState<ImageLoad>(ImageLoad.Loading, source, loader) {
        val image = with(density) { loader.load(source, maxWidth.roundToPx(), maxHeight.roundToPx()) }
        value = if (image == null) ImageLoad.Unavailable else ImageLoad.Ready(image)
    }
    return load
}

/**
 * A picture inside a message (`.msg__image`): rounded, at most 200 dp wide, in the picture's
 * own proportions. While it loads the frame shows the image icon; a picture from the web that
 * cannot or may not be loaded takes no room (its link is still in the text). Tapping opens it
 * large; [onLongPress] is set for a GIF that can be kept as a favourite.
 */
@Composable
fun ChatInlineImage(
    source: ChatImageSource,
    loader: ChatImageLoader,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val load = loadImage(source, loader, INLINE_MAX_WIDTH, INLINE_MAX_HEIGHT)
    if (load == ImageLoad.Unavailable && source is ChatImageSource.Remote) return
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    val size = when {
        load is ImageLoad.Ready -> fittedSize(load.image.width, load.image.height, INLINE_MAX_WIDTH, INLINE_MAX_HEIGHT)
        source is ChatImageSource.Embedded -> fittedSize(source.width, source.height, INLINE_MAX_WIDTH, INLINE_MAX_HEIGHT)
        else -> fittedSize(0, 0, INLINE_MAX_WIDTH, INLINE_MAX_HEIGHT)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.bgElevated)
            .border(1.dp, colors.border, shape)
            .pointerInput(load is ImageLoad.Ready, onLongPress) {
                if (load is ImageLoad.Ready) {
                    detectTapGestures(onTap = { onOpen() }, onLongPress = onLongPress?.let { action -> { action() } })
                }
            },
    ) {
        when (load) {
            is ImageLoad.Ready -> DrawableImage(load.image.drawable, Modifier.fillMaxSize())
            ImageLoad.Loading ->
                MumcordIcon(MumcordIcons.Image, contentDescription = null, tint = colors.textMuted, size = MumcordSize.iconXl)
            ImageLoad.Unavailable ->
                MumcordIcon(MumcordIcons.Alert, contentDescription = null, tint = colors.textMuted, size = MumcordSize.iconLg)
        }
    }
}

/** A picture that fills its frame, cropped at the centre: a tile of the GIF favourites. */
@Composable
fun ChatTileImage(source: ChatImageSource, loader: ChatImageLoader, modifier: Modifier = Modifier) {
    val load = loadImage(source, loader, TILE_DECODE, TILE_DECODE)
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        when (load) {
            is ImageLoad.Ready -> DrawableImage(load.image.drawable, Modifier.fillMaxSize(), crop = true)
            else -> MumcordIcon(MumcordIcons.Gif, contentDescription = null, tint = MumcordTheme.colors.textMuted, size = MumcordSize.iconLg)
        }
    }
}

private val TILE_DECODE = 180.dp

/** A picture as large as the screen allows, for the viewer. Null while it is not there. */
@Composable
fun ChatFullImage(source: ChatImageSource, loader: ChatImageLoader, maxWidth: Dp, maxHeight: Dp, modifier: Modifier = Modifier) {
    val load = loadImage(source, loader, maxWidth, maxHeight)
    if (load is ImageLoad.Ready) {
        DrawableImage(load.image.drawable, modifier.size(fittedSize(load.image.width, load.image.height, maxWidth, maxHeight)))
    } else {
        MumcordIcon(MumcordIcons.Image, contentDescription = null, tint = MumcordTheme.colors.mediaText, size = MumcordSize.iconXl)
    }
}

/**
 * Draws a platform drawable. An animated one runs only while this is on screen and the app is
 * in front: leaving the list (scrolled away) or the screen stops it.
 */
@Composable
private fun DrawableImage(drawable: Drawable, modifier: Modifier = Modifier, crop: Boolean = false) {
    var frame by remember(drawable) { mutableIntStateOf(0) }
    if (drawable is AnimatedImageDrawable) {
        DisposableEffect(drawable) {
            val handler = Handler(Looper.getMainLooper())
            drawable.callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {
                    frame++
                }

                override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
                    handler.postAtTime(what, who, time)
                }

                override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                    handler.removeCallbacks(what, who)
                }
            }
            onDispose {
                drawable.stop()
                drawable.callback = null
                handler.removeCallbacksAndMessages(drawable)
            }
        }
        LifecycleStartEffect(drawable) {
            drawable.start()
            onStopOrDispose { drawable.stop() }
        }
    }
    Box(
        modifier.drawBehind {
            // Reading the frame counter redraws this node for every frame of an animation.
            frame
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: size.width.roundToInt()
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: size.height.roundToInt()
            val scale = if (crop) maxOf(size.width / width, size.height / height) else minOf(size.width / width, size.height / height)
            clipRect {
                translate((size.width - width * scale) / 2, (size.height - height * scale) / 2) {
                    scale(scale, scale, pivot = Offset.Zero) {
                        drawIntoCanvas { canvas ->
                            drawable.setBounds(0, 0, width, height)
                            drawable.draw(canvas.nativeCanvas)
                        }
                    }
                }
            }
        },
    )
}
