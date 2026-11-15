// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.session.ThemeCodeRead
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay

const val THEMES_CODE_FIELD_TEST_TAG = "themes-code-field"
const val THEMES_APPLY_TEST_TAG = "themes-apply"
const val THEMES_PREVIEW_TEST_TAG = "themes-preview"
const val THEMES_SHARE_TEST_TAG = "themes-share"
const val THEMES_DELETE_TEST_TAG = "themes-delete"
const val THEMES_DELETE_CONFIRM_TEST_TAG = "themes-delete-confirm"

/** The open sheet of the themes screen. None of them has a mockup. */
@Composable
fun ThemesSheetHost(state: ThemesUiState, actions: ThemesController, topInsets: WindowInsets, bottomInsets: WindowInsets) {
    when (val sheet = state.sheet) {
        is ThemesSheet.Paste -> PasteSheet(sheet, actions, topInsets, bottomInsets)
        is ThemesSheet.Actions -> state.find(sheet.themeId)?.let { theme ->
            SheetOverlay(theme.name, actions::closeSheet, topInsets, bottomInsets) {
                MumcordList(horizontalMargin = MumcordSpace.s1) {
                    MumcordListRow(
                        title = stringResource(R.string.themes_share),
                        icon = MumcordIcons.Send,
                        onClick = { actions.share(theme.id) },
                        modifier = Modifier.testTag(THEMES_SHARE_TEST_TAG),
                    )
                    if (!theme.builtIn) {
                        MumcordListRow(
                            title = stringResource(R.string.themes_delete),
                            icon = MumcordIcons.Trash,
                            divider = true,
                            danger = true,
                            onClick = { actions.requestDelete(theme.id) },
                            modifier = Modifier.testTag(THEMES_DELETE_TEST_TAG),
                        )
                    }
                }
            }
        }
        is ThemesSheet.ConfirmDelete -> state.find(sheet.themeId)?.let { theme ->
            SheetOverlay(stringResource(R.string.themes_delete_title, theme.name), actions::closeSheet, topInsets, bottomInsets) {
                Paragraph(stringResource(R.string.themes_delete_message))
                MumcordButton(
                    text = stringResource(R.string.themes_delete_confirm),
                    onClick = { actions.confirmDelete(theme.id) },
                    style = MumcordButtonStyle.Danger,
                    icon = MumcordIcons.Trash,
                    modifier = Modifier.fillMaxWidth().testTag(THEMES_DELETE_CONFIRM_TEST_TAG),
                )
                MumcordButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = actions::closeSheet,
                    style = MumcordButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        null -> Unit
    }
}

/**
 * "Paste theme code": the pasted text is read by the engine as it changes; a theme found in it
 * is previewed in both versions before "Apply theme" imports it and makes it the app theme. The
 * text is untrusted: only the engine reads it, and only its name and checked colours are shown.
 */
@Composable
private fun PasteSheet(sheet: ThemesSheet.Paste, actions: ThemesController, topInsets: WindowInsets, bottomInsets: WindowInsets) {
    val error = when (sheet.read) {
        ThemeCodeRead.Invalid -> stringResource(R.string.themes_import_invalid)
        ThemeCodeRead.Unreadable -> stringResource(R.string.themes_import_unreadable)
        is ThemeCodeRead.Found, null -> null
    }
    SheetOverlay(stringResource(R.string.themes_import_title), actions::closeSheet, topInsets, bottomInsets) {
        MumcordTextField(
            value = sheet.text,
            onValueChange = actions::changePasteText,
            label = stringResource(R.string.themes_import_label),
            hint = stringResource(R.string.themes_import_hint),
            error = error,
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.testTag(THEMES_CODE_FIELD_TEST_TAG),
        )
        val found = sheet.read as? ThemeCodeRead.Found
        if (found != null) {
            CodePreview(found)
            MumcordButton(
                text = stringResource(R.string.themes_apply),
                onClick = actions::applyPasted,
                enabled = !sheet.applying,
                modifier = Modifier.fillMaxWidth().testTag(THEMES_APPLY_TEST_TAG),
            )
        }
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = actions::closeSheet,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The theme found in the pasted text: its name, both versions, and notes as on the desktop. */
@Composable
private fun CodePreview(found: ThemeCodeRead.Found) {
    val preview = found.preview
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.list)
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgSidebar, shape)
            .border(1.dp, colors.border, shape)
            .padding(MumcordSpace.s3)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(THEMES_PREVIEW_TEST_TAG),
    ) {
        Row {
            // The name comes from the pasted code: plain characters only.
            BasicText(
                text = preview.name,
                style = MumcordTheme.typography.bodyStrong.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (preview.importedId != null) {
                BasicText(stringResource(R.string.themes_imported), style = MumcordTheme.typography.small.copy(color = colors.success))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MumcordSpace.s1)) {
                ThemeSample(preview.dark, Modifier.fillMaxWidth().height(SAMPLE_HEIGHT))
                SampleLabel(stringResource(R.string.themes_mode_dark))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MumcordSpace.s1)) {
                ThemeSample(preview.light, Modifier.fillMaxWidth().height(SAMPLE_HEIGHT))
                SampleLabel(stringResource(R.string.themes_mode_light))
            }
        }
        if (preview.madeWithNewer) {
            BasicText(
                stringResource(R.string.themes_made_with_newer, preview.createdWith),
                style = MumcordTheme.typography.small.copy(color = colors.textMuted),
            )
        }
    }
}

private val SAMPLE_HEIGHT = 48.dp
