// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * One tab. [badge] is the unread count shown on the icon; [contentDescription] replaces the
 * label for TalkBack when the badge needs words ("Messages, 2 unread").
 */
@Immutable
data class MumcordTab(
    val icon: ImageVector,
    val label: String,
    val badge: String? = null,
    val contentDescription: String? = null,
)

/**
 * Bottom tab bar (`.tabbar`) for the four main areas. It fills the space of the system
 * navigation bar with its own background through [windowInsets].
 */
@Composable
fun MumcordTabBar(
    tabs: List<MumcordTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
) {
    val colors = MumcordTheme.colors
    val bottomInset = with(LocalDensity.current) { windowInsets.getBottom(this).toDp() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgServers)
            .drawBehind {
                val y = 0.5.dp.toPx()
                drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            // 1 dp of the top padding is the border line. The labels sit 8 dp into the system
            // bar area, as in the mockups, but never closer than 6 dp to the edge.
            .padding(start = 8.dp, top = 7.dp, end = 8.dp, bottom = (bottomInset - 8.dp).coerceAtLeast(6.dp))
            .selectableGroup(),
    ) {
        tabs.forEachIndexed { index, tab ->
            TabItem(
                tab = tab,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The same tabs as a navigation rail at the left edge (`.tabbar--rail`), for two columns on
 * tablets. It reaches up into the status bar area with its own background and starts its tabs
 * below it ([windowInsets]).
 */
@Composable
fun MumcordNavigationRail(
    tabs: List<MumcordTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.statusBars,
) {
    val colors = MumcordTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(colors.bgServers)
            .drawBehind {
                val x = size.width - 0.5.dp.toPx()
                drawLine(colors.border, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            .windowInsetsPadding(windowInsets)
            .padding(vertical = MumcordSpace.s3)
            .selectableGroup(),
    ) {
        tabs.forEachIndexed { index, tab ->
            TabItem(
                tab = tab,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp),
            )
        }
    }
}

/** Width of the navigation rail (`.tabbar--rail`). */
val RAIL_WIDTH = 88.dp

@Composable
private fun TabItem(tab: MumcordTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MumcordTheme.colors
    val pill = if (selected) colors.accent.copy(alpha = MumcordTheme.opacities.tabActive) else Color.Transparent
    val description = tab.contentDescription
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        modifier = modifier
            .defaultMinSize(minHeight = 50.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .then(
                if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = 56.dp, height = 28.dp).background(pill, CircleShape),
        ) {
            MumcordIcon(tab.icon, contentDescription = null, tint = if (selected) colors.accent else colors.textMuted)
            if (tab.badge != null) {
                MumcordBadge(
                    text = tab.badge,
                    ringColor = colors.bgServers,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = 31.dp, y = (-5).dp),
                )
            }
        }
        val style = MumcordTheme.typography.tab
        BasicText(
            text = tab.label,
            style = style.copy(color = if (selected) colors.text else colors.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // With a large system font the label shrinks to fit its tab, down to the normal size,
            // instead of being cut off ("Chann…"); only then does it end in an ellipsis.
            // Sizes in sp grow with the font scale, so dividing by it gives the normal size on screen.
            autoSize = TextAutoSize.StepBased(
                minFontSize = style.fontSize / LocalDensity.current.fontScale.coerceAtLeast(1f),
                maxFontSize = style.fontSize,
            ),
            modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

@Preview(name = "Tab bar dark")
@Composable
private fun TabBarDarkPreview() {
    PreviewFrame(darkTheme = true) { TabBarSample() }
}

@Preview(name = "Tab bar light")
@Composable
private fun TabBarLightPreview() {
    PreviewFrame(darkTheme = false) { TabBarSample() }
}

@Preview(name = "Navigation rail dark", heightDp = 320)
@Composable
private fun NavigationRailDarkPreview() {
    PreviewFrame(darkTheme = true) { NavigationRailSample() }
}

@Composable
internal fun NavigationRailSample() {
    MumcordNavigationRail(
        tabs = listOf(
            MumcordTab(MumcordIcons.List, "Channels"),
            MumcordTab(MumcordIcons.Messages, "Messages", badge = "2", contentDescription = "Messages, 2 unread"),
            MumcordTab(MumcordIcons.More, "More"),
        ),
        selectedIndex = 0,
        onSelect = {},
        windowInsets = WindowInsets(),
    )
}

@Composable
internal fun TabBarSample() {
    MumcordTabBar(
        tabs = listOf(
            MumcordTab(MumcordIcons.List, "Channels"),
            MumcordTab(MumcordIcons.Hash, "Chat"),
            MumcordTab(MumcordIcons.Messages, "Messages", badge = "2", contentDescription = "Messages, 2 unread"),
            MumcordTab(MumcordIcons.More, "More"),
        ),
        selectedIndex = 0,
        onSelect = {},
        windowInsets = WindowInsets(),
    )
}
