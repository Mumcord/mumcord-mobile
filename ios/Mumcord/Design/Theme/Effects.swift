// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

extension View {
    /// Draws a CSS-style box shadow of the given shape behind the view; nothing for `nil`.
    func tokenShadow<S: InsettableShape>(_ token: ShadowToken?, shape: S) -> some View {
        background {
            if let token {
                shape
                    .inset(by: -token.spread)
                    .fill(token.color)
                    .offset(y: token.y)
                    // A CSS blur radius is twice the standard deviation of the blur.
                    .blur(radius: token.blur / 2)
            }
        }
    }
}

/// A CSS `radial-gradient(<radiusX> <radiusY> at <center>, color, transparent 60%)`: an ellipse
/// that fades from the colour in its centre to nothing at 60 % of its radius.
struct EllipticalGlow {
    let color: Color
    let center: CGPoint
    let radiusX: CGFloat
    let radiusY: CGFloat

    func draw(in context: GraphicsContext) {
        guard radiusX > 0, radiusY > 0 else { return }
        var context = context
        context.translateBy(x: center.x, y: center.y)
        context.scaleBy(x: 1, y: radiusY / radiusX)
        let gradient = Gradient(stops: [
            Gradient.Stop(color: color, location: 0),
            Gradient.Stop(color: color.opacity(0), location: 0.6),
        ])
        context.fill(
            Path(ellipseIn: CGRect(x: -radiusX, y: -radiusX, width: 2 * radiusX, height: 2 * radiusX)),
            with: .radialGradient(gradient, center: .zero, startRadius: 0, endRadius: radiusX)
        )
    }
}
