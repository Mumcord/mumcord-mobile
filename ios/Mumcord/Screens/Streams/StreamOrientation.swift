// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

/// The screen orientations the app allows right now: all of them, or landscape only while the
/// stream's full screen was turned to landscape with its button. The system asks the app's
/// delegate (`MumcordAppDelegate`) for them.
@MainActor
enum OrientationLock {
    private static var landscape = false

    static var allowed: UIInterfaceOrientationMask {
        if landscape { return .landscape }
        return UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }

    /// Turns the screen to landscape while `landscape`, and lets it follow the phone again
    /// afterwards: back to portrait unless the phone is held sideways.
    static func set(landscape: Bool) {
        guard landscape != self.landscape else { return }
        self.landscape = landscape
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            for window in scene.windows {
                window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
            let wanted: UIInterfaceOrientationMask? = if landscape {
                .landscapeRight
            } else if !UIDevice.current.orientation.isLandscape {
                .portrait
            } else {
                nil
            }
            if let wanted {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: wanted)) { error in
                    AppLog.write("turning the screen failed: \(type(of: error))")
                }
            }
        }
    }
}

/// Asked by the system which orientations the app allows.
final class MumcordAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLock.allowed
    }
}

extension View {
    /// Turns the screen to landscape while `landscape` and while this view is shown.
    func streamOrientation(landscape: Bool) -> some View {
        onChange(of: landscape, initial: true) { _, landscape in
            OrientationLock.set(landscape: landscape)
        }
        .onDisappear { OrientationLock.set(landscape: false) }
    }
}
