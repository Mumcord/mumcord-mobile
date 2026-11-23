// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.streams

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.StreamKind
import app.mumcord.mobile.bridge.StreamWatch
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordLiveBadge
import app.mumcord.mobile.design.components.MumcordSlider
import app.mumcord.mobile.design.components.MumcordStageButton
import app.mumcord.mobile.design.components.MumcordStageHint
import app.mumcord.mobile.design.components.MumcordStreamTile
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.tokenShadow
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.SheetOverlay
import app.mumcord.mobile.ui.connected.MuteControl

const val STREAM_TILE_TEST_TAG = "stream-tile"
const val STREAM_FULL_SCREEN_TEST_TAG = "stream-full-screen"
const val STREAM_MINI_WINDOW_TEST_TAG = "stream-mini-window"
const val STREAM_COLLAPSE_TEST_TAG = "stream-collapse"
const val STREAM_EXPAND_TEST_TAG = "stream-expand"
const val STREAM_STOP_TEST_TAG = "stream-stop"
const val STREAM_ROTATE_TEST_TAG = "stream-rotate"
const val STREAM_LEAVE_FULL_SCREEN_TEST_TAG = "stream-leave-full-screen"
const val STREAM_RETRY_TEST_TAG = "stream-retry"
const val STREAM_VOLUME_TEST_TAG = "stream-volume"
const val DIRECT_CONNECTIONS_YES_TEST_TAG = "direct-connections-yes"
const val DIRECT_CONNECTIONS_NO_TEST_TAG = "direct-connections-no"

/** Width of the tile in the channel tree (`.stream--compact`). */
private val TILE_WIDTH = 232.dp

/** Width of the mini window (`.pip`). */
private val MINI_WIDTH = 204.dp

// Names come from the server and its users: they are only ever drawn as plain characters.
@Composable
fun StreamUi.title(): String = stringResource(
    if (key.kind == StreamKind.SCREEN) R.string.stream_title_screen else R.string.stream_title_camera,
    name,
)

/** The line a stream shows where it has no picture, or null where it plays. */
@Composable
private fun StreamUi.status(): String? = when (val look = tile) {
    TileLook.TapToWatch -> stringResource(R.string.stream_tap_to_watch)
    TileLook.Connecting -> stringResource(R.string.stream_connecting)
    TileLook.Paused -> stringResource(R.string.stream_paused)
    TileLook.OpenElsewhere -> stringResource(R.string.stream_open_elsewhere)
    is TileLook.Unreachable -> stringResource(look.reason.text(), name)
    is TileLook.Playing -> null
}

/** The status of an opened stream: its tile's, except that it plays here rather than elsewhere. */
@Composable
internal fun StreamUi.openStatus(): String? = when {
    tile == TileLook.OpenElsewhere -> null
    tile is TileLook.Playing -> null
    else -> status()
}

/**
 * The tile under the sharer in the channel tree (FR-5.70). While it is on screen it may start the
 * stream by itself (see [wantedWatches]); a tap opens the stream full screen.
 */
@Composable
fun StreamTileLine(
    stream: StreamUi,
    onOpen: (StreamKey) -> Unit,
    onShown: (StreamKey, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(stream.key) {
        onShown(stream.key, true)
        onDispose { onShown(stream.key, false) }
    }
    val title = stream.title()
    val status = stream.status()
    val viewers = stream.viewers?.let { pluralStringResource(R.plurals.stream_viewers, it, it) }
    val description = listOfNotNull(viewers, status).fold(stringResource(R.string.stream_tile_description, title)) { text, part ->
        stringResource(R.string.stream_description_part, text, part)
    }
    val look = stream.tile
    MumcordStreamTile(
        label = title,
        liveLabel = stringResource(R.string.stream_live),
        viewerCount = stream.viewers,
        contentDescription = description,
        onClick = { onOpen(stream.key) },
        icon = stream.icon(),
        status = status,
        picture = if (look is TileLook.Playing) {
            { StreamPicture(look.videoId, Modifier.fillMaxSize()) }
        } else {
            null
        },
        modifier = modifier
            .padding(start = 50.dp, bottom = 6.dp)
            .width(TILE_WIDTH)
            .testTag(STREAM_TILE_TEST_TAG),
    )
}

private fun StreamUi.icon() = if (key.kind == StreamKind.SCREEN) MumcordIcons.Monitor else MumcordIcons.Camera

/**
 * The opened stream full screen (FR-5.71), dark in both themes like the mockup: title, LIVE and
 * viewers at the top, the picture in the middle, the sound volume and the buttons at the bottom.
 * Back shrinks it to the mini window.
 *
 * @param mute the user's own microphone for its button; null hides the button.
 * @param onLeave set where the whole screen was opened from the right column (two columns):
 *   back and the collapse arrow return there, and the rotate button becomes "Leave full screen",
 *   because a tablet turns by itself.
 */
@Composable
fun StreamFullScreen(
    stream: StreamUi,
    landscape: Boolean,
    mute: MuteControl?,
    onToggleMute: () -> Unit,
    onMicrophone: () -> Unit,
    actions: StreamsController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    onLeave: (() -> Unit)? = null,
) {
    val back = onLeave ?: actions::collapse
    BackHandler(onBack = back)
    val colors = MumcordTheme.colors
    val portrait = onLeave == null && LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.stageBg)
            .reportStreamBounds(LocalStreamBounds.current)
            .testTag(STREAM_FULL_SCREEN_TEST_TAG),
    ) {
        val playing = stream.watch is StreamWatch.Watching && !stream.paused
        if (playing) StreamPicture(stream.videoId, Modifier.fillMaxSize())
        stream.openStatus()?.let { status ->
            StageStatus(status, retry = (stream.tile as? TileLook.Unreachable)?.let { { actions.retry(stream.key) } })
        }
        if (portrait) {
            MumcordStageHint(
                icon = MumcordIcons.Maximize,
                text = stringResource(R.string.stream_rotate_hint),
                modifier = Modifier
                    .align(Alignment.Center)
                    // Below a 16:9 picture across the full width.
                    .offset(y = maxWidth * 9f / 32f + 48.dp),
            )
        }
        StageTop(stream, back, Modifier.align(Alignment.TopCenter).windowInsetsPadding(topInsets))
        StageBottom(
            stream = stream,
            landscape = landscape,
            mute = mute,
            onToggleMute = onToggleMute,
            onMicrophone = onMicrophone,
            actions = actions,
            onLeave = onLeave,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottomInsets),
        )
    }
}

@Composable
private fun StageTop(stream: StreamUi, onCollapse: () -> Unit, modifier: Modifier) {
    val colors = MumcordTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(colors.stageBg, Color.Transparent)))
            .padding(start = MumcordSpace.s3, top = MumcordSpace.s2, end = MumcordSpace.s3, bottom = 28.dp),
    ) {
        MumcordStageButton(
            icon = MumcordIcons.ChevronDown,
            contentDescription = stringResource(R.string.stream_collapse),
            onClick = onCollapse,
            modifier = Modifier.testTag(STREAM_COLLAPSE_TEST_TAG),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            BasicText(
                text = stream.title(),
                style = MumcordTheme.typography.title.copy(color = colors.mediaText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
                MumcordLiveBadge(stringResource(R.string.stream_live))
                stream.viewers?.let { viewers ->
                    MumcordIcon(MumcordIcons.User, contentDescription = null, tint = colors.stageTextSecondary, size = MumcordSize.iconSm)
                    BasicText(
                        text = pluralStringResource(R.plurals.stream_watching, viewers, viewers),
                        style = MumcordTheme.typography.caption.copy(color = colors.stageTextSecondary),
                    )
                }
            }
        }
    }
}

@Composable
private fun StageBottom(
    stream: StreamUi,
    landscape: Boolean,
    mute: MuteControl?,
    onToggleMute: () -> Unit,
    onMicrophone: () -> Unit,
    actions: StreamsController,
    onLeave: (() -> Unit)?,
    modifier: Modifier,
) {
    val colors = MumcordTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.stageBg)))
            .padding(start = MumcordSpace.s3, top = 28.dp, end = MumcordSpace.s3, bottom = MumcordSpace.s3),
    ) {
        // Only a share that sends sound has a volume (FR-5.72).
        if (stream.hasSound) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
                MumcordIcon(MumcordIcons.Volume, contentDescription = null, tint = colors.mediaText)
                MumcordSlider(
                    value = stream.volume / MAX_STREAM_VOLUME.toFloat(),
                    onValueChange = { actions.changeVolume(stream.key, Math.round(it * MAX_STREAM_VOLUME)) },
                    contentDescription = stringResource(R.string.stream_volume),
                    onMedia = true,
                    modifier = Modifier.weight(1f).testTag(STREAM_VOLUME_TEST_TAG),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (mute) {
                MuteControl.Unmuted, MuteControl.Muted -> MumcordStageButton(
                    icon = if (mute == MuteControl.Muted) MumcordIcons.MicOff else MumcordIcons.Mic,
                    contentDescription = stringResource(
                        if (mute == MuteControl.Muted) R.string.voice_unmute_microphone else R.string.voice_mute_microphone,
                    ),
                    onClick = onToggleMute,
                )
                MuteControl.NoMicrophone -> MumcordStageButton(
                    icon = MumcordIcons.MicOff,
                    contentDescription = stringResource(R.string.voice_unmute_microphone),
                    onClick = onMicrophone,
                )
                // The user cannot change the server's mute, so there is no button for it here.
                MuteControl.ServerMuted, null -> Unit
            }
            if (onLeave != null) {
                MumcordStageButton(
                    icon = MumcordIcons.Maximize,
                    contentDescription = stringResource(R.string.stream_leave_full_screen),
                    onClick = onLeave,
                    modifier = Modifier.testTag(STREAM_LEAVE_FULL_SCREEN_TEST_TAG),
                )
            } else {
                MumcordStageButton(
                    icon = MumcordIcons.Maximize,
                    contentDescription = stringResource(
                        if (landscape) R.string.stream_rotate_portrait else R.string.stream_rotate_landscape,
                    ),
                    onClick = actions::toggleLandscape,
                    modifier = Modifier.testTag(STREAM_ROTATE_TEST_TAG),
                )
            }
            MumcordStageButton(
                icon = MumcordIcons.Monitor,
                contentDescription = stringResource(R.string.stream_collapse),
                onClick = actions::collapse,
            )
            MumcordStageButton(
                icon = MumcordIcons.X,
                contentDescription = stringResource(R.string.stream_stop),
                onClick = actions::close,
                danger = true,
                modifier = Modifier.testTag(STREAM_STOP_TEST_TAG),
            )
        }
    }
}

/** What an opened stream shows where it has no picture, with "Try again" where it could not be reached. */
@Composable
internal fun StageStatus(text: String, retry: (() -> Unit)?, small: Boolean = false) {
    val colors = MumcordTheme.colors
    Box(Modifier.fillMaxSize().padding(MumcordSpace.s4), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        ) {
            BasicText(
                text = text,
                style = (if (small) MumcordTheme.typography.captionStrong else MumcordTheme.typography.body)
                    .copy(color = colors.mediaText, textAlign = TextAlign.Center),
                maxLines = if (small) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (retry != null && !small) {
                // The stage is dark in both themes, so the button takes the dark look too.
                MumcordTheme(darkTheme = true) {
                    MumcordButton(
                        text = stringResource(R.string.stream_try_again),
                        onClick = retry,
                        style = MumcordButtonStyle.Secondary,
                        small = true,
                        modifier = Modifier.testTag(STREAM_RETRY_TEST_TAG),
                    )
                }
            }
        }
    }
}

/**
 * The floating mini window (FR-5.71, `.pip`): the stream keeps playing above the chat and the
 * channels. A tap or the expand button opens it full screen; the cross stops watching.
 */
@Composable
fun StreamMiniWindow(
    stream: StreamUi,
    actions: StreamsController,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = actions::expand,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    val title = stream.title()
    Box(
        modifier = modifier
            .width(MINI_WIDTH)
            .aspectRatio(16f / 9f)
            .tokenShadow(MumcordTheme.shadows.raised, shape)
            .clip(shape)
            .background(colors.stageBg)
            .border(1.dp, colors.borderStrong, shape)
            .reportStreamBounds(LocalStreamBounds.current)
            .clickable(onClickLabel = stringResource(R.string.stream_expand), onClick = onExpand)
            .semantics { contentDescription = title }
            .testTag(STREAM_MINI_WINDOW_TEST_TAG),
    ) {
        if (stream.watch is StreamWatch.Watching && !stream.paused) {
            StreamPicture(stream.videoId, Modifier.fillMaxSize())
        }
        stream.openStatus()?.let { StageStatus(it, retry = null, small = true) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(colors.mediaScrim, Color.Transparent)))
                .padding(start = 6.dp),
        ) {
            MumcordLiveBadge(stringResource(R.string.stream_live))
            Box(Modifier.weight(1f))
            MumcordStageButton(
                icon = MumcordIcons.Maximize,
                contentDescription = stringResource(R.string.stream_expand),
                onClick = onExpand,
                size = 30.dp,
                iconSize = 16.dp,
                modifier = Modifier.testTag(STREAM_EXPAND_TEST_TAG),
            )
            MumcordStageButton(
                icon = MumcordIcons.X,
                contentDescription = stringResource(R.string.stream_stop),
                onClick = actions::close,
                size = 30.dp,
                iconSize = 16.dp,
                modifier = Modifier.testTag(STREAM_STOP_TEST_TAG),
            )
        }
        BasicText(
            text = title,
            style = MumcordTheme.typography.captionStrong.copy(color = colors.mediaText),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = MumcordSpace.s2, bottom = 6.dp, end = MumcordSpace.s2)
                .background(colors.mediaScrim, RoundedCornerShape(MumcordRadius.sm))
                .padding(horizontal = MumcordSpace.s2, vertical = 2.dp),
        )
    }
}

/** The system's picture-in-picture window (FR-5.76): only the picture, nothing to tap. */
@Composable
fun StreamPictureInPicture(stream: StreamUi) {
    val colors = MumcordTheme.colors
    Box(Modifier.fillMaxSize().background(colors.stageBg)) {
        if (stream.watch is StreamWatch.Watching && !stream.paused) {
            StreamPicture(stream.videoId, Modifier.fillMaxSize())
        }
        stream.openStatus()?.let { StageStatus(it, retry = null, small = true) }
    }
}

/**
 * The engine's question before the first direct connection. It is about the user's privacy, so
 * it says plainly what the other person sees. Going back answers no, the choice that shows less.
 */
@Composable
fun DirectConnectionsSheet(actions: StreamsController, topInsets: WindowInsets, bottomInsets: WindowInsets) {
    SheetOverlay(
        title = stringResource(R.string.direct_connections_title),
        onDismiss = { actions.answerDirectConnections(false) },
        topInsets = topInsets,
        bottomInsets = bottomInsets,
    ) {
        MumcordBanner(text = stringResource(R.string.direct_connections_body), horizontalMargin = 0.dp)
        MumcordButton(
            text = stringResource(R.string.direct_connections_yes),
            onClick = { actions.answerDirectConnections(true) },
            modifier = Modifier.fillMaxWidth().testTag(DIRECT_CONNECTIONS_YES_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.direct_connections_no),
            onClick = { actions.answerDirectConnections(false) },
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(DIRECT_CONNECTIONS_NO_TEST_TAG),
        )
    }
}

/** Turns the screen to landscape while [landscape], and lets it follow the phone again afterwards. */
@Composable
fun StreamOrientation(landscape: Boolean) {
    val activity = LocalContext.current.activity() ?: return
    DisposableEffect(activity, landscape) {
        activity.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The mini window's distance from the edge and from the bars below it. */
val MINI_WINDOW_MARGIN = MumcordSpace.s3
