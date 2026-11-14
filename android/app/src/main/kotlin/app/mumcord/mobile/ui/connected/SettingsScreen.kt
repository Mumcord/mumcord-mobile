// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.MobileSettings
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.components.MumcordToggle
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.tokens.MumcordSpace

const val SETTINGS_SCREEN_TEST_TAG = "settings"
const val SETTINGS_AUTOPLAY_TEST_TAG = "settings-autoplay-streams"
const val SETTINGS_LOAD_MEDIA_TEST_TAG = "settings-load-media"
const val SETTINGS_REDUCED_VOICE_TEST_TAG = "settings-reduced-voice"
const val SETTINGS_IDLE_TEST_TAG = "settings-idle-disconnect"

/**
 * The settings (mockup `16-settings`), as far as they exist: the sections "Mobile data"
 * (FR-5.41) and "Battery" (FR-5.42). Every switch is saved at once.
 */
@Composable
fun SettingsScreen(
    settings: MobileSettings,
    onChange: ((MobileSettings) -> MobileSettings) -> Unit,
    onBack: () -> Unit,
    topInsets: WindowInsets,
) {
    Column(Modifier.fillMaxSize().testTag(SETTINGS_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.settings_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.settings_back),
            windowInsets = topInsets,
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4)) {
            MumcordSectionHeader(stringResource(R.string.settings_section_mobile_data))
            MumcordList {
                SwitchRow(
                    title = stringResource(R.string.settings_autoplay_streams),
                    icon = MumcordIcons.Monitor,
                    checked = settings.mobileDataAutoplayStreams,
                    onCheckedChange = { on -> onChange { it.copy(mobileDataAutoplayStreams = on) } },
                    testTag = SETTINGS_AUTOPLAY_TEST_TAG,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_load_media),
                    icon = MumcordIcons.Image,
                    checked = settings.mobileDataLoadMedia,
                    onCheckedChange = { on -> onChange { it.copy(mobileDataLoadMedia = on) } },
                    testTag = SETTINGS_LOAD_MEDIA_TEST_TAG,
                    divider = true,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_reduced_voice),
                    icon = MumcordIcons.Wifi,
                    checked = settings.mobileDataReducedVoice,
                    onCheckedChange = { on -> onChange { it.copy(mobileDataReducedVoice = on) } },
                    testTag = SETTINGS_REDUCED_VOICE_TEST_TAG,
                    divider = true,
                )
            }
            MumcordSectionHeader(stringResource(R.string.settings_section_battery))
            MumcordList {
                SwitchRow(
                    title = stringResource(R.string.settings_idle_disconnect),
                    subtitle = stringResource(R.string.settings_idle_disconnect_sub),
                    icon = MumcordIcons.PhoneOff,
                    checked = settings.idleDisconnect,
                    onCheckedChange = { on -> onChange { it.copy(idleDisconnect = on) } },
                    testTag = SETTINGS_IDLE_TEST_TAG,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    subtitle: String? = null,
    divider: Boolean = false,
) {
    MumcordListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        divider = divider,
        // Nothing is cut off, also with a large font.
        wrapTitle = true,
        trailing = {
            MumcordToggle(
                checked = checked,
                onCheckedChange = onCheckedChange,
                contentDescription = title,
                modifier = Modifier.testTag(testTag),
            )
        },
    )
}
