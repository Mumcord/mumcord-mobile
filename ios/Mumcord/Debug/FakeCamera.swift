// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import SwiftUI

/// The camera permission without the system: `answer` is what the system's question would say.
final class FakeCameraGateway: CameraGateway {
    var current: CameraPermission
    var answer: Bool
    private(set) var requests = 0

    init(permission: CameraPermission = .granted, answer: Bool = true) {
        current = permission
        self.answer = answer
    }

    func permission() -> CameraPermission {
        current
    }

    func request(_ completion: @escaping @Sendable (Bool) -> Void) {
        requests += 1
        // As iOS: after one answer the system does not ask again.
        current = answer ? .granted : .denied
        completion(answer)
    }
}

/// Hands the scan screen scripted QR texts instead of a camera picture, the way the camera sees
/// the codes the desktop shows one after the other: over and over, while the screen scans.
struct ScriptedQrSource: QrSource {
    let texts: [String]
    var interval: Duration = .milliseconds(300)

    @MainActor
    func viewfinder(onText: @escaping @MainActor (String) -> Void, onFailure: @escaping @MainActor () -> Void) -> AnyView {
        AnyView(ScriptedFeed(texts: texts, interval: interval, onText: onText))
    }
}

private struct ScriptedFeed: View {
    let texts: [String]
    let interval: Duration
    let onText: @MainActor (String) -> Void

    var body: some View {
        Color.clear.task { await feed() }
    }

    @MainActor
    private func feed() async {
        guard !texts.isEmpty else { return }
        while !Task.isCancelled {
            for text in texts {
                try? await Task.sleep(for: interval)
                if Task.isCancelled { return }
                onText(text)
            }
        }
    }
}
#endif
