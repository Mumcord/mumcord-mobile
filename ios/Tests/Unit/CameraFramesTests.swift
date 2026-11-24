// SPDX-License-Identifier: AGPL-3.0-only

import CoreVideo
import XCTest

@testable import Mumcord

/// What the camera hands to the engine, without the engine: the feed of the camera's frame
/// queue.
final class CameraFramesTests: XCTestCase {
    /// Answers every push with `answer` and counts the pushes.
    private final class AnsweringFrames: CameraFrames, @unchecked Sendable {
        var answer = CameraPush.sent
        private(set) var rotations: [UInt32] = []

        func push(_ pixels: CVPixelBuffer, rotation: UInt32) -> Int32 {
            rotations.append(rotation)
            return answer
        }
    }

    private final class Count: @unchecked Sendable {
        var value = 0
    }

    private func picture(width: Int = 64, height: Int = 36) throws -> CVPixelBuffer {
        var pixels: CVPixelBuffer?
        let status = CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, nil, &pixels)
        XCTAssertEqual(status, kCVReturnSuccess)
        return try XCTUnwrap(pixels)
    }

    func testAnEngineThatHasNotOpenedTheCameraIsToldOncePerRun() throws {
        let frames = AnsweringFrames()
        let told = Count()
        let feed = CameraFrameFeed(frames: frames) { told.value += 1 }
        let pixels = try picture()
        feed.rotation = 90

        feed.push(pixels)
        XCTAssertEqual(told.value, 0, "a sent picture is no refusal")
        frames.answer = CameraPush.notOpen
        feed.push(pixels)
        feed.push(pixels)
        XCTAssertEqual(told.value, 1, "the camera is asked to stop once, not for every picture")

        feed.restart()
        feed.push(pixels)
        XCTAssertEqual(told.value, 2, "a new run of the camera is told again")
        XCTAssertEqual(frames.rotations, [90, 90, 90, 90])
    }
}
