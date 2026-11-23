// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// What iOS says about the camera permission.
enum CameraPermission: Equatable {
    /// The system has not asked yet; it asks once.
    case undetermined
    case granted
    /// The user said no to the system, or the phone does not allow the camera. iOS does not
    /// ask again: only the system settings help.
    case denied
}

/// What the camera question needs from the system. Everything platform-specific stays behind
/// it, so the flow can be tested without a device.
protocol CameraGateway: AnyObject {
    func permission() -> CameraPermission
    /// Shows the system's question; the answer arrives on a thread of the system.
    func request(_ completion: @escaping @Sendable (Bool) -> Void)
}

/// What a screen that wants the camera shows before it may use it; nil when it may.
func cameraNotice(_ permission: CameraPermission) -> CameraNotice? {
    switch permission {
    case .granted: nil
    case .undetermined: .explain
    case .denied: .blocked
    }
}

/// A phone whose camera is never asked for: the default where nothing scans.
final class NoCamera: CameraGateway {
    func permission() -> CameraPermission { .denied }
    func request(_ completion: @escaping @Sendable (Bool) -> Void) { completion(false) }
}
