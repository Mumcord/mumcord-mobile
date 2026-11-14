// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.connect.messageRes

const val FORM_SAVE_TEST_TAG = "server-form-save"

/** Adds a server to the list or changes one: address, port, name, user name, server password. */
@Composable
fun ServerFormScreen(
    form: ServerFormState,
    onChange: ((ServerFormState) -> ServerFormState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    val next = KeyboardOptions(imeAction = ImeAction.Next)
    MumcordScreen {
        MumcordAppBar(
            title = stringResource(if (form.isEdit) R.string.form_edit_title else R.string.form_add_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.action_back),
            windowInsets = topInsets,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s4),
            modifier = Modifier
                .weight(1f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(bottomInsets)
                .padding(start = MumcordSpace.s4, top = MumcordSpace.s2, end = MumcordSpace.s4, bottom = MumcordSpace.s4),
        ) {
            MumcordTextField(
                value = form.address,
                onValueChange = { text -> onChange { it.copy(address = text) } },
                label = stringResource(R.string.form_address_label),
                placeholder = stringResource(R.string.form_address_placeholder),
                error = stringResource(R.string.form_address_missing).takeIf { form.showErrors && form.addressMissing },
                keyboardOptions = next.copy(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            MumcordTextField(
                value = form.port,
                onValueChange = { text -> onChange { it.copy(port = text) } },
                label = stringResource(R.string.form_port_label),
                error = stringResource(R.string.form_port_invalid).takeIf { form.showErrors && form.portNumber == null },
                keyboardOptions = next.copy(keyboardType = KeyboardType.Number),
            )
            MumcordTextField(
                value = form.name,
                onValueChange = { text -> onChange { it.copy(name = text) } },
                label = stringResource(R.string.form_name_label),
                hint = stringResource(R.string.form_name_hint),
                keyboardOptions = next.copy(capitalization = KeyboardCapitalization.Words),
            )
            MumcordTextField(
                value = form.username,
                onValueChange = { text -> onChange { it.copy(username = text) } },
                label = stringResource(R.string.form_username_label),
                hint = stringResource(R.string.form_username_hint),
                error = stringResource(R.string.form_username_missing).takeIf { form.showErrors && form.usernameMissing },
                keyboardOptions = next.copy(autoCorrectEnabled = false),
            )
            MumcordTextField(
                value = form.password,
                onValueChange = { text -> onChange { it.copy(password = text) } },
                label = stringResource(R.string.form_password_label),
                hint = stringResource(
                    when {
                        form.removeSavedPassword -> R.string.form_password_will_be_removed
                        form.hasSavedPassword -> R.string.form_password_saved
                        else -> R.string.form_password_hint
                    },
                ),
                password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
            if (form.hasSavedPassword && form.password.isEmpty()) {
                MumcordButton(
                    text = stringResource(
                        if (form.removeSavedPassword) R.string.form_password_keep else R.string.form_password_remove,
                    ),
                    onClick = { onChange { it.copy(removeSavedPassword = !it.removeSavedPassword) } },
                    style = MumcordButtonStyle.Ghost,
                    modifier = Modifier.align(Alignment.Start),
                )
            }
            if (form.saveError != null) {
                MumcordBanner(
                    title = stringResource(R.string.form_save_failed),
                    text = stringResource(form.saveError.messageRes()),
                    icon = MumcordIcons.Alert,
                    tone = MumcordBannerTone.Danger,
                    horizontalMargin = 0.dp,
                )
            }
            MumcordButton(
                text = stringResource(R.string.form_save),
                onClick = onSave,
                enabled = !form.saving,
                modifier = Modifier.fillMaxWidth().testTag(FORM_SAVE_TEST_TAG),
            )
        }
    }
}
