// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.core.net.toUri
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordGifTile
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordIconButtonStyle
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay

const val GIF_SHEET_TEST_TAG = "gif-sheet"
const val GIF_TILE_TEST_TAG = "gif-tile"
const val IMAGE_SOURCE_LIBRARY_TEST_TAG = "image-source-library"
const val IMAGE_SOURCE_CAMERA_TEST_TAG = "image-source-camera"
const val LINK_WARNING_TEST_TAG = "link-warning"
const val LINK_WARNING_OPEN_TEST_TAG = "link-warning-open"
const val IMAGE_VIEWER_TEST_TAG = "image-viewer"

private const val MAX_ZOOM = 5f

/** The sheet over the chat and the full-screen picture, whichever is open. */
@Composable
fun ChatOverlays(
    state: ChatUiState,
    actions: ChatController,
    loader: ChatImageLoader,
    images: ChatImageSystem,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    val context = LocalContext.current
    state.urlToOpen?.let { url ->
        LaunchedEffect(url) {
            openInBrowser(context, url)
            actions.urlOpened()
        }
    }
    when (val sheet = state.sheet) {
        is ChatSheet.Gifs -> GifFavouritesSheet(sheet, actions, loader, topInsets, bottomInsets)
        ChatSheet.ImageSource -> ImageSourceSheet(
            onLibrary = {
                actions.dismissSheet()
                images.pickPhoto()
            },
            onCamera = {
                actions.dismissSheet()
                images.takePhoto()
            },
            onDismiss = actions::dismissSheet,
            topInsets = topInsets,
            bottomInsets = bottomInsets,
        )
        is ChatSheet.LinkWarning -> LinkWarningSheet(sheet, actions, topInsets, bottomInsets)
        null -> Unit
    }
    state.viewer?.let { ImageViewer(it, loader, actions::closeViewer, topInsets, bottomInsets) }
}

/**
 * Hands a web address to the system, which opens the user's browser. The address was checked
 * by the engine (http or https with a host); nothing is loaded inside the app.
 */
private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A phone without a browser: there is nothing to open the link with.
    }
}

/**
 * GIF favourites (mockup `08-gif-favourites`): a grid of the saved GIFs. Tapping one sends it,
 * the star takes it out of the favourites. There is no search.
 */
@Composable
private fun GifFavouritesSheet(
    sheet: ChatSheet.Gifs,
    actions: ChatController,
    loader: ChatImageLoader,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.gif_title), actions::dismissSheet, topInsets, bottomInsets) {
        sheet.favourites.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2), modifier = Modifier.testTag(GIF_SHEET_TEST_TAG)) {
                row.forEachIndexed { column, favourite ->
                    MumcordGifTile(
                        contentDescription = stringResource(R.string.gif_item, rowIndex * 2 + column + 1),
                        tag = stringResource(R.string.chat_gif_word),
                        removeContentDescription = stringResource(R.string.gif_remove),
                        onClick = { actions.sendGif(favourite.url) },
                        onRemove = { actions.removeGif(favourite.url) },
                        modifier = Modifier.weight(1f).testTag(GIF_TILE_TEST_TAG),
                    ) {
                        ChatTileImage(ChatImageSource.Remote(favourite.url, gif = true), loader)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        BasicText(
            text = stringResource(R.string.gif_hint),
            style = MumcordTheme.typography.caption.copy(color = MumcordTheme.colors.textMuted, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(top = MumcordSpace.s1),
        )
    }
}

/** Where a picture to send comes from: the photo library or the camera. */
@Composable
private fun ImageSourceSheet(
    onLibrary: () -> Unit,
    onCamera: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.image_source_title), onDismiss, topInsets, bottomInsets) {
        MumcordList(horizontalMargin = MumcordSpace.s1) {
            MumcordListRow(
                title = stringResource(R.string.image_source_library),
                icon = MumcordIcons.Image,
                onClick = onLibrary,
                modifier = Modifier.testTag(IMAGE_SOURCE_LIBRARY_TEST_TAG),
            )
            MumcordListRow(
                title = stringResource(R.string.image_source_camera),
                icon = MumcordIcons.Camera,
                divider = true,
                onClick = onCamera,
                modifier = Modifier.testTag(IMAGE_SOURCE_CAMERA_TEST_TAG),
            )
        }
    }
}

/**
 * Before a link is opened whose text names another address than the one it goes to: the real
 * address, in full, and the choice to open it or not.
 */
@Composable
private fun LinkWarningSheet(
    sheet: ChatSheet.LinkWarning,
    actions: ChatController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.link_warning_title), actions::dismissSheet, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.link_warning_text))
        MumcordBanner(
            title = sheet.link.host,
            // The address as the engine checked it, shown as plain characters.
            text = sheet.link.url,
            tone = MumcordBannerTone.Warning,
            icon = MumcordIcons.Alert,
            horizontalMargin = MumcordSpace.s1,
            modifier = Modifier.testTag(LINK_WARNING_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = actions::dismissSheet,
            modifier = Modifier.fillMaxWidth(),
        )
        MumcordButton(
            text = stringResource(R.string.link_warning_open),
            onClick = actions::confirmLink,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(LINK_WARNING_OPEN_TEST_TAG),
        )
    }
}

/** A picture of the chat on the whole screen. Pinch to zoom, double tap to reset. */
@Composable
private fun ImageViewer(
    source: ChatImageSource,
    loader: ChatImageLoader,
    onClose: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    BackHandler(onBack = onClose)
    val title = stringResource(R.string.viewer_title)
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var pan by remember(source) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MumcordTheme.colors.mediaBg)
            .testTag(IMAGE_VIEWER_TEST_TAG)
            .semantics { paneTitle = title }
            .pointerInput(source) {
                detectTapGestures(
                    onDoubleTap = {
                        zoom = 1f
                        pan = Offset.Zero
                    },
                )
            }
            .pointerInput(source) {
                detectTransformGestures { _, move, change, _ ->
                    zoom = (zoom * change).coerceIn(1f, MAX_ZOOM)
                    pan = if (zoom == 1f) Offset.Zero else pan + move
                }
            },
    ) {
        ChatFullImage(
            source = source,
            loader = loader,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                }
                .semantics { contentDescription = title },
        )
        Box(
            contentAlignment = Alignment.TopEnd,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(topInsets).windowInsetsPadding(bottomInsets).padding(MumcordSpace.s2),
        ) {
            MumcordIconButton(MumcordIcons.X, stringResource(R.string.viewer_close), onClose, style = MumcordIconButtonStyle.Tonal)
        }
    }
}
