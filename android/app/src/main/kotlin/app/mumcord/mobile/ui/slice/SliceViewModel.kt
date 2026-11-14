// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.slice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mumcord.mobile.MumcordApplication
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user typed. Kept as text so a half-typed port is not lost. */
data class ConnectForm(
    val address: String = "",
    val port: String = DEFAULT_PORT,
    val username: String = "",
) {
    /** The port as a number, or null while the text is not a port between 1 and 65535. */
    val portNumber: UShort?
        get() = port.trim().toUShortOrNull()?.takeIf { it > 0u }

    val isValid: Boolean
        get() = address.isNotBlank() && username.isNotBlank() && portNumber != null

    private companion object {
        /** The standard Mumble port. */
        const val DEFAULT_PORT = "64738"
    }
}

data class SliceUiState(
    val form: ConnectForm = ConnectForm(),
    val session: SessionState = SessionState(),
) {
    val canConnect: Boolean
        get() = form.isValid && !session.isBusy
}

class SliceViewModel(private val repository: SessionRepository) : ViewModel() {
    private val form = MutableStateFlow(ConnectForm())

    val uiState: StateFlow<SliceUiState> =
        combine(form, repository.state, ::SliceUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SliceUiState())

    fun onAddressChange(address: String) = form.update { it.copy(address = address) }

    fun onPortChange(port: String) = form.update { it.copy(port = port) }

    fun onUsernameChange(username: String) = form.update { it.copy(username = username) }

    /** Also the retry action: it connects again with what is in the form. */
    fun connect() {
        val current = form.value
        val port = current.portNumber
        if (!current.isValid || port == null || repository.state.value.isBusy) return
        viewModelScope.launch {
            repository.connect(current.address.trim(), port, current.username.trim())
        }
    }

    fun answerCertificatePrompt(fingerprint: String, trust: Boolean) {
        viewModelScope.launch { repository.answerCertificatePrompt(fingerprint, trust) }
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }

    companion object {
        /** Keeps the state alive across a rotation without restarting the collection. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY]) as MumcordApplication
                SliceViewModel(application.sessionRepository)
            }
        }
    }
}
