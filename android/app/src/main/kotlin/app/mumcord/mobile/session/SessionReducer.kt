// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ConnectionState

/** Folds one engine event into the state. Pure, so it can run on any thread and be tested. */
fun SessionState.fold(event: BridgeEvent): SessionState = when (event) {
    is BridgeEvent.ConnectionChanged -> when (val connection = event.state) {
        // The end of a connection also ends its certificate question and forgets its server.
        is ConnectionState.Disconnected -> SessionState(
            phase = connection.error
                ?.let { ConnectionPhase.Failed(it.toConnectionError()) }
                ?: ConnectionPhase.Idle,
        )
        ConnectionState.Connecting -> copy(phase = ConnectionPhase.Connecting)
        ConnectionState.Connected ->
            copy(phase = ConnectionPhase.Connected, certificatePrompt = null)
        // The last known channels stay visible while the engine tries to get back in.
        is ConnectionState.Reconnecting -> copy(
            phase = ConnectionPhase.Reconnecting(
                attempt = connection.attempt.toInt(),
                maxAttempts = connection.maxAttempts.toInt(),
            ),
            certificatePrompt = null,
        )
    }
    is BridgeEvent.CertificatePromptRequired -> copy(
        certificatePrompt = CertificatePromptState(
            fingerprint = event.prompt.fingerprint,
            previousFingerprint = event.prompt.previousFingerprint,
        ),
    )
    is BridgeEvent.ServerSynced -> copy(
        server = ServerModel(
            selfSession = event.server.selfSession,
            channels = event.server.channels.associateBy { it.id },
            people = event.server.people.associateBy { it.session },
        ),
    )
    is BridgeEvent.ChannelsChanged -> copy(
        server = server?.let { it.copy(channels = it.channels + event.channels.associateBy { c -> c.id }) },
    )
    is BridgeEvent.ChannelRemoved -> copy(
        server = server?.let { it.copy(channels = it.channels - event.id) },
    )
    is BridgeEvent.PersonChanged -> copy(
        server = server?.let { it.copy(people = it.people + (event.person.session to event.person)) },
    )
    is BridgeEvent.PersonRemoved -> copy(
        server = server?.let { it.copy(people = it.people - event.session) },
    )
    // No UI for these yet: the server list (task 2.4) and channel passwords and denials (task 2.5) use them.
    is BridgeEvent.ActionDenied,
    is BridgeEvent.ChannelPasswordChanged,
    is BridgeEvent.ServersChanged,
    -> this
}
