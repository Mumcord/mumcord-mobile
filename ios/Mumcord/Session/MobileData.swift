// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The voice bitrate of "Reduced voice quality" on mobile data, in kbit/s (FR-5.26).
let reducedVoiceKbps: UInt32 = 24

/// The engine's reconnect attempt from which the app gets back in by itself; see `reconnectsNow`.
let reconnectNowFromAttempt = 3

/// "Load images and GIFs" (FR-5.41): on a network that is not metered they are always loaded,
/// on a metered one as the switch says. On by default, also while the settings are unread.
func loadMedia(_ network: NetworkStatus, _ settings: MobileSettings?) -> Bool {
    !network.metered || settings?.mobileDataLoadMedia != false
}

/// "Start streams automatically" (FR-5.41): on a network that is not metered streams start by
/// themselves, on a metered one only with the switch on; off by default. The screens for
/// streams ask this before they start one.
func autoplayStreams(_ network: NetworkStatus, _ settings: MobileSettings?) -> Bool {
    !network.metered || settings?.mobileDataAutoplayStreams == true
}

/// "Reduced voice quality" (FR-5.26, FR-5.41): the limit for the voice bitrate in kbit/s while
/// the network is metered and the switch is on, nil (no limit) otherwise.
func bitrateLimitKbps(_ network: NetworkStatus, _ settings: MobileSettings?) -> UInt32? {
    network.metered && settings?.mobileDataReducedVoice == true ? reducedVoiceKbps : nil
}

/// Whether the app gets back into the server itself, now (FR-5.40).
///
/// The engine notices a lost connection and reconnects by itself, at first within seconds.
/// After the third attempt it waits up to half a minute between tries, also when a network has
/// come back meanwhile. So when a working network appears (`previous` had none, or another one)
/// while the engine is that far, the app does not wait for it. Earlier attempts are left to the
/// engine, and nothing is started while one such reconnect is `running` already.
func reconnectsNow(
    previous: NetworkStatus,
    current: NetworkStatus,
    phase: ConnectionPhase,
    running: Bool
) -> Bool {
    guard !running, current.usable, !previous.usable || previous.id != current.id else { return false }
    guard case .reconnecting(let progress) = phase else { return false }
    return progress.attempt >= reconnectNowFromAttempt
}

/// What the phone's network means for the session: the voice bitrate limit on mobile data and
/// getting back in when a network returns. It follows the network and the session by itself
/// once it has been created, and lives as long as the process; the system reports network
/// changes, nothing here runs on a timer.
@MainActor
final class NetworkSession {
    private let store: SessionStore
    private var network: NetworkStatus
    private var lastLimitInputs: LimitInputs?
    private var reconnecting = false

    /// What the limit depends on. The engine forgets the limit when it starts, so it is set
    /// again with every connection, and whenever the network or the switch changes.
    private struct LimitInputs: Equatable {
        let network: NetworkStatus
        let settings: MobileSettings?
        let serversLoaded: Bool
        let phase: Int
    }

    init(store: SessionStore, monitor: NetworkMonitor) {
        self.store = store
        network = monitor.status
        let statuses = monitor.statuses()
        Task { [weak self] in
            for await status in statuses {
                guard let self else { return }
                self.follow(network: status)
            }
        }
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                guard let self else { return }
                self.applyLimit(state)
            }
        }
    }

    private func follow(network next: NetworkStatus) {
        let previous = network
        network = next
        applyLimit(store.state)
        guard reconnectsNow(previous: previous, current: next, phase: store.state.phase, running: reconnecting) else {
            return
        }
        reconnecting = true
        Task { [weak self, store] in
            await store.reconnectNow()
            self?.reconnecting = false
        }
    }

    private func applyLimit(_ state: SessionState) {
        let inputs = LimitInputs(
            network: network,
            settings: state.mobileSettings,
            serversLoaded: state.serversLoaded,
            phase: state.phase.kind
        )
        guard inputs != lastLimitInputs else { return }
        lastLimitInputs = inputs
        store.setBitrateLimit(bitrateLimitKbps(network, state.mobileSettings))
    }
}

private extension ConnectionPhase {
    /// The phase without its details: a new reconnect attempt is no new phase.
    var kind: Int {
        switch self {
        case .idle: 0
        case .connecting: 1
        case .connected: 2
        case .reconnecting: 3
        case .failed: 4
        }
    }
}
