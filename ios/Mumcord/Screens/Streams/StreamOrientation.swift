// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

/// The screen orientations the app allows right now: all of them, or landscape only while the
/// stream's full screen was turned to landscape with its button. The system asks the app's
/// delegate (`MumcordAppDelegate`) for them.
@MainActor
enum OrientationLock {
    private static var landscape = false
    /// Where the screen was turned before the landscape lock, to go back to afterwards.
    private static var before = UIInterfaceOrientation.portrait

    static var allowed: UIInterfaceOrientationMask {
        if landscape { return .landscape }
        return UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }

    /// Whether the app can turn the screen. Not on an iPad: it lets apps share the screen, so
    /// iOS ignores an app's wish for an orientation there.
    static var canTurn: Bool {
        UIDevice.current.userInterfaceIdiom != .pad
    }

    /// Turns the screen to landscape while `landscape`, and lets it follow the phone again
    /// afterwards (see `orientationAfterLandscape`).
    static func set(landscape: Bool) {
        guard landscape != self.landscape else { return }
        self.landscape = landscape
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            if landscape { before = scene.interfaceOrientation }
            for window in scene.windows {
                window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
            let wanted: UIInterfaceOrientationMask? = if landscape {
                .landscapeRight
            } else {
                orientationAfterLandscape(phone: UIDevice.current.orientation, before: before)
            }
            if let wanted {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: wanted)) { error in
                    AppLog.write("turning the screen failed: \(type(of: error))")
                }
            }
        }
    }
}

/// Where the screen turns when the landscape lock ends: nil while the phone is held sideways
/// (iOS then follows it by itself), portrait while it is held upright, and otherwise (lying
/// flat, or not known) back to where the screen was before the lock.
func orientationAfterLandscape(phone: UIDeviceOrientation, before: UIInterfaceOrientation) -> UIInterfaceOrientationMask? {
    if phone.isLandscape { return nil }
    if phone == .portrait { return .portrait }
    return switch before {
    case .landscapeLeft: .landscapeLeft
    case .landscapeRight: .landscapeRight
    default: .portrait
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
