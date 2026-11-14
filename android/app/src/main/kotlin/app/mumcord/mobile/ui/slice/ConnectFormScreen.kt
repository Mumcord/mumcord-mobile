// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.slice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.ConnectionPhase

@Composable
fun ConnectFormScreen(
    form: ConnectForm,
    phase: ConnectionPhase,
    canConnect: Boolean,
    engineVersion: String,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = phase != ConnectionPhase.Idle && phase !is ConnectionPhase.Failed
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.connect_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = form.address,
                onValueChange = onAddressChange,
                label = { Text(stringResource(R.string.connect_address_label)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            val portInvalid = form.port.isNotEmpty() && form.portNumber == null
            OutlinedTextField(
                value = form.port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.connect_port_label)) },
                singleLine = true,
                enabled = !busy,
                isError = portInvalid,
                supportingText = if (portInvalid) {
                    { Text(stringResource(R.string.connect_port_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.connect_username_label)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (phase is ConnectionPhase.Failed) {
                ConnectionErrorCard(error = phase.error, canRetry = canConnect, onRetry = onConnect)
            }
            if (busy) {
                ConnectingRow(onCancel = onCancel)
            } else {
                Button(
                    onClick = onConnect,
                    enabled = canConnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.connect_action))
                }
            }
            Text(
                text = stringResource(R.string.engine_version, engineVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionErrorCard(error: ConnectionError, canRetry: Boolean, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        // Announced by the screen reader when it appears: nothing else tells that connecting failed.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.error_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(error.messageRes()))
            OutlinedButton(onClick = onRetry, enabled = canRetry) {
                Text(stringResource(R.string.error_retry))
            }
        }
    }
}

@Composable
private fun ConnectingRow(onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(R.string.connect_in_progress),
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.connect_cancel))
        }
    }
}
