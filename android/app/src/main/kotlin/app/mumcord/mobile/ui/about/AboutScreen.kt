// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordListRowValue
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.update.AvailableUpdate

const val ABOUT_SCREEN_TEST_TAG = "about"
const val ABOUT_LICENCES_TEST_TAG = "about-licences"
const val ABOUT_SOURCE_TEST_TAG = "about-source"
const val ABOUT_CRASH_REPORTS_TEST_TAG = "about-crash-reports"
const val LICENCES_SCREEN_TEST_TAG = "licences"
const val LICENCES_RETRY_TEST_TAG = "licences-retry"
const val UPDATE_NOTICE_TEST_TAG = "update-notice"
const val UPDATE_DOWNLOAD_TEST_TAG = "update-download"
const val UPDATE_DISMISS_TEST_TAG = "update-dismiss"

/**
 * About Mumcord (FR-5.100, no mockup): the app and engine versions, the third-party licences,
 * the source code of this version (AGPL-3.0), the saved crash reports (FR-5.103) and what the
 * app contacts besides the servers. A newer release is offered at the top (FR-5.101).
 */
@Composable
fun AboutScreen(
    state: AboutUiState,
    onBack: () -> Unit,
    onLicences: () -> Unit,
    onCrashReports: () -> Unit,
    onDismissUpdate: () -> Unit,
    topInsets: WindowInsets,
) {
    val links = LocalUriHandler.current
    Column(Modifier.fillMaxSize().testTag(ABOUT_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.about_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.about_back),
            windowInsets = topInsets,
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4)) {
            state.update?.let { UpdateNotice(it, onDismissUpdate) }
            Spacer(Modifier.height(MumcordSpace.s2))
            MumcordList {
                MumcordListRow(
                    title = stringResource(R.string.about_version),
                    icon = MumcordIcons.Star,
                    trailing = { MumcordListRowValue(state.appVersion) },
                )
                MumcordListRow(
                    title = stringResource(R.string.about_engine),
                    icon = MumcordIcons.Settings,
                    divider = true,
                    trailing = { MumcordListRowValue(state.engineVersion) },
                )
            }
            Spacer(Modifier.height(MumcordSpace.s3))
            MumcordList {
                MumcordListRow(
                    title = stringResource(R.string.about_licences),
                    icon = MumcordIcons.List,
                    trailing = { Chevron() },
                    onClick = onLicences,
                    modifier = Modifier.testTag(ABOUT_LICENCES_TEST_TAG),
                )
                MumcordListRow(
                    title = stringResource(R.string.about_source),
                    subtitle = stringResource(R.string.about_source_sub),
                    icon = MumcordIcons.Link,
                    divider = true,
                    wrapTitle = true,
                    trailing = { Chevron() },
                    onClick = { links.openSafely(state.sourceUrl) },
                    modifier = Modifier.testTag(ABOUT_SOURCE_TEST_TAG),
                )
                MumcordListRow(
                    title = stringResource(R.string.about_crash_reports),
                    icon = MumcordIcons.Alert,
                    divider = true,
                    trailing = { Chevron() },
                    onClick = onCrashReports,
                    modifier = Modifier.testTag(ABOUT_CRASH_REPORTS_TEST_TAG),
                )
            }
            Paragraph(stringResource(R.string.about_licence_text))
            Paragraph(stringResource(R.string.about_privacy_text))
        }
    }
}

/** The third-party licences of this build, as bundled into the APK (`THIRD_PARTY_NOTICES.txt`). */
@Composable
fun LicencesScreen(licences: Licences, onBack: () -> Unit, onRetry: () -> Unit, topInsets: WindowInsets) {
    Column(Modifier.fillMaxSize().testTag(LICENCES_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.about_licences),
            onBack = onBack,
            backContentDescription = stringResource(R.string.licences_back),
            windowInsets = topInsets,
        )
        when (licences) {
            is Licences.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
                items(licences.blocks) { block ->
                    // Licence texts keep their line breaks and indents, which a fixed-width font shows as meant.
                    BasicText(
                        text = block.text,
                        style = if (block.heading) {
                            MumcordTheme.typography.smallStrong.copy(color = MumcordTheme.colors.text)
                        } else {
                            MumcordTheme.typography.caption.copy(
                                color = MumcordTheme.colors.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = MumcordSpace.s4,
                                top = if (block.heading) MumcordSpace.s4 else MumcordSpace.s2,
                                end = MumcordSpace.s4,
                                bottom = MumcordSpace.s2,
                            )
                            .then(if (block.heading) Modifier.semantics { heading() } else Modifier),
                    )
                }
            }
            Licences.Failed -> {
                MumcordBanner(
                    text = stringResource(R.string.licences_failed),
                    icon = MumcordIcons.Alert,
                    tone = MumcordBannerTone.Danger,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                MumcordButton(
                    text = stringResource(R.string.licences_retry),
                    onClick = onRetry,
                    small = true,
                    modifier = Modifier.padding(horizontal = MumcordSpace.s4).testTag(LICENCES_RETRY_TEST_TAG),
                )
            }
            // Reading takes a moment at most; an empty page is calmer than a flashing spinner.
            Licences.Loading, Licences.NotRead -> Unit
        }
    }
}

/**
 * "Version X is available" (FR-5.101, no mockup) on the server list and the About screen.
 * "Download" opens the signed APK of the release in the browser; Android itself installs it
 * and refuses one that is not signed with the same key. "Not now" hides it until a newer one.
 */
@Composable
fun UpdateNotice(update: AvailableUpdate, onDismiss: () -> Unit) {
    val links = LocalUriHandler.current
    Column(Modifier.testTag(UPDATE_NOTICE_TEST_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
        MumcordBanner(
            title = stringResource(R.string.update_available, update.version.toString()),
            text = stringResource(R.string.update_available_text),
            icon = MumcordIcons.Star,
            tone = MumcordBannerTone.Info,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            modifier = Modifier.padding(horizontal = MumcordSpace.s4),
        ) {
            MumcordButton(
                text = stringResource(R.string.update_download),
                onClick = { links.openSafely(update.downloadUrl) },
                small = true,
                modifier = Modifier.testTag(UPDATE_DOWNLOAD_TEST_TAG),
            )
            MumcordButton(
                text = stringResource(R.string.update_not_now),
                onClick = onDismiss,
                style = MumcordButtonStyle.Ghost,
                small = true,
                modifier = Modifier.testTag(UPDATE_DISMISS_TEST_TAG),
            )
        }
    }
}

/** A phone without a browser has nothing to open the address with; the tap then does nothing. */
private fun UriHandler.openSafely(url: String) {
    try {
        openUri(url)
    } catch (_: IllegalArgumentException) {
        // AndroidUriHandler reports a missing browser this way.
    }
}

@Composable
private fun Paragraph(text: String) {
    BasicText(
        text = text,
        style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textSecondary),
        modifier = Modifier.padding(start = MumcordSpace.s4, top = MumcordSpace.s3, end = MumcordSpace.s4),
    )
}

@Composable
private fun Chevron() {
    MumcordIcon(MumcordIcons.ChevronRight, contentDescription = null, tint = MumcordTheme.colors.textMuted)
}
