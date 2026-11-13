// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Shows the version of the engine inside the app, e.g. "0.8.0 (6d2ac79e)".
struct VersionView: View {
    let version: String

    var body: some View {
        VStack(spacing: 8) {
            Text("Mumcord")
                .font(.largeTitle)
            Text(version)
                .font(.body.monospaced())
                .accessibilityLabel("Engine version \(version)")
                .accessibilityIdentifier("engine-version")
        }
        .padding()
    }
}
