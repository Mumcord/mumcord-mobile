// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

@main
struct MumcordApp: App {
    // Read once at start, so no view body ever calls into the engine.
    private let version = engineVersion()

    var body: some Scene {
        WindowGroup {
            VersionView(version: version)
        }
    }
}
