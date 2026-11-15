// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay
import app.mumcord.mobile.ui.connect.messageRes
import java.text.DateFormat
import java.util.Date

const val IDENTITY_DETAILS_CLOSE_TEST_TAG = "identity-details-close"
const val IDENTITY_SET_DEFAULT_TEST_TAG = "identity-set-default"
const val IDENTITY_EXPORT_TEST_TAG = "identity-export"
const val IDENTITY_DELETE_TEST_TAG = "identity-delete"
const val IDENTITY_CREATE_CONFIRM_TEST_TAG = "identity-create-confirm"
const val IDENTITY_IMPORT_CONFIRM_TEST_TAG = "identity-import-confirm"
const val IDENTITY_EXPORT_CONFIRM_TEST_TAG = "identity-export-confirm"
const val IDENTITY_DELETE_CONFIRM_TEST_TAG = "identity-delete-confirm"
const val IDENTITY_DELETE_BACKUP_TEST_TAG = "identity-delete-backup"

/** What the identity sheets let the user do. */
interface IdentitySheetActions {
    fun closeSheet()
    fun setDefault(identityId: Long)
    fun openExport(identityId: Long)
    fun openDelete(identityId: Long)
    fun changeCreateName(name: String)
    fun create()
    fun changeImportPassword(password: String)
    fun confirmImport()
    fun changeExportPassword(password: String)
    fun changeExportRepeat(repeat: String)
    fun export()
    fun changeDeleteConfirmation(typed: String)
    fun delete()
}

@Composable
fun IdentitySheetHost(
    sheet: IdentitySheet,
    actions: IdentitySheetActions,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    when (sheet) {
        is IdentitySheet.Details -> DetailsSheet(sheet.identity, actions, topInsets, bottomInsets)
        is IdentitySheet.Create -> CreateSheet(sheet, actions, topInsets, bottomInsets)
        is IdentitySheet.Import -> ImportSheet(sheet.form, actions, topInsets, bottomInsets)
        is IdentitySheet.Export -> ExportSheet(sheet, actions, topInsets, bottomInsets)
        is IdentitySheet.Delete -> DeleteSheet(sheet, actions, topInsets, bottomInsets)
    }
}

/** One identity in full, with what can be done with it. */
@Composable
private fun DetailsSheet(
    identity: IdentityDetails,
    actions: IdentitySheetActions,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(identity.label, actions::closeSheet, topInsets, bottomInsets) {
        Fact(stringResource(R.string.identity_details_name), identity.commonName)
        Fact(stringResource(R.string.identity_details_fingerprint), identity.fingerprint, monospace = true)
        Fact(
            stringResource(R.string.identity_details_added),
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(identity.createdMs)),
        )
        Paragraph(
            if (identity.isDefault) {
                "${stringResource(R.string.identity_details_default)} ${usedOn(identity.serverCount)}"
            } else {
                usedOn(identity.serverCount)
            },
        )
        if (!identity.isDefault) {
            MumcordButton(
                text = stringResource(R.string.identity_set_default),
                onClick = { actions.setDefault(identity.id) },
                style = MumcordButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth().testTag(IDENTITY_SET_DEFAULT_TEST_TAG),
            )
        }
        MumcordButton(
            text = stringResource(R.string.identity_export),
            onClick = { actions.openExport(identity.id) },
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(IDENTITY_EXPORT_TEST_TAG),
        )
        if (identity.canDelete) {
            MumcordButton(
                text = stringResource(R.string.identity_delete),
                onClick = { actions.openDelete(identity.id) },
                style = MumcordButtonStyle.Danger,
                icon = MumcordIcons.Trash,
                modifier = Modifier.fillMaxWidth().testTag(IDENTITY_DELETE_TEST_TAG),
            )
        } else {
            // No button that does nothing: the reason stands where it would be.
            BasicText(
                text = stringResource(R.string.identity_delete_last),
                style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textMuted),
            )
        }
        MumcordButton(
            text = stringResource(R.string.action_close),
            onClick = actions::closeSheet,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(IDENTITY_DETAILS_CLOSE_TEST_TAG),
        )
    }
}

@Composable
private fun Fact(label: String, value: String, monospace: Boolean = false) {
    val colors = MumcordTheme.colors
    Column {
        BasicText(label, style = MumcordTheme.typography.smallStrong.copy(color = colors.textSecondary))
        // Selectable, so the fingerprint can be compared with the desktop app or copied.
        SelectionContainer {
            BasicText(
                text = value,
                style = MumcordTheme.typography.body.copy(
                    color = colors.text,
                    fontFamily = if (monospace) FontFamily.Monospace else MumcordTheme.typography.body.fontFamily,
                ),
            )
        }
    }
}

@Composable
private fun CreateSheet(
    sheet: IdentitySheet.Create,
    actions: IdentitySheetActions,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    val form = sheet.form
    SheetOverlay(stringResource(R.string.identity_create_title), actions::closeSheet, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.identity_create_text))
        MumcordTextField(
            value = form.name,
            onValueChange = actions::changeCreateName,
            label = stringResource(R.string.identity_create_name),
            error = form.error?.let { error ->
                stringResource(
                    if (error == ConnectionError.InvalidInput) R.string.identity_create_name_invalid else error.messageRes(),
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { actions.create() }),
        )
        MumcordButton(
            text = stringResource(R.string.identity_create_confirm),
            onClick = actions::create,
            enabled = sheet.canCreate,
            modifier = Modifier.fillMaxWidth().testTag(IDENTITY_CREATE_CONFIRM_TEST_TAG),
        )
        CancelButton(actions::closeSheet)
    }
}

@Composable
private fun ImportSheet(
    form: IdentitySheetLocal.Import,
    actions: IdentitySheetActions,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.identity_import_title), actions::closeSheet, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.identity_import_text))
        MumcordTextField(
            value = form.password,
            onValueChange = actions::changeImportPassword,
            label = stringResource(R.string.identity_import_password),
            error = form.problem?.let { problem ->
                stringResource(
                    when (problem) {
                        ImportProblem.Unreadable -> R.string.identities_file_unreadable
                        ImportProblem.WrongPasswordOrFile -> R.string.identity_import_wrong
                        ImportProblem.Duplicate -> R.string.error_duplicate_identity
                        ImportProblem.Failed -> R.string.identity_import_failed
                    },
                )
            },
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.confirmImport() }),
        )
        MumcordButton(
            text = stringResource(R.string.identity_import_confirm),
            onClick = actions::confirmImport,
            enabled = !form.saving,
            modifier = Modifier.fillMaxWidth().testTag(IDENTITY_IMPORT_CONFIRM_TEST_TAG),
        )
        CancelButton(actions::closeSheet)
    }
}

@Composable
private fun ExportSheet(
    sheet: IdentitySheet.Export,
    actions: IdentitySheetActions,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    val form = sheet.form
    SheetOverlay(
        stringResource(R.string.identity_export_title, sheet.identity.label),
        actions::closeSheet,
        topInsets,
        bottomInsets,
    ) {
        Paragraph(stringResource(R.string.identity_export_text))
        MumcordTextField(
            value = form.password,
            onValueChange = actions::changeExportPassword,
            label = stringResource(R.string.identity_export_password),
            error = if (form.failed) stringResource(R.string.identity_export_failed) else null,
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        MumcordTextField(
            value = form.repeat,
            onValueChange = actions::changeExportRepeat,
            label = stringResource(R.string.identity_export_repeat),
            error = if (form.mismatch) stringResource(R.string.identity_export_mismatch) else null,
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.export() }),
        )
        MumcordButton(
            text = stringResource(R.string.identity_export_confirm),
            onClick = actions::export,
            enabled = form.canExport,
            modifier = Modifier.fillMaxWidth().testTag(IDENTITY_EXPORT_CONFIRM_TEST_TAG),
        )
        CancelButton(actions::closeSheet)
    }
}

/** The warning before an identity is deleted, with the label typed as confirmation (Spec 03 FR-3.9). */
@Composable
private fun DeleteSheet(
    sheet: IdentitySheet.Delete,
    actions: IdentitySheetActions,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    val identity = sheet.identity
    val form = sheet.form
    SheetOverlay(
        stringResource(R.string.identity_delete_title, identity.label),
        actions::closeSheet,
        topInsets,
        bottomInsets,
    ) {
        MumcordBanner(
            text = stringResource(R.string.identity_delete_warning),
            icon = MumcordIcons.Alert,
            tone = MumcordBannerTone.Danger,
            horizontalMargin = 0.dp,
        )
        val nextDefault = identity.nextDefaultLabel
        if (nextDefault != null) {
            Paragraph(stringResource(R.string.identity_delete_next_default, nextDefault))
        }
        if (identity.serverCount > 0 && (nextDefault != null || !identity.isDefault)) {
            Paragraph(
                pluralStringResource(
                    R.plurals.identity_delete_servers,
                    identity.serverCount,
                    identity.serverCount,
                    nextDefault ?: sheet.defaultLabel,
                ),
            )
        }
        MumcordButton(
            text = stringResource(R.string.identity_delete_backup),
            onClick = { actions.openExport(identity.id) },
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(IDENTITY_DELETE_BACKUP_TEST_TAG),
        )
        MumcordTextField(
            value = form.typed,
            onValueChange = actions::changeDeleteConfirmation,
            label = stringResource(R.string.identity_delete_type),
            error = form.error?.let { stringResource(it.messageRes()) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        MumcordButton(
            text = stringResource(R.string.identity_delete),
            onClick = actions::delete,
            style = MumcordButtonStyle.Danger,
            enabled = sheet.canDelete,
            modifier = Modifier.fillMaxWidth().testTag(IDENTITY_DELETE_CONFIRM_TEST_TAG),
        )
        CancelButton(actions::closeSheet)
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit) {
    MumcordButton(
        text = stringResource(R.string.action_cancel),
        onClick = onClick,
        style = MumcordButtonStyle.Secondary,
        modifier = Modifier.fillMaxWidth(),
    )
}
