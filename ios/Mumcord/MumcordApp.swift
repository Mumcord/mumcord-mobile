// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

@main
struct MumcordApp: App {
    // Read once at start, so no view body ever calls into the engine.
    private let version = engineVersion()

    init() {
        MumcordFonts.register()
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            // Debug builds can be started on a screen of the design system (see DesignDebugLaunch).
            if let launch = DesignDebugLaunch.fromLaunchArguments() {
                DesignDebugRoot(launch: launch)
            } else {
                VersionView(version: version)
            }
            #else
            VersionView(version: version)
            #endif
        }
    }
}
