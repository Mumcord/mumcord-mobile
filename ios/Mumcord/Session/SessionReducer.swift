// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

extension SessionState {
    /// Folds one engine event into the state. Pure, so it can run on any thread and be tested.
    func fold(_ event: BridgeEvent) -> SessionState {
        var next = self
        switch event {
        case .connectionChanged(let connection):
            switch connection {
            // The end of a connection also ends its certificate question and forgets its channels.
            case .disconnected(let error):
                next.phase = error.map { ConnectionPhase.failed($0.connectionError) } ?? .idle
                next.certificatePrompt = nil
                next.server = nil
                next.activeServerId = error == nil ? nil : activeServerId
                next.channelPassword = nil
                next.denied = nil
                next.hints = []
            case .connecting:
                next.phase = .connecting
            case .connected:
                next.phase = .connected
                next.certificatePrompt = nil
            // The last known channels stay visible while the engine tries to get back in.
            case .reconnecting(let attempt, let maxAttempts, _):
                next.phase = .reconnecting(Reconnecting(attempt: Int(attempt), maxAttempts: Int(maxAttempts)))
                next.certificatePrompt = nil
            }
        case .certificatePromptRequired(let prompt):
            next.certificatePrompt = CertificatePromptState(
                fingerprint: prompt.fingerprint,
                previousFingerprint: prompt.previousFingerprint
            )
        case .serverSynced(let server):
            next.server = ServerModel(
                selfSession: server.selfSession,
                channels: Dictionary(server.channels.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last }),
                people: Dictionary(server.people.map { ($0.session, $0) }, uniquingKeysWith: { _, last in last })
            )
        case .channelsChanged(let channels):
            if let server {
                var all = server.channels
                for channel in channels { all[channel.id] = channel }
                next.server = server.with(channels: all)
            }
        case .channelRemoved(let id):
            if let server {
                var all = server.channels
                all[id] = nil
                next.server = server.with(channels: all)
            }
        case .personChanged(let person):
            if let server {
                var all = server.people
                all[person.session] = person
                next.server = server.with(people: all)
            }
        case .personRemoved(let session):
            if let server {
                var all = server.people
                all[session] = nil
                next.server = server.with(people: all)
            }
        case .serversChanged(let servers):
            next.servers = servers
            next.serversLoaded = true
        case .channelPasswordChanged(let channelId, let state):
            switch state {
            case .required: next.channelPassword = ChannelPasswordPrompt(channelId: channelId, wrong: false)
            case .wrong: next.channelPassword = ChannelPasswordPrompt(channelId: channelId, wrong: true)
            case .ended: next.channelPassword = nil
            }
        case .actionDenied(let channelId, let detail):
            next.denied = DeniedAction(channelId: channelId, detail: detail)
        case .audioWantedChanged(let audio):
            next.audio = audio
        // Up to 20 times a second and only for the level meter: it has its own path in the
        // store, so that it does not make every screen compare its state.
        case .inputLevelChanged:
            break
        // Messages and unread counts go to `ChatStore` and never reach this state. The rest
        // belongs to the Messages tab.
        case .chatMessageReceived, .chatUnreadChanged, .chatNotification, .messageRequestsChanged,
             .blockedPeopleChanged:
            break
        }
        return next
    }
}
