// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.mumcord.mobile.design.components.avatar
import app.mumcord.mobile.design.theme.mumcordColors
import app.mumcord.mobile.design.tokens.MumcordColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2 contrast of the text and background pairs the components use, in both themes. */
class ContrastTest {
    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.compositeOver(background).luminance() + 0.05
        val b = background.luminance() + 0.05
        return if (a > b) a / b else b / a
    }

    private fun MumcordColors.surfaces(): Map<String, Color> = mapOf(
        "bgServers" to bgServers,
        "bgSidebar" to bgSidebar,
        "bgChat" to bgChat,
        "bgElevated" to bgElevated,
        "bgHover" to bgHover,
        "bgSelected" to bgSelected,
    )

    /** Text pairs: name, foreground, background. */
    private fun MumcordColors.textPairs(): List<Triple<String, Color, Color>> = buildList {
        for ((surfaceName, surface) in surfaces()) {
            add(Triple("text on $surfaceName", text, surface))
            add(Triple("textSecondary on $surfaceName", textSecondary, surface))
            add(Triple("textMuted on $surfaceName", textMuted, surface))
            add(Triple("accentText on $surfaceName", accentText, surface))
            add(Triple("danger on $surfaceName", danger, surface))
        }
        add(Triple("localMuted on bgSidebar", localMuted, bgSidebar))
        add(Triple("success on bgSidebar", success, bgSidebar))
        add(Triple("warning on bgSelected", warning, bgSelected))
        add(Triple("onAccent on accent", onAccent, accent))
        add(Triple("onAccent on danger", onAccent, danger))
        add(Triple("onAccent on badge", onAccent, accentStop2))
        add(Triple("primaryText on primaryBg", primaryText, primaryBg))
        add(Triple("onTalking on talking", onTalking, talking))
        add(Triple("onLive on live", onLive, live))
        add(Triple("mediaText on media label", mediaText, mediaScrim.compositeOver(mediaBg)))
        add(Triple("toast", bgSidebar, text))
        for (slot in 1..8) add(Triple("avatarText on avatar $slot", avatarText, avatar(slot)))
    }

    @Test
    fun textMeetsAaInBothThemes() {
        val failures = mutableListOf<String>()
        for (dark in listOf(true, false)) {
            for ((name, foreground, background) in mumcordColors(dark).textPairs()) {
                val ratio = contrast(foreground, background)
                if (ratio < 4.5) failures += "${if (dark) "dark" else "light"}: $name = ${"%.2f".format(ratio)}"
            }
        }
        assertTrue("Below 4.5:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun iconsAndControlOutlinesMeetThreeToOne() {
        val failures = mutableListOf<String>()
        for (dark in listOf(true, false)) {
            val colors = mumcordColors(dark)
            val pairs = listOf(
                Triple("accent icon on bgServers", colors.accent, colors.bgServers),
                Triple("talking ring on bgSidebar", colors.talking, colors.bgSidebar),
                Triple("talking border on bgSelected", colors.talking, colors.bgSelected),
                Triple("borderStrong on bgSidebar", colors.borderStrong, colors.bgSidebar),
                Triple("borderStrong on bgElevated", colors.borderStrong, colors.bgElevated),
                Triple("slider thumb on bgHover", colors.text, colors.bgHover),
            )
            for ((name, foreground, background) in pairs) {
                val ratio = contrast(foreground, background)
                if (ratio < 3.0) failures += "${if (dark) "dark" else "light"}: $name = ${"%.2f".format(ratio)}"
            }
        }
        assertTrue("Below 3:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
