// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.ViewChecker
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import java.io.File
import java.util.Locale
import org.junit.Assert.fail
import org.robolectric.shadows.ShadowBuild

/**
 * An error of the accessibility checks that is accepted on purpose. [check] is the simple name of
 * the check, [element] a part of the element's description in the report, [reason] why it is fine.
 */
private data class Allowed(val check: String, val element: String, val reason: String)

private val ALLOWED: List<Allowed> = emptyList()

/**
 * Runs Google's Accessibility Test Framework (the checks of Android's Accessibility Scanner) on
 * the drawn screen: labels of controls, touch target sizes, text and image contrast, duplicate or
 * redundant labels, traversal order. Every finding is written to build/outputs/accessibility, and
 * an error that is not in [ALLOWED] fails the test. With `-PaccessibilityAudit` nothing fails,
 * which is how the counts of a whole run are taken.
 */
fun ComposeContentTestRule.checkAccessibility(name: String) {
    val output = File(checkNotNull(System.getProperty("mumcord.accessibility.output")))
    val audit = System.getProperty("mumcord.accessibility.audit").toBoolean()
    val root = onRoot()
    val view = (root.fetchSemanticsNode().root as ViewRootForTest).view
    val screenshot = root.captureToImage().asAndroidBitmap()
    val sheets = sheetBounds(root.fetchSemanticsNode())
    val results = runChecks(view, screenshot).filter {
        it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING
    }
    val lines = results.map { result ->
        val check = result.sourceCheckClass.simpleName
        val element = describe(result)
        val allowed = result.type == AccessibilityCheckResultType.ERROR &&
            ALLOWED.any { it.check == check && element.contains(it.element) }
        val type = when {
            isBehindSheet(result, sheets) -> "BEHIND_SHEET"
            isOnlyClipped(result) -> "CLIPPED"
            allowed -> "ALLOWED"
            else -> result.type.name
        }
        listOf(name, type, check, element, result.getMessage(Locale.ENGLISH).toString().replace('\n', ' '), result.metadata.toString())
    }.toMutableList()
    if (!hasHeading(root.fetchSemanticsNode())) lines += listOf(name, "INFO", "NoHeading", "", "The screen has no heading.")
    output.mkdirs()
    File(output, "$name.tsv").writeText(lines.joinToString("") { it.joinToString("\t") + "\n" })
    val errors = lines.filter { it[1] == "ERROR" }
    if (errors.isNotEmpty() && !audit) {
        fail("Accessibility errors on $name:\n" + errors.joinToString("\n") { "${it[2]}: ${it[3]}: ${it[4]}" })
    }
}

private fun runChecks(view: View, screenshot: Bitmap): List<AccessibilityViewCheckResult> {
    // The framework skips Robolectric when it sees Robolectric's build fingerprint.
    val fingerprint = Build.FINGERPRINT
    ShadowBuild.setFingerprint("mumcord-screenshots")
    try {
        val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
        val parameters = Parameters().apply { putScreenCapture(BitmapImage(screenshot)) }
        return ViewChecker().apply { setObtainCharacterLocations(true) }.runChecksOnView(checks, view, parameters)
    } finally {
        ShadowBuild.setFingerprint(fingerprint)
    }
}

/**
 * The frames of the bottom sheets on screen: panes (they carry a pane title) that do not cover
 * the whole screen. The full voice view is a pane too, but fills the screen.
 */
private fun sheetBounds(root: SemanticsNode): List<Rect> {
    val screen = root.boundsInWindow
    val panes = mutableListOf<Rect>()
    fun visit(node: SemanticsNode) {
        val bounds = node.boundsInWindow
        if (SemanticsProperties.PaneTitle in node.config && bounds.height < screen.height * SHEET_MAX_SHARE) panes += bounds
        node.children.forEach(::visit)
    }
    visit(root)
    return panes
}

private const val SHEET_MAX_SHARE = 0.95f

/**
 * A finding on the screen behind an open sheet: dimmed by the backdrop and partly covered, so its
 * contrast and visible size are not what the user meets. These are counted on their own. A sheet
 * leaves the screen behind it out of the accessibility tree, so there should be none.
 */
private fun isBehindSheet(result: AccessibilityViewCheckResult, sheets: List<Rect>): Boolean {
    if (sheets.isEmpty()) return false
    val bounds = result.element?.boundsInScreen ?: return false
    val element = Rect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
    return sheets.none { it.contains(element.topLeft) && it.contains(Offset(element.right - 1, element.bottom - 1)) }
}

/**
 * A touch target that is only too small on the picture because the edge of a scrolling list, a
 * sheet or the screen cuts it; its whole size is large enough.
 */
private fun isOnlyClipped(result: AccessibilityViewCheckResult): Boolean {
    if (result.sourceCheckClass != TouchTargetSizeCheck::class.java) return false
    val metadata = result.metadata ?: return false
    if (!metadata.getBoolean(TouchTargetSizeCheck.KEY_IS_CLIPPED_BY_ANCESTOR, false)) return false
    val height = metadata.getInt(TouchTargetSizeCheck.KEY_NONCLIPPED_HEIGHT, metadata.getInt(TouchTargetSizeCheck.KEY_HEIGHT, 0))
    val width = metadata.getInt(TouchTargetSizeCheck.KEY_NONCLIPPED_WIDTH, metadata.getInt(TouchTargetSizeCheck.KEY_WIDTH, 0))
    return height >= metadata.getInt(TouchTargetSizeCheck.KEY_REQUIRED_HEIGHT, MIN_TOUCH_TARGET) &&
        width >= metadata.getInt(TouchTargetSizeCheck.KEY_REQUIRED_WIDTH, MIN_TOUCH_TARGET)
}

private const val MIN_TOUCH_TARGET = 48

private fun describe(result: AccessibilityViewCheckResult): String {
    val element = result.element ?: return ""
    // Typed by hand: the framework's own annotations are not on the test class path.
    val description: CharSequence? = element.contentDescription
    val text: CharSequence? = element.text
    val label = listOfNotNull(description, text).joinToString(" / ")
    return "${element.className} \"$label\" ${element.boundsInScreen}"
}

private fun hasHeading(node: SemanticsNode): Boolean =
    SemanticsProperties.Heading in node.config || node.children.any { hasHeading(it) }
