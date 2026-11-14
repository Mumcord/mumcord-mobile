// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay

const val SWITCH_CONFIRM_TEST_TAG = "switch-confirm"
const val PASSWORD_CONNECT_TEST_TAG = "password-connect"

/** One server at a time: asks before the current connection is ended for another server. */
@Composable
fun ConfirmSwitchSheet(
    currentName: String,
    targetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.switch_title), onDismiss, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.switch_text, currentName, targetName))
        MumcordButton(
            text = stringResource(R.string.switch_confirm),
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().testTag(SWITCH_CONFIRM_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** After a wrong or missing server password: type it and connect again. */
@Composable
fun PasswordSheet(
    serverName: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.password_title), onDismiss, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.password_text, serverName))
        MumcordTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.password_label),
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onConnect() }),
        )
        MumcordButton(
            text = stringResource(R.string.servers_connect),
            onClick = onConnect,
            enabled = password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag(PASSWORD_CONNECT_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
