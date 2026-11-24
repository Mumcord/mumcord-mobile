// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

enum CameraFacing: CaseIterable, Equatable {
    case front
    case back
}

/// Why the camera could not run.
enum CameraTrouble: Equatable {
    /// The phone has no camera that can be used.
    case noCamera
    /// The camera could not be opened or stopped working.
    case failed
}

/// Where the pictures of the shared camera come from: the phone's camera (`AVCameraSource`) or,
/// in the tests, a drawn picture. Every call is made on the main thread. The camera runs only
/// between `start` and `stop`; the caller makes sure that is only while the app is on screen
/// and shows that the camera is on.
@MainActor
protocol CameraSource: AnyObject {
    /// The cameras this phone has.
    var facings: Set<CameraFacing> { get }

    /// Starts the camera facing `facing`, or switches to it while it runs; its pictures go to
    /// the engine. Without that camera the other one is used.
    func start(_ facing: CameraFacing)

    /// Stops the camera and releases it, so the system's camera indicator goes off.
    func stop()

    /// The camera's picture as the user sees it (the front camera mirrored).
    func preview() -> AnyView
}
