// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAvatar
import app.mumcord.mobile.design.components.MumcordAvatarSize
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.connected.initials

const val IDENTITY_CARD_TEST_TAG = "identity-card"
const val IDENTITY_SCAN_TEST_TAG = "identity-scan"
const val IDENTITY_IMPORT_TEST_TAG = "identity-import"
const val IDENTITY_CREATE_TEST_TAG = "identity-create"
const val IDENTITY_FILE_NOTICE_TEST_TAG = "identity-file-notice"

private const val AVATAR_COLOURS = 8

/** The identity list with the three ways to add one (mockup 17). */
@Composable
fun IdentitiesScreen(
    state: IdentitiesUiState,
    onBack: () -> Unit,
    onDetails: (Long) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    MumcordScreen {
        MumcordAppBar(
            title = stringResource(R.string.identities_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.action_back),
            windowInsets = topInsets,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(bottomInsets)
                .padding(bottom = MumcordSpace.s4),
        ) {
            BasicText(
                text = stringResource(R.string.identities_intro),
                // Android draws the face a hair wider than the browser; the letters sit a little
                // closer so that the sentence stays on one line at the standard size, as in the mockup.
                style = MumcordTheme.typography.small.copy(
                    color = MumcordTheme.colors.textSecondary,
                    letterSpacing = (-0.01).em,
                ),
                modifier = Modifier.padding(start = MumcordSpace.s4, top = MumcordSpace.s2, end = MumcordSpace.s4),
            )
            MumcordSectionHeader(stringResource(R.string.identities_section_yours))
            Column(verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
                state.rows.forEach { row -> IdentityCard(row, onClick = { onDetails(row.id) }) }
            }
            MumcordSectionHeader(stringResource(R.string.identities_section_add))
            AddIdentityOptions(state.unreadableFile, onScan, onImport, onCreate)
        }
    }
}

/**
 * First start: the app needs an identity before anything else. The same three ways as on the
 * identity list; there is nothing to skip, because every server wants a certificate.
 */
@Composable
fun IdentitySetupScreen(
    unreadableFile: Boolean,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    MumcordScreen {
        Column(
            modifier = Modifier
                .windowInsetsPadding(topInsets)
                .windowInsetsPadding(bottomInsets)
                .verticalScroll(rememberScrollState())
                .padding(top = MumcordSpace.s5, bottom = MumcordSpace.s3),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
                modifier = Modifier.padding(
                    start = MumcordSpace.s4,
                    top = MumcordSpace.s4,
                    end = MumcordSpace.s4,
                    bottom = MumcordSpace.s5,
                ),
            ) {
                BasicText(
                    text = stringResource(R.string.identity_setup_title),
                    // The intro title of the first-start screens, as on the talk mode choice.
                    style = MumcordTheme.typography.title.copy(
                        color = MumcordTheme.colors.text,
                        fontSize = 30.sp,
                        lineHeight = 1.15.em,
                        letterSpacing = (-0.02).em,
                    ),
                    modifier = Modifier.semantics { heading() },
                )
                Paragraph(stringResource(R.string.identity_setup_text))
            }
            AddIdentityOptions(unreadableFile, onScan, onImport, onCreate)
        }
    }
}

@Composable
private fun AddIdentityOptions(
    unreadableFile: Boolean,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3)) {
        AddOption(
            icon = MumcordIcons.QrCode,
            title = stringResource(R.string.identities_scan),
            subtitle = stringResource(R.string.identities_scan_sub),
            tag = stringResource(R.string.identities_scan_tag),
            onClick = onScan,
            modifier = Modifier.testTag(IDENTITY_SCAN_TEST_TAG),
        )
        AddOption(
            icon = MumcordIcons.Paperclip,
            title = stringResource(R.string.identities_import),
            subtitle = stringResource(R.string.identities_import_sub),
            onClick = onImport,
            modifier = Modifier.testTag(IDENTITY_IMPORT_TEST_TAG),
        )
        if (unreadableFile) {
            MumcordBanner(
                text = stringResource(R.string.identities_file_unreadable),
                icon = MumcordIcons.Alert,
                tone = MumcordBannerTone.Danger,
                modifier = Modifier.testTag(IDENTITY_FILE_NOTICE_TEST_TAG),
            )
        }
        AddOption(
            icon = MumcordIcons.Plus,
            title = stringResource(R.string.identities_create),
            subtitle = stringResource(R.string.identities_create_sub),
            onClick = onCreate,
            modifier = Modifier.testTag(IDENTITY_CREATE_TEST_TAG),
        )
    }
}

/** One identity (`.idcard` of the mockup); tapping it opens its sheet. */
@Composable
private fun IdentityCard(row: IdentityRow, onClick: () -> Unit) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val title = if (row.isDefault) stringResource(R.string.identities_default, row.label) else row.label
    val used = usedOn(row.serverCount)
    val description = stringResource(R.string.identities_card_description, title, used)
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = Modifier
            .padding(horizontal = MumcordSpace.s4)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bgElevated)
            .border(1.dp, colors.border, shape)
            .testTag(IDENTITY_CARD_TEST_TAG)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(MumcordSpace.s4),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MumcordAvatar(
                initials = row.label.initials(1),
                // Always the same colour for the same identity (Spec 03 FR-3.1).
                colorSlot = Math.floorMod(row.id, AVATAR_COLOURS.toLong()).toInt() + 1,
                size = MumcordAvatarSize.Large,
            )
            Column(Modifier.weight(1f)) {
                BasicText(title, style = MumcordTheme.typography.bodyStrong.copy(color = colors.text))
                BasicText(used, style = MumcordTheme.typography.small.copy(color = colors.textMuted))
            }
            MumcordIcon(MumcordIcons.Shield, contentDescription = null, tint = colors.success)
        }
        BasicText(
            text = stringResource(R.string.identities_fingerprint, row.fingerprintStart),
            style = MumcordTheme.typography.caption.copy(
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.02.em,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgSidebar, RoundedCornerShape(MumcordRadius.md))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun usedOn(serverCount: Int): String =
    if (serverCount == 0) {
        stringResource(R.string.identities_unused)
    } else {
        pluralStringResource(R.plurals.identities_used_on, serverCount, serverCount)
    }

/** One way to add an identity (`.opt` of the mockup); with a [tag] it is the recommended one. */
@Composable
private fun AddOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val best = tag != null
    val border = if (best) 2.dp else 1.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = MumcordSpace.s4)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .clip(shape)
            .background(if (best) colors.bgSelected else colors.bgElevated)
            .border(border, if (best) colors.accent else colors.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = MumcordSpace.s4 + border, vertical = MumcordSpace.s3 + border),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .background(if (best) colors.accent else colors.bgHover, RoundedCornerShape(12.dp)),
        ) {
            MumcordIcon(
                icon = icon,
                contentDescription = null,
                tint = if (best) colors.onAccent else colors.accentText,
                size = MumcordSize.iconLg,
            )
        }
        Column(Modifier.weight(1f)) {
            // The tag follows the title and moves to the next line where both do not fit.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = title,
                    style = MumcordTheme.typography.bodyStrong.copy(color = colors.text),
                )
                if (tag != null) {
                    BasicText(
                        text = tag,
                        style = MumcordTheme.typography.captionStrong.copy(color = colors.onAccent),
                        modifier = Modifier.background(colors.accent, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            BasicText(subtitle, style = MumcordTheme.typography.small.copy(color = colors.textMuted))
        }
        MumcordIcon(MumcordIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
    }
}
