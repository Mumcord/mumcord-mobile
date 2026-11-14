// SPDX-License-Identifier: AGPL-3.0-only

import AVFAudio
import Foundation

/// The microphone permission of iOS and what the user answered to the app's own explanation.
final class SystemMicrophoneGateway: MicrophoneGateway {
    private static let declinedKey = "microphone.declined"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func permission() -> MicrophonePermission {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: .granted
        case .denied: .denied
        default: .undetermined
        }
    }

    var declined: Bool {
        get { defaults.bool(forKey: Self.declinedKey) }
        set { defaults.set(newValue, forKey: Self.declinedKey) }
    }

    func request(_ completion: @escaping @Sendable (Bool) -> Void) {
        AVAudioApplication.requestRecordPermission(completionHandler: completion)
    }
}
