// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Where the scan screen gets the texts of QR codes from: the camera of the phone, or scripted
/// texts in debug builds, because the simulator has no camera to show a code to.
protocol QrSource {
    /// The picture shown while scanning. It hands every QR text it reads to `onText`, the same
    /// code again and again while it is in view, and calls `onFailure` when it cannot run.
    @MainActor
    func viewfinder(onText: @escaping @MainActor (String) -> Void, onFailure: @escaping @MainActor () -> Void) -> AnyView
}

/// A phone without a scanner: the default where nothing scans.
struct NoQrSource: QrSource {
    @MainActor
    func viewfinder(onText: @escaping @MainActor (String) -> Void, onFailure: @escaping @MainActor () -> Void) -> AnyView {
        AnyView(Color.clear.onAppear { onFailure() })
    }
}
