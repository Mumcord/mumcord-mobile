// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.slice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.mumcord.mobile.R
import app.mumcord.mobile.session.CertificatePromptState

const val FINGERPRINT_TEST_TAG = "certificate-fingerprint"

/**
 * Asks whether to trust the server's certificate. It cannot be dismissed by tapping outside or
 * with the back gesture: trusting or not is a security decision the user has to make on purpose.
 */
@Composable
fun CertificatePromptDialog(
    prompt: CertificatePromptState,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    val previousFingerprint = prompt.previousFingerprint
    val properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    if (previousFingerprint == null) {
        AlertDialog(
            onDismissRequest = {},
            properties = properties,
            title = { Text(stringResource(R.string.certificate_unknown_title)) },
            text = {
                PromptBody(message = stringResource(R.string.certificate_unknown_message)) {
                    Fingerprint(
                        label = stringResource(R.string.certificate_fingerprint_label),
                        fingerprint = prompt.fingerprint,
                    )
                }
            },
            confirmButton = {
                Button(onClick = onTrust) {
                    Text(stringResource(R.string.certificate_unknown_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.certificate_unknown_reject))
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = {},
            properties = properties,
            title = {
                Text(
                    text = stringResource(R.string.certificate_changed_title),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                PromptBody(message = stringResource(R.string.certificate_changed_message)) {
                    Fingerprint(
                        label = stringResource(R.string.certificate_changed_previous_label),
                        fingerprint = previousFingerprint,
                    )
                    Fingerprint(
                        label = stringResource(R.string.certificate_changed_new_label),
                        fingerprint = prompt.fingerprint,
                    )
                }
            },
            // Two buttons of equal weight: a changed certificate has no suggested answer.
            confirmButton = {
                OutlinedButton(onClick = onTrust) {
                    Text(stringResource(R.string.certificate_changed_trust))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onReject) {
                    Text(stringResource(R.string.certificate_changed_reject))
                }
            },
        )
    }
}

@Composable
private fun PromptBody(message: String, fingerprints: @Composable () -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = message)
        fingerprints()
    }
}

@Composable
private fun Fingerprint(label: String, fingerprint: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = fingerprint,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag(FINGERPRINT_TEST_TAG),
        )
    }
}
