// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import ImageIO
import MumcordBridge
import UIKit
import UniformTypeIdentifiers
import XCTest

@testable import Mumcord

/// Decoding the pictures of the chat and reading the ones the user picks.
final class ChatImageTests: XCTestCase {
    private func png(width: Int = 40, height: Int = 20) throws -> Data {
        let bytes = gradientPNG([0x3B2F8FFF, 0xD0607FFF], width: width, height: height)
        XCTAssertFalse(bytes.isEmpty, "the test picture could not be drawn")
        return bytes
    }

    private func cgImage(_ bytes: Data) throws -> CGImage {
        try XCTUnwrap(UIImage(data: bytes)?.cgImage)
    }

    /// `frames` encoded as one picture of `type`, each with the properties given.
    private func encode(_ frames: [CGImage], type: UTType, frameProperties: [CFString: Any]? = nil) throws -> Data {
        let data = NSMutableData()
        let destination = try XCTUnwrap(
            CGImageDestinationCreateWithData(data as CFMutableData, type.identifier as CFString, frames.count, nil)
        )
        let properties = frameProperties.map { $0 as CFDictionary }
        for frame in frames {
            CGImageDestinationAddImage(destination, frame, properties)
        }
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    // MARK: Animations

    func testAShortAnimationKeepsAllItsFrames() {
        XCTAssertEqual(animationFrameIndices(count: 10, pixelsPerFrame: 100 * 100), Array(0..<10))
        XCTAssertEqual(animationFrameIndices(count: 2, pixelsPerFrame: 1), [0, 1])
        XCTAssertEqual(
            animationFrameIndices(count: animationMaxFrames, pixelsPerFrame: 100 * 100),
            Array(0..<animationMaxFrames)
        )
        // Exactly two frames of this size fit the memory budget.
        XCTAssertEqual(animationFrameIndices(count: 2, pixelsPerFrame: animationPixelBudget / 2), [0, 1])
    }

    func testALongAnimationIsThinnedOutEvenly() {
        let thinned = animationFrameIndices(count: 300, pixelsPerFrame: 100 * 100)
        XCTAssertEqual(thinned, Array(stride(from: 0, to: 300, by: 3)))
        XCTAssertLessThanOrEqual(thinned.count, animationMaxFrames)

        let oneMore = animationFrameIndices(count: animationMaxFrames + 1, pixelsPerFrame: 100 * 100)
        XCTAssertEqual(oneMore, Array(stride(from: 0, to: animationMaxFrames + 1, by: 2)))
    }

    func testAnAnimationOfLargeFramesIsThinnedToTheMemoryBudget() {
        // Six frames of a million pixels fit the budget.
        let thinned = animationFrameIndices(count: 20, pixelsPerFrame: 1_000_000)
        XCTAssertEqual(thinned, [0, 4, 8, 12, 16])
        XCTAssertLessThanOrEqual(thinned.count * 1_000_000, animationPixelBudget)
    }

    func testAnAnimationThatDoesNotFitIsShownStill() {
        XCTAssertEqual(animationFrameIndices(count: 10, pixelsPerFrame: 4_000_000), [])
        XCTAssertEqual(animationFrameIndices(count: 10, pixelsPerFrame: animationPixelBudget + 1), [])
        XCTAssertEqual(animationFrameIndices(count: 1, pixelsPerFrame: 100), [], "one frame is no animation")
        XCTAssertEqual(animationFrameIndices(count: 0, pixelsPerFrame: 100), [])
        XCTAssertEqual(animationFrameIndices(count: 10, pixelsPerFrame: 0), [])
    }

    // MARK: Sizes

    func testPicturesKeepTheirProportionsInsideTheFrame() {
        let wide = fittedSize(width: 400, height: 225, maxWidth: 200, maxHeight: 240)
        XCTAssertEqual(wide.width, 200, accuracy: 0.01)
        XCTAssertEqual(wide.height, 112.5, accuracy: 0.01)

        let tall = fittedSize(width: 100, height: 400, maxWidth: 200, maxHeight: 240)
        XCTAssertEqual(tall.width, 60, accuracy: 0.01)
        XCTAssertEqual(tall.height, 240, accuracy: 0.01)

        let exact = fittedSize(width: 200, height: 240, maxWidth: 200, maxHeight: 240)
        XCTAssertEqual(exact.width, 200, accuracy: 0.01)
        XCTAssertEqual(exact.height, 240, accuracy: 0.01)
    }

    func testAPictureOfUnknownSizeGetsTheWideFrameOfTheMockup() {
        for (width, height) in [(0, 0), (0, 100), (100, 0), (-1, 100)] {
            let size = fittedSize(width: width, height: height, maxWidth: 200, maxHeight: 240)
            XCTAssertEqual(size.width, 200, accuracy: 0.01)
            XCTAssertEqual(size.height, 112.5, accuracy: 0.01)
        }
    }

    // MARK: Decoding

    func testAStillPictureIsDecodedWithItsSize() throws {
        let loaded = try XCTUnwrap(decodeChatImage(try png(), maxPixelSize: 100))
        XCTAssertEqual(loaded.width, 40)
        XCTAssertEqual(loaded.height, 20)
        XCTAssertFalse(loaded.animated)
        XCTAssertTrue(loaded.frames.isEmpty)
        XCTAssertEqual(loaded.duration, 0)
        XCTAssertGreaterThan(loaded.image.size.width, 0)
        XCTAssertGreaterThan(loaded.image.size.height, 0)
    }

    func testAPictureIsDecodedNoLargerThanItIsShown() throws {
        let loaded = try XCTUnwrap(decodeChatImage(try png(width: 400, height: 200), maxPixelSize: 100))
        let decoded = try XCTUnwrap(loaded.image.cgImage)
        XCTAssertLessThanOrEqual(max(decoded.width, decoded.height), 100)
        XCTAssertEqual(loaded.width, 400, "the size of the picture as it was sent stays known")
        XCTAssertEqual(loaded.height, 200)
    }

    func testBytesThatAreNoPictureAreNotDecoded() {
        XCTAssertNil(decodeChatImage(Data([0, 1, 2, 3, 4, 5, 6, 7]), maxPixelSize: 100))
        XCTAssertNil(decodeChatImage(Data("not a picture".utf8), maxPixelSize: 100))
        XCTAssertNil(decodeChatImage(Data(), maxPixelSize: 100))
    }

    func testAnAnimatedPictureIsDecodedWithItsFrames() throws {
        let frames = [
            try cgImage(gradientPNG([0x3B2F8FFF, 0xD0607FFF], width: 16, height: 16)),
            try cgImage(gradientPNG([0x0E6F78FF, 0x7A3FA0FF], width: 16, height: 16)),
            try cgImage(gradientPNG([0xE0905AFF, 0x2F6F4FFF], width: 16, height: 16)),
        ]
        let delay: [CFString: Any] = [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: 0.2]]
        let gif = try encode(frames, type: .gif, frameProperties: delay)

        let loaded = try XCTUnwrap(decodeChatImage(gif, maxPixelSize: 100))
        XCTAssertTrue(loaded.animated)
        XCTAssertEqual(loaded.frames.count, 3)
        XCTAssertGreaterThan(loaded.duration, 0)
        XCTAssertEqual(loaded.width, 16)
        XCTAssertEqual(loaded.height, 16)
    }

    // MARK: Picking

    func testAPickedPngIsSentAsItIs() throws {
        let bytes = try png()
        XCTAssertEqual(readPickedImage(bytes), PickedImage(bytes: bytes, mime: "image/png"))
    }

    func testAPickedJpegIsSentAsItIs() throws {
        let bytes = try XCTUnwrap(UIImage(data: try png())?.jpegData(compressionQuality: 0.8))
        XCTAssertEqual(readPickedImage(bytes), PickedImage(bytes: bytes, mime: "image/jpeg"))
    }

    func testAPickedPictureInAnotherEncodingBecomesJpeg() throws {
        let tiff = try encode([try cgImage(try png())], type: .tiff)
        XCTAssertFalse(tiff.isEmpty)

        let picked = try XCTUnwrap(readPickedImage(tiff))
        XCTAssertEqual(picked.mime, "image/jpeg")
        XCTAssertNotEqual(picked.bytes, tiff)
        XCTAssertEqual(Array(picked.bytes.prefix(2)), [0xFF, 0xD8], "the bytes are a JPEG")
        let decoded = try XCTUnwrap(decodeChatImage(picked.bytes, maxPixelSize: 100))
        XCTAssertEqual(decoded.width, 40)
        XCTAssertEqual(decoded.height, 20)
    }

    func testPickedBytesThatAreNoPictureAreRefused() {
        XCTAssertNil(readPickedImage(Data([0, 1, 2, 3, 4, 5, 6, 7])))
        XCTAssertNil(readPickedImage(Data("not a picture".utf8)))
        XCTAssertNil(readPickedImage(Data()))
    }

    func testAPickedPictureLargerThanTheEngineTakesIsRefused() throws {
        // A real picture followed by filler: its encoding is still recognised, only its size is
        // over the limit.
        var bytes = try png()
        bytes.append(Data(count: maxImageBytes))
        XCTAssertGreaterThan(bytes.count, maxImageBytes)
        XCTAssertNil(readPickedImage(bytes))
    }

    func testACameraPhotoBecomesJpeg() throws {
        let photo = try XCTUnwrap(UIImage(data: try png()))
        let picked = try XCTUnwrap(readCameraPhoto(photo))
        XCTAssertEqual(picked.mime, "image/jpeg")
        XCTAssertEqual(Array(picked.bytes.prefix(2)), [0xFF, 0xD8], "the bytes are a JPEG")
        XCTAssertLessThanOrEqual(picked.bytes.count, maxImageBytes)
        let decoded = try XCTUnwrap(decodeChatImage(picked.bytes, maxPixelSize: 100))
        XCTAssertEqual(decoded.width, 40)
        XCTAssertEqual(decoded.height, 20)
    }

    func testACameraPhotoWithoutPixelsIsRefused() {
        XCTAssertNil(readCameraPhoto(UIImage()))
    }
}
