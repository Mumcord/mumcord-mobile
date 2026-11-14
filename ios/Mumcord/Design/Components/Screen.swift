// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

private struct BottomInsetKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    /// The height of the system area at the bottom of the screen (home indicator), set by
    /// `MumcordScreen` for the bars that reach into it.
    var mumcordBottomInset: CGFloat {
        get { self[BottomInsetKey.self] }
        set { self[BottomInsetKey.self] = newValue }
    }
}

/// The background every screen sits on (`.screen` in the mockups): the sidebar colour with two
/// soft glows in the top corners and film grain over everything, bars included. Children are
/// laid out in a column inside the safe area; bars extend their own background beyond it.
struct MumcordScreen<Content: View>: View {
    @Environment(\.mumcordTheme) private var theme
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        GeometryReader { proxy in
            VStack(spacing: 0) { content }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .environment(\.mumcordBottomInset, proxy.safeAreaInsets.bottom)
        }
        .background { backdrop.ignoresSafeArea() }
        .overlay {
            // Painted as a shape fill: an image view would lie over the whole screen as an
            // accessibility element and keep UI tests from tapping what is below it.
            Rectangle()
                .fill(ImagePaint(image: Image(uiImage: Grain.image)))
                .opacity(theme.opacities.grain)
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    private var backdrop: some View {
        let colors = theme.colors
        return Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(colors.bgSidebar))
            // 60rem x 40rem at 85 % / -10 % and 50rem x 36rem at -10 % / 10 %, as in tokens.css.
            EllipticalGlow(
                color: colors.glowBackdrop1,
                center: CGPoint(x: size.width * 0.85, y: size.height * -0.1),
                radiusX: 960,
                radiusY: 640
            ).draw(in: context)
            EllipticalGlow(
                color: colors.glowBackdrop2,
                center: CGPoint(x: size.width * -0.1, y: size.height * 0.1),
                radiusX: 800,
                radiusY: 576
            ).draw(in: context)
        }
    }
}

/// Film grain like the desktop app: grey noise of varying opacity. The seed is fixed so that
/// every start and every screenshot shows the same pattern.
private enum Grain {
    private static let tile = 160
    // The desktop app's grain is an SVG filter that outputs 50 % grey in linear light, which is
    // this value in sRGB.
    private static let grey: Double = 0xBC

    static let image: UIImage = {
        var random = SeededRandom(seed: 7)
        var pixels = [UInt8](repeating: 0, count: tile * tile * 4)
        for index in 0..<(tile * tile) {
            let alpha = min(max(128 + random.nextGaussian() * 48, 0), 255) / 255
            // Premultiplied alpha, as the bitmap format below expects.
            let value = UInt8(grey * alpha)
            pixels[index * 4] = value
            pixels[index * 4 + 1] = value
            pixels[index * 4 + 2] = value
            pixels[index * 4 + 3] = UInt8(alpha * 255)
        }
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        guard
            let provider = CGDataProvider(data: Data(pixels) as CFData),
            let image = CGImage(
                width: tile,
                height: tile,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: tile * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: bitmapInfo,
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
            )
        else {
            // Without grain the screen is still complete; it is decoration only.
            return UIImage()
        }
        // One noise pixel per device pixel.
        return UIImage(cgImage: image, scale: UITraitCollection.current.displayScale, orientation: .up)
    }()
}

/// Small deterministic random number generator (SplitMix64) for the grain pattern.
private struct SeededRandom {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    private mutating func nextUnit() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        // 53 random bits as a number in 0 ..< 1.
        return Double(z >> 11) / Double(1 << 53)
    }

    /// A normally distributed number (mean 0, deviation 1) by the Box-Muller method.
    mutating func nextGaussian() -> Double {
        let u = max(nextUnit(), .leastNonzeroMagnitude)
        let v = nextUnit()
        return (-2 * log(u)).squareRoot() * cos(2 * .pi * v)
    }
}
