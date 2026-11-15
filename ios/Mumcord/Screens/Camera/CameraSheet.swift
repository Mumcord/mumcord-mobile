// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Says in one sentence (`reason`) why the camera is needed, before the system asks for it.
/// Once the system no longer asks (`blocked`), it points to the system settings instead.
struct CameraSheet: View {
    let reason: String
    let blocked: Bool
    let onContinue: () -> Void
    let onOpenSettings: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.cameraTitle, onDismiss: onDismiss) {
            MumcordBanner(text: blocked ? "\(reason) \(Strings.cameraBlockedText)" : reason, horizontalMargin: 0)
            MumcordButton(
                text: blocked ? Strings.cameraOpenSettings : Strings.cameraContinue,
                fullWidth: true,
                action: blocked ? onOpenSettings : onContinue
            )
            .accessibilityIdentifier("camera-continue")
            MumcordButton(text: Strings.cameraNotNow, style: .secondary, fullWidth: true, action: onDismiss)
                .accessibilityIdentifier("camera-not-now")
        }
    }
}

/// Lets the chat's "Take photo" reach the camera permission without a path through every view.
private struct CameraGatewayKey: EnvironmentKey {
    static let defaultValue: CameraGateway = NoCamera()
}

extension EnvironmentValues {
    var cameraGateway: CameraGateway {
        get { self[CameraGatewayKey.self] }
        set { self[CameraGatewayKey.self] = newValue }
    }
}
