// SPDX-License-Identifier: AGPL-3.0-only

import os
import SwiftUI
import UIKit

@main
struct MumcordApp: App {
    // Decided once at start: the engine must exist only once per process.
    private let model: AppModel?
    private let theme: MumcordThemeMode
    #if DEBUG
    private let designLaunch: DesignDebugLaunch?
    #endif

    init() {
        MumcordFonts.register()
        #if DEBUG
        // Debug builds can be started on a screen of the design system (see DesignDebugLaunch)
        // or against the fake engine (see AppDebugLaunch).
        designLaunch = DesignDebugLaunch.fromLaunchArguments()
        if designLaunch != nil {
            model = nil
            theme = .system
        } else if let launch = AppDebugLaunch.fromLaunchArguments() {
            model = launch.makeModel()
            theme = launch.theme
        } else {
            model = .live
            theme = .system
        }
        #else
        model = .live
        theme = .system
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if let designLaunch {
                DesignDebugRoot(launch: designLaunch)
            } else if let model {
                AppRoot(model: model, theme: theme)
            }
            #else
            if let model {
                AppRoot(model: model, theme: theme)
            }
            #endif
        }
    }
}

extension AppModel {
    /// The app on the real engine. Created once per process: the engine must exist only once.
    static let live: AppModel = {
        let store = SessionStore(engineFactory: BridgeEngineFactory.make, log: AppLog.write)
        return AppModel(
            store: store,
            setupStore: UserDefaultsSetupStore(),
            microphone: VoiceAudio(
                store: store,
                gateway: SystemMicrophoneGateway(),
                backend: NativeAudio(),
                session: SystemVoiceSession(log: AppLog.write),
                scheduler: QueueAudioScheduler(),
                log: AppLog.write
            ),
            localNetwork: SystemLocalNetworkGateway(log: AppLog.write),
            openSettings: {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        )
    }()
}

/// The app's technical log. Messages never contain names, addresses or other personal data.
enum AppLog {
    private static let logger = Logger(subsystem: "app.mumcord.mobile", category: "session")

    static let write: @Sendable (String) -> Void = { message in
        logger.error("\(message, privacy: .public)")
    }
}
