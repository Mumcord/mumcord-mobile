// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation

/// Stream pictures made here instead of by the engine's decoders, so the real render path
/// (Metal, the I420 shader) draws something known in tests and screenshots. Every stream shows
/// the same picture; it is made once and lent out like the engine's.
final class FakeVideoFrames: VideoFrames, @unchecked Sendable {
    static let width: UInt32 = 640
    static let height: UInt32 = 360

    enum Pattern {
        /// The picture of the mockups: a code window and a game on a violet desktop.
        case mockup
        /// Pure red on the left half and pure blue on the right, for a check of the colours.
        case colours
    }

    // Memory of its own that never moves, so it can be lent out as the engine lends its own.
    private let y: UnsafeMutableBufferPointer<UInt8>
    private let u: UnsafeMutableBufferPointer<UInt8>
    private let v: UnsafeMutableBufferPointer<UInt8>
    private let lock = NSLock()
    private var nextToken: UInt64 = 1

    init(_ pattern: Pattern) {
        let width = Int(Self.width)
        let height = Int(Self.height)
        let colour: (Int, Int) -> (Double, Double, Double) = switch pattern {
        case .mockup: { Self.mockup(x: Double($0) / Double(width), y: Double($1) / Double(height)) }
        case .colours: { x, _ in x < width / 2 ? (1, 0, 0) : (0, 0, 1) }
        }
        let y = UnsafeMutableBufferPointer<UInt8>.allocate(capacity: width * height)
        let u = UnsafeMutableBufferPointer<UInt8>.allocate(capacity: width / 2 * height / 2)
        let v = UnsafeMutableBufferPointer<UInt8>.allocate(capacity: width / 2 * height / 2)
        for row in 0..<height {
            for column in 0..<width {
                let (r, g, b) = colour(column, row)
                y[row * width + column] = Self.byte(16 + 65.481 * r + 128.553 * g + 24.966 * b)
                if row % 2 == 0 && column % 2 == 0 {
                    let index = row / 2 * (width / 2) + column / 2
                    u[index] = Self.byte(128 - 37.797 * r - 74.203 * g + 112 * b)
                    v[index] = Self.byte(128 + 112 * r - 93.786 * g - 18.214 * b)
                }
            }
        }
        self.y = y
        self.u = u
        self.v = v
    }

    deinit {
        y.deallocate()
        u.deallocate()
        v.deallocate()
    }

    private static func byte(_ value: Double) -> UInt8 {
        UInt8(min(max(value.rounded(), 0), 255))
    }

    /// The colour of the mockup picture at a point of it, from 0 to 1 in both directions.
    private static func mockup(x: Double, y: Double) -> (Double, Double, Double) {
        func inside(_ left: Double, _ top: Double, _ width: Double, _ height: Double) -> Bool {
            x >= left && x < left + width && y >= top && y < top + height
        }
        // The code window: a title bar and lines of code.
        if inside(0.06, 0.12, 0.42, 0.06) { return (0.17, 0.19, 0.33) }
        if inside(0.06, 0.18, 0.42, 0.62) {
            let line = Int((y - 0.22) / 0.06)
            let length = [0.30, 0.22, 0.34, 0.18, 0.26, 0.31, 0.2, 0.28, 0.15][max(0, min(line, 8))]
            if y > 0.22, (y - 0.22).truncatingRemainder(dividingBy: 0.06) < 0.03, x > 0.09, x < 0.09 + length {
                return line % 3 == 0 ? (0.61, 0.48, 1) : (0.85, 0.87, 0.95)
            }
            return (0.08, 0.09, 0.18)
        }
        // The game: a sky, the sun and the ground.
        if inside(0.54, 0.2, 0.4, 0.6) {
            if y > 0.62 { return (0.18, 0.55, 0.36) }
            let sun = (x - 0.82) * (x - 0.82) * 3.2 + (y - 0.34) * (y - 0.34)
            if sun < 0.006 { return (1, 0.76, 0.42) }
            return (0.36, 0.56 + (y - 0.2) * 0.5, 0.9)
        }
        // The desktop: from deep blue to violet.
        let t = (x + y) / 2
        return (0.06 + 0.3 * t, 0.09 + 0.08 * t, 0.23 + 0.22 * t)
    }

    func acquire(videoId: UInt64) -> VideoFrame? {
        let token = lock.withLock {
            defer { nextToken += 1 }
            return nextToken
        }
        guard let y = y.baseAddress, let u = u.baseAddress, let v = v.baseAddress else { return nil }
        return VideoFrame(
            token: token,
            width: Int(Self.width),
            height: Int(Self.height),
            y: UnsafePointer(y),
            u: UnsafePointer(u),
            v: UnsafePointer(v),
            strideY: Int(Self.width),
            strideU: Int(Self.width) / 2,
            strideV: Int(Self.width) / 2
        )
    }

    func release(token: UInt64) {}
}
#endif
