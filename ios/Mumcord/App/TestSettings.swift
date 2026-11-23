// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// What a test can change about the app on the real engine. Release builds have none of it.
enum TestSettings {
    /// A ban also covers the address, which every person of the local test server shares (the
    /// host): when set, a ban lasts this many seconds instead of the chosen length, so an
    /// end-to-end test can wait for its end. Debug builds read it from the launch argument
    /// `-mumcordBanSeconds <seconds>`.
    static let banSeconds: UInt32? = {
        #if DEBUG
        banSeconds(UserDefaults.standard)
        #else
        nil
        #endif
    }()

    #if DEBUG
    /// Launch arguments of the form `-key value` are readable as user defaults.
    static func banSeconds(_ defaults: UserDefaults) -> UInt32? {
        let seconds = defaults.integer(forKey: "mumcordBanSeconds")
        return seconds > 0 ? UInt32(exactly: seconds) : nil
    }
    #endif
}
