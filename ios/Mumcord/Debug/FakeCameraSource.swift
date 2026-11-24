// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import SwiftUI

/// A camera without a camera: it records what it is asked to do, and its picture is a drawn
/// person like the mockup's. `trouble` makes it fail as a camera would. It sends nothing to the
/// engine.
@MainActor
final class FakeCameraSource: CameraSource {
    let facings: Set<CameraFacing>
    private let onTrouble: (CameraTrouble) -> Void
    /// The camera that runs now, nil while stopped.
    private(set) var running: CameraFacing?
    /// Every start (the camera) and stop (nil), in order.
    private(set) var calls: [CameraFacing?] = []

    init(onTrouble: @escaping (CameraTrouble) -> Void = { _ in }, facings: Set<CameraFacing> = Set(CameraFacing.allCases)) {
        self.onTrouble = onTrouble
        self.facings = facings
    }

    func start(_ facing: CameraFacing) {
        running = facing
        calls.append(facing)
    }

    func stop() {
        running = nil
        calls.append(nil)
    }

    func trouble(_ trouble: CameraTrouble) {
        onTrouble(trouble)
    }

    func preview() -> AnyView {
        AnyView(FakeCameraPicture())
    }
}

/// The head and shoulders of the mockup's camera picture.
private struct FakeCameraPicture: View {
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        GeometryReader { area in
            let size = area.size
            let head = CGSize(width: size.width * 0.27, height: size.height * 0.37)
            let body = CGSize(width: size.width * 0.66, height: size.height * 0.5)
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: body.width / 2)
                    .fill(theme.colors.accent.opacity(0.45))
                    .frame(width: body.width, height: body.height)
                    .offset(x: (size.width - body.width) / 2, y: size.height * 0.58)
                Ellipse()
                    .fill(theme.colors.textMuted)
                    .frame(width: head.width, height: head.height)
                    .offset(x: (size.width - head.width) / 2, y: size.height * 0.2)
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
        .clipped()
        .accessibilityElement()
        .accessibilityIdentifier("fake-camera-picture")
    }
}
#endif
