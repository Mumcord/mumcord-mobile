// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAppBarSubtitle
import app.mumcord.mobile.design.components.MumcordChannelBody
import app.mumcord.mobile.design.components.MumcordChannelRow
import app.mumcord.mobile.design.components.MumcordDot
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordPersonRow
import app.mumcord.mobile.design.components.MumcordPersonStatus
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordStatusTone
import app.mumcord.mobile.design.components.MumcordStreamTile
import app.mumcord.mobile.design.components.MumcordTab
import app.mumcord.mobile.design.components.MumcordTabBar
import app.mumcord.mobile.design.components.MumcordTile
import app.mumcord.mobile.design.components.MumcordTree
import app.mumcord.mobile.design.components.MumcordVoiceBar
import app.mumcord.mobile.design.components.MumcordVoiceBarLabels
import app.mumcord.mobile.design.icons.MumcordIcons

/**
 * Mockup `02-channels` rebuilt from the design system with static demo data (server Nordlicht).
 * It proves the system against the mockup and is not connected to the engine; the real channel
 * screen is built by a later task.
 */
@Composable
fun ChannelsReferenceScreen(
    onOpenGallery: () -> Unit,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    MumcordScreen {
        MumcordAppBar(
            title = "Nordlicht",
            windowInsets = topInsets,
            leading = { MumcordTile("NO", brand = true, small = true) },
            subtitle = {
                MumcordDot()
                MumcordAppBarSubtitle("5 online · 24 ms")
            },
        ) {
            MumcordIconButton(MumcordIcons.Search, contentDescription = "Search channels and people", onClick = {})
            MumcordIconButton(MumcordIcons.More, contentDescription = "Server menu", onClick = onOpenGallery)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            DemoChannelTree()
        }
        MumcordVoiceBar(
            channelName = "Lobby",
            status = "Nordlicht · Voice connected",
            talking = false,
            muted = false,
            deafened = false,
            labels = MumcordVoiceBarLabels(
                open = "Open voice controls",
                talking = "You are talking",
                notTalking = "You are not talking",
                mute = "Mute microphone",
                unmute = "Unmute microphone",
                deafen = "Deafen",
                undeafen = "Undeafen",
                leave = "Leave server",
            ),
            onOpen = {},
            onToggleMute = {},
            onToggleDeafen = {},
            onLeave = {},
        )
        MumcordTabBar(
            tabs = listOf(
                MumcordTab(MumcordIcons.List, "Channels"),
                MumcordTab(MumcordIcons.Hash, "Chat"),
                MumcordTab(MumcordIcons.Messages, "Messages", badge = "2", contentDescription = "Messages, 2 unread"),
                MumcordTab(MumcordIcons.More, "More"),
            ),
            selectedIndex = 0,
            onSelect = {},
            windowInsets = bottomInsets,
        )
    }
}

@Composable
private fun DemoChannelTree() {
    MumcordTree {
        MumcordChannelRow(
            name = "Lobby",
            onClick = {},
            icon = MumcordIcons.Volume,
            expanded = true,
            toggleDescription = "Collapse Lobby",
            memberCount = 3,
            current = true,
            meta = "you are here",
        )
        MumcordChannelBody {
            MumcordPersonRow("Anna", initials = "A", colorSlot = 4, onClick = {})
            MumcordPersonRow("Ben", initials = "B", colorSlot = 8, onClick = {}, talking = true, talkingDescription = "Talking")
            MumcordPersonRow("Lea", initials = "L", colorSlot = 2, onClick = {}, note = "(you)")
        }
        MumcordChannelRow(
            name = "Gaming",
            onClick = {},
            expanded = true,
            toggleDescription = "Collapse Gaming",
            memberCount = 2,
        )
        MumcordChannelBody {
            MumcordPersonRow(
                name = "Mira",
                initials = "M",
                colorSlot = 6,
                onClick = {},
                status = listOf(MumcordPersonStatus(MumcordIcons.Monitor, "Sharing screen", MumcordStatusTone.Live)),
            )
            MumcordStreamTile(
                label = "Mira's screen",
                liveLabel = "LIVE",
                viewerCount = 1,
                contentDescription = "Mira's screen, live, 1 viewer",
                onClick = {},
                // Lines up with the names: avatar plus gap of a person row.
                modifier = Modifier.padding(start = 50.dp, bottom = 6.dp).width(232.dp),
            )
            MumcordPersonRow(
                name = "Jonas",
                initials = "J",
                colorSlot = 3,
                onClick = {},
                status = listOf(
                    MumcordPersonStatus(MumcordIcons.MicOff, "Muted", MumcordStatusTone.Off),
                    MumcordPersonStatus(MumcordIcons.HeadphonesOff, "Deafened", MumcordStatusTone.Off),
                ),
            )
        }
        MumcordChannelRow("Music", onClick = {}, empty = true, lockedDescription = "Password protected")
        MumcordChannelRow("AFK", onClick = {}, empty = true)
    }
}
