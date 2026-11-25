// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import SwiftUI

/// Gives the app only `width` points of the screen while the iPad stands upright, as a narrow
/// Split View window does, and the whole screen in landscape (`-mumcordSplitWidth`). The UI
/// tests cannot resize the app's window on the simulator; turning it with this in place resizes
/// the window between one column and two, as dragging the Split View divider does. Without a
/// width it changes nothing.
struct DebugSplitWidth: ViewModifier {
    let width: CGFloat?
    /// Measured on the whole window: the keyboard takes away height, not the upright stand.
    @State private var portrait = true

    func body(content: Content) -> some View {
        if let width {
            content
                .frame(width: portrait ? width : nil)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    GeometryReader { window in
                        Color.clear.onChange(of: window.size.height > window.size.width, initial: true) { _, upright in
                            portrait = upright
                        }
                    }
                    .ignoresSafeArea()
                }
        } else {
            content
        }
    }
}
#endif
