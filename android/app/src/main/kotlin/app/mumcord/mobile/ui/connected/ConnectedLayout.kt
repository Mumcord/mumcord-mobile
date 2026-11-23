// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How the connected screens are laid out in the space the app has. */
enum class ConnectedLayout { OneColumn, TwoColumns }

/** From this width the app uses two columns (specification section 5, "regular width"). */
val TWO_COLUMNS_MIN_WIDTH = 600.dp

/**
 * Below this height the app stays in one column even when it is wide: a phone turned to
 * landscape is wide but low, and its landscape stream must keep the whole screen.
 */
val TWO_COLUMNS_MIN_HEIGHT = 480.dp

/**
 * The layout for the space the app has right now: the window, or its part of a split screen.
 * It is decided again on every rotation and resize.
 */
fun connectedLayout(width: Dp, height: Dp): ConnectedLayout =
    if (width >= TWO_COLUMNS_MIN_WIDTH && height >= TWO_COLUMNS_MIN_HEIGHT) ConnectedLayout.TwoColumns else ConnectedLayout.OneColumn

/** The destinations of the navigation rail; the phone's Chat tab is part of Channels there. */
val RAIL_TABS = listOf(ConnectedTab.Channels, ConnectedTab.Messages, ConnectedTab.More)

/**
 * The tab as two columns show it. The Chat tab of the phone is the right column of Channels,
 * so a Chat tab chosen on the phone is shown as Channels; the tab itself is kept, so making the
 * window narrow again shows the chat as before.
 */
fun ConnectedTab.inTwoColumns(): ConnectedTab = if (this == ConnectedTab.Chat) ConnectedTab.Channels else this
