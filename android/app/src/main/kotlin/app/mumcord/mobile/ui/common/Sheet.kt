// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import app.mumcord.mobile.design.components.MumcordSheetFrame
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * A bottom sheet over the screen: the dimmed backdrop and the sheet frame with [title] and
 * scrolling [content]. Tapping the backdrop or going back calls [onDismiss]; a sheet with a
 * decision that must be made on purpose passes null and cannot be dismissed.
 */
@Composable
fun SheetOverlay(
    title: String,
    onDismiss: (() -> Unit)?,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler { onDismiss?.invoke() }
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(MumcordTheme.colors.backdrop)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss?.invoke() } }
            .windowInsetsPadding(topInsets)
            .imePadding(),
    ) {
        MumcordSheetFrame(
            title = title,
            windowInsets = bottomInsets,
            modifier = Modifier
                // Taps on the sheet itself must not reach the backdrop.
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = title },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s2),
                content = content,
            )
        }
    }
}

/** Running text that wraps, in the body style. Server-supplied text is drawn as plain characters. */
@Composable
fun Paragraph(text: String, modifier: Modifier = Modifier, color: Color = MumcordTheme.colors.textSecondary) {
    BasicText(text = text, style = MumcordTheme.typography.body.copy(color = color), modifier = modifier)
}
