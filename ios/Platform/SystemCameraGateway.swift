// SPDX-License-Identifier: AGPL-3.0-only

import AVFoundation

/// The camera permission of iOS. iOS asks once; after a "no" only the system settings help,
/// which `authorizationStatus` says by itself, so nothing needs to be remembered here.
final class SystemCameraGateway: CameraGateway {
    func permission() -> CameraPermission {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: .granted
        case .notDetermined: .undetermined
        default: .denied
        }
    }

    func request(_ completion: @escaping @Sendable (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
    }
}
