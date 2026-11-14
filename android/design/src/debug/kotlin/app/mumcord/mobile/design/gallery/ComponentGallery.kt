// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.mumcord.mobile.design.components.AppBarSample
import app.mumcord.mobile.design.components.BasicsSample
import app.mumcord.mobile.design.components.ButtonsSample
import app.mumcord.mobile.design.components.ChannelTreeSample
import app.mumcord.mobile.design.components.ChatSample
import app.mumcord.mobile.design.components.ControlsSample
import app.mumcord.mobile.design.components.FieldsSample
import app.mumcord.mobile.design.components.ListsSample
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.components.OverlaysSample
import app.mumcord.mobile.design.components.StreamTileSample
import app.mumcord.mobile.design.components.TabBarSample
import app.mumcord.mobile.design.components.TalkButtonSample
import app.mumcord.mobile.design.components.VoiceBarSample
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Every base component with the same sample content as its preview, one group under the other.
 * Only part of debug builds: it is the content of the debug-only component gallery screen.
 */
@Composable
fun ComponentGallery(modifier: Modifier = Modifier) {
    val groups: List<Pair<String, @Composable () -> Unit>> = listOf(
        "App bar" to { AppBarSample() },
        "Tiles, badge, dots, icon" to { BasicsSample() },
        "Buttons" to { ButtonsSample() },
        "Channel tree, people, avatars" to { ChannelTreeSample() },
        "Stream tile" to { StreamTileSample() },
        "Lists" to { ListsSample() },
        "Chat" to { ChatSample() },
        "Text field" to { FieldsSample() },
        "Toggle, slider, chips, segmented control" to { ControlsSample() },
        "Hint banner, bottom sheet" to { OverlaysSample() },
        "Hold-to-talk button" to { TalkButtonSample() },
        "Voice bar" to { VoiceBarSample() },
        "Tab bar" to { TabBarSample() },
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = modifier.fillMaxWidth().padding(bottom = MumcordSpace.s5),
    ) {
        groups.forEach { (title, sample) ->
            MumcordSectionHeader(title)
            sample()
        }
    }
}
