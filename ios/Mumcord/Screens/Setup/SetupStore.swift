// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Remembers that the first-start setup is done. The engine cannot say so itself: its talk mode
/// is voice activity until one is chosen, which looks the same as having chosen voice activity.
protocol SetupStore: AnyObject {
    var firstStartDone: Bool { get }

    func markFirstStartDone()
}

final class UserDefaultsSetupStore: SetupStore {
    private static let keyDone = "setup.first_start_done"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var firstStartDone: Bool { defaults.bool(forKey: Self.keyDone) }

    func markFirstStartDone() {
        defaults.set(true, forKey: Self.keyDone)
    }
}
