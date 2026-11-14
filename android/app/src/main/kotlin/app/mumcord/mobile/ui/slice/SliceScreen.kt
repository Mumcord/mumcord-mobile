// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.slice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Connects the screens to the view model; everything below it only gets values and callbacks. */
@Composable
fun SliceRoute(viewModel: SliceViewModel, engineVersion: String) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SliceScreen(
        uiState = uiState,
        engineVersion = engineVersion,
        onAddressChange = viewModel::onAddressChange,
        onPortChange = viewModel::onPortChange,
        onUsernameChange = viewModel::onUsernameChange,
        onConnect = viewModel::connect,
        onAnswerCertificatePrompt = viewModel::answerCertificatePrompt,
        onDisconnect = viewModel::disconnect,
    )
}

@Composable
fun SliceScreen(
    uiState: SliceUiState,
    engineVersion: String,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onConnect: () -> Unit,
    onAnswerCertificatePrompt: (fingerprint: String, trust: Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val session = uiState.session
    val server = session.server
    if (server != null && session.showsServer) {
        ChannelTreeScreen(rows = server.rows, phase = session.phase, onDisconnect = onDisconnect)
    } else {
        ConnectFormScreen(
            form = uiState.form,
            phase = session.phase,
            canConnect = uiState.canConnect,
            engineVersion = engineVersion,
            onAddressChange = onAddressChange,
            onPortChange = onPortChange,
            onUsernameChange = onUsernameChange,
            onConnect = onConnect,
            onCancel = onDisconnect,
        )
    }
    session.certificatePrompt?.let { prompt ->
        CertificatePromptDialog(
            prompt = prompt,
            onTrust = { onAnswerCertificatePrompt(prompt.fingerprint, true) },
            onReject = { onAnswerCertificatePrompt(prompt.fingerprint, false) },
        )
    }
}
