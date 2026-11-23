// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.common

import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import app.mumcord.mobile.design.components.MumcordSheetFrame
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * A bottom sheet over the screen: the dimmed backdrop and the sheet frame with [title] and
 * scrolling [content]. Tapping the backdrop or going back calls [onDismiss]; a sheet with a
 * decision that must be made on purpose passes null and cannot be dismissed. A sheet without
 * a visible [title] names itself for screen readers with [paneTitle].
 *
 * The sheet is modal for screen readers too: the screen behind it is left out of the
 * accessibility tree, the screen reader's focus moves into the sheet when it opens and back to
 * the control that had it when it closes, and a dismissible sheet offers the "dismiss" action.
 */
@Composable
fun SheetOverlay(
    title: String?,
    onDismiss: (() -> Unit)?,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    paneTitle: String = title.orEmpty(),
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler { onDismiss?.invoke() }
    ScreenReaderFocusInSheet()
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            // Compose leaves every node out of the accessibility tree that lies completely under
            // a later node with semantics; a dialog over the whole screen thus hides the screen
            // behind it from TalkBack, as the backdrop hides it from the eye and the finger.
            .semantics {
                dialog()
                isTraversalGroup = true
            }
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
                .semantics {
                    this.paneTitle = paneTitle
                    this[SheetFrame] = Unit
                    if (onDismiss != null) {
                        dismiss {
                            onDismiss()
                            true
                        }
                    }
                },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s2),
                content = content,
            )
        }
    }
}

/** Marks the frame of a sheet, so that the screen reader's focus can be moved into it. */
private val SheetFrame = SemanticsPropertyKey<Unit>("MumcordSheetFrame")

/**
 * Moves the screen reader's focus to the first thing in the sheet that it reads once the sheet
 * is laid out, and back to the control that had it (the one that opened the sheet) when the
 * sheet goes away. Compose has no API for the accessibility focus, so this goes through the
 * view's accessibility node provider, whose virtual views are the semantics nodes; the nodes come
 * from the view's [RootForTest] side, the only public way to them. Without a screen reader nothing
 * has accessibility focus and nothing happens.
 */
@Composable
private fun ScreenReaderFocusInSheet() {
    val view = LocalView.current
    DisposableEffect(view) {
        val root = view as? RootForTest
        val provider = view.accessibilityNodeProvider
        val screenReader = view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        // The effect runs before the sheet is laid out, so the control behind it is still in the tree.
        val opener = if (root != null && provider != null) accessibilityFocusedNode(root, provider) else null
        if (root != null && provider != null && (opener != null || screenReader)) {
            view.post { firstReadNodeOfSheet(root)?.let { provider.focusForAccessibility(it) } }
        }
        onDispose {
            // Posted: until the next layout the gone sheet still covers the control.
            if (opener != null && provider != null) view.post { provider.focusForAccessibility(opener) }
        }
    }
}

private fun accessibilityFocusedNode(root: RootForTest, provider: AccessibilityNodeProvider): Int? {
    if (provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.isAccessibilityFocused != true) return null
    return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull { node ->
        !node.isRoot && provider.createAccessibilityNodeInfo(node.id)?.isAccessibilityFocused == true
    }?.id
}

/** The first node with something to read in the topmost sheet: its title, or else its first text or label. */
private fun firstReadNodeOfSheet(root: RootForTest): Int? {
    var sheet: SemanticsNode? = null
    fun visit(node: SemanticsNode) {
        if (SheetFrame in node.config) sheet = node
        node.children.forEach(::visit)
    }
    visit(root.semanticsOwner.rootSemanticsNode)
    return sheet?.let(::firstReadNode)?.id
}

private fun firstReadNode(node: SemanticsNode): SemanticsNode? {
    if (SemanticsProperties.Text in node.config || SemanticsProperties.ContentDescription in node.config) return node
    node.children.forEach { child -> firstReadNode(child)?.let { return it } }
    return null
}

private fun AccessibilityNodeProvider.focusForAccessibility(node: Int) {
    performAction(node, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
}

/** Running text that wraps, in the body style. Server-supplied text is drawn as plain characters. */
@Composable
fun Paragraph(text: String, modifier: Modifier = Modifier, color: Color = MumcordTheme.colors.textSecondary) {
    BasicText(text = text, style = MumcordTheme.typography.body.copy(color = color), modifier = modifier)
}
