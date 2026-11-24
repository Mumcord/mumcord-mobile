// SPDX-License-Identifier: AGPL-3.0-only

import CoreVideo
import XCTest

@testable import Mumcord

/// What the camera hands to the engine, without the engine: the planes of a picture as the
/// bridge's push reads them, the feed of the camera's frame queue and the turn that makes a
/// picture upright.
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

    private func picture(
        width: Int = 64,
        height: Int = 36,
        format: OSType = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
    ) throws -> CVPixelBuffer {
        var pixels: CVPixelBuffer?
        let status = CVPixelBufferCreate(nil, width, height, format, nil, &pixels)
        XCTAssertEqual(status, kCVReturnSuccess)
        return try XCTUnwrap(pixels)
    }

    /// The bridge's `Image::fits` (`bridge/src/camera_send.rs`), which refuses a picture with
    /// `MUMCORD_CAMERA_INVALID`: every byte the conversion may read lies inside the planes.
    private func fits(_ planes: CameraPlanes) -> Bool {
        let (width, height) = (Int(planes.width), Int(planes.height))
        let (yStride, uvStride, pixelStride) = (Int(planes.yStride), Int(planes.uvStride), Int(planes.uvPixelStride))
        guard (2...4096).contains(width), (2...4096).contains(height), yStride >= width else { return false }
        let (chromaWidth, chromaHeight) = ((width + 1) / 2, (height + 1) / 2)
        let chroma = (chromaHeight - 1) * uvStride + (chromaWidth - 1) * pixelStride + 1
        return uvStride > (chromaWidth - 1) * pixelStride
            && planes.yLength >= (height - 1) * yStride + width
            && planes.uLength >= chroma
            && planes.vLength >= chroma
    }

    func testThePlanesOfAPictureAreDescribedAsTheBridgeReadsThem() throws {
        let sizes = [(1280, 720), (720, 1280), (641, 361), (2, 2)]
        let formats = [kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        for (width, height) in sizes {
            for format in formats {
                let pixels = try picture(width: width, height: height, format: format)
                let checked: Bool? = withCameraPlanes(pixels) { planes in
                    let luma = CVPixelBufferGetBaseAddressOfPlane(pixels, 0)
                    let chroma = CVPixelBufferGetBaseAddressOfPlane(pixels, 1)
                    let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)
                    let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1)
                    let size = "\(width) x \(height)"
                    XCTAssertEqual(planes.width, UInt32(width), size)
                    XCTAssertEqual(planes.height, UInt32(height), size)
                    XCTAssertEqual(UnsafeRawPointer(planes.y), UnsafeRawPointer(luma), size)
                    XCTAssertEqual(Int(planes.yStride), yStride, size)
                    XCTAssertEqual(planes.yLength, yStride * height, size)
                    XCTAssertEqual(UnsafeRawPointer(planes.u), UnsafeRawPointer(chroma), "U is the first byte of each pair")
                    XCTAssertEqual(planes.v - planes.u, 1, "V is the second byte of each pair")
                    XCTAssertEqual(Int(planes.uvStride), uvStride, size)
                    XCTAssertEqual(planes.uLength, uvStride * ((height + 1) / 2), size)
                    XCTAssertEqual(planes.vLength, planes.uLength - 1, "V ends where the plane ends")
                    XCTAssertEqual(planes.uvPixelStride, 2)
                    XCTAssertTrue(fits(planes), "the bridge would refuse \(size)")
                    return true
                }
                XCTAssertEqual(checked, true, "\(width) x \(height) is not read")
            }
        }
    }

    func testOnlyNv12PicturesAreRead() throws {
        for format in [kCVPixelFormatType_32BGRA, kCVPixelFormatType_420YpCbCr8Planar] {
            let read: Bool? = withCameraPlanes(try picture(format: format)) { _ in true }
            XCTAssertNil(read, "format \(format)")
        }
    }

    func testTheTurnOfAPictureIsTheNearestQuarterTurn() {
        let turns: [(CGFloat, UInt32)] = [
            (0, 0), (90, 90), (180, 180), (270, 270), (360, 0),
            (44, 0), (46, 90), (89.5, 90), (300, 270), (-90, 270), (-180, 180), (450, 90),
        ]
        for (degrees, expected) in turns {
            XCTAssertEqual(quarterTurns(degrees), expected, "\(degrees)°")
        }
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
