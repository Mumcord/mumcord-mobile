// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

extension Color {
    /// A colour of design/tokens.json as `0xRRGGBBAA` in sRGB.
    init(rgba: UInt32) {
        self.init(
            .sRGB,
            red: Double((rgba >> 24) & 0xff) / 255,
            green: Double((rgba >> 16) & 0xff) / 255,
            blue: Double((rgba >> 8) & 0xff) / 255,
            opacity: Double(rgba & 0xff) / 255
        )
    }
}

/// A CSS box shadow from design/tokens.json: vertical offset, blur radius, spread and colour.
struct ShadowToken {
    let y: CGFloat
    let blur: CGFloat
    let spread: CGFloat
    let color: Color
}

/// A text style from design/tokens.json. `size` is in points that follow Dynamic Type;
/// `lineHeight` is a multiple of the size and `letterSpacing` is in em.
struct TextToken: Equatable {
    let display: Bool
    let size: CGFloat
    let weight: CGFloat
    let lineHeight: CGFloat
    let letterSpacing: CGFloat

    func with(size: CGFloat? = nil, weight: CGFloat? = nil, lineHeight: CGFloat? = nil) -> TextToken {
        TextToken(
            display: display,
            size: size ?? self.size,
            weight: weight ?? self.weight,
            lineHeight: lineHeight ?? self.lineHeight,
            letterSpacing: letterSpacing
        )
    }
}

/// A font family from design/tokens.json: the file in the app bundle (without `.ttf`) and the
/// variable font axes that are fixed for every text style.
struct FontToken {
    let family: String
    let file: String
    let axes: [String: CGFloat]
}
