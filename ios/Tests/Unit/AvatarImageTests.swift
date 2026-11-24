// SPDX-License-Identifier: AGPL-3.0-only

import ImageIO
import UIKit
import UniformTypeIdentifiers
import XCTest

@testable import Mumcord

/// A camera photo becomes the avatar without its EXIF data (among it the place it was taken) and
/// upright, on the system's own image decoder.
final class AvatarImageTests: XCTestCase {
    private let photoWidth = 400
    private let photoHeight = 200

    /// A JPEG 400 x 200 pixels, left half red, with a GPS position, a camera make and "rotate
    /// 90°" (EXIF orientation 6) in its properties.
    private func cameraPhoto() throws -> Data {
        let image = picture(width: photoWidth, height: photoHeight) { x in x < self.photoWidth / 2 ? .red : .blue }
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil))
        let properties: [CFString: Any] = [
            kCGImagePropertyOrientation: 6,
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 52.5,
                kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 13.4,
                kCGImagePropertyGPSLongitudeRef: "E",
            ],
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Test camera"],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "secret"],
        ]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    private func picture(width: Int, height: Int, colour: (Int) -> UIColor) -> CGImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            for x in 0..<width {
                colour(x).setFill()
                context.fill(CGRect(x: x, y: 0, width: 1, height: height))
            }
        }
        return image.cgImage!
    }

    private func properties(_ data: Data) throws -> [CFString: Any] {
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        return try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
    }

    private func decoded(_ data: Data) throws -> CGImage {
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        return try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
    }

    /// Red and blue of the pixel, 0 to 255.
    private func redBlue(_ image: CGImage, x: Int, y: Int) -> (red: Int, blue: Int) {
        var bytes = [UInt8](repeating: 0, count: 4)
        let context = CGContext(
            data: &bytes,
            width: 1,
            height: 1,
            bitsPerComponent: 8,
            bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
        context?.draw(image, in: CGRect(x: -x, y: y - image.height + 1, width: image.width, height: image.height))
        return (Int(bytes[0]), Int(bytes[2]))
    }

    /// The system reports the size and colour space of every PNG as EXIF values of its own; the
    /// file holds nothing else of that kind.
    private func onlyTheSize(_ properties: [CFString: Any]) -> Bool {
        let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let allowed = Set([kCGImagePropertyExifColorSpace, kCGImagePropertyExifPixelXDimension, kCGImagePropertyExifPixelYDimension].map { $0 as String })
        return Set(exif.keys.map { $0 as String }).isSubset(of: allowed)
    }

    func testThePhotosLocationAndCameraDataAreNotSent() throws {
        let photo = try cameraPhoto()
        XCTAssertNotNil(try properties(photo)[kCGImagePropertyGPSDictionary], "the test photo has a location")

        let avatar = try XCTUnwrap(avatarImage(photo))

        let kept = try properties(avatar)
        XCTAssertNil(kept[kCGImagePropertyGPSDictionary])
        XCTAssertTrue(onlyTheSize(kept), "\(kept)")
        XCTAssertNil((kept[kCGImagePropertyTIFFDictionary] as? [CFString: Any])?[kCGImagePropertyTIFFMake])
        XCTAssertNil(kept[kCGImagePropertyOrientation], "already upright, nothing left to turn")
        let bytes = String(decoding: avatar, as: UTF8.self)
        XCTAssertFalse(bytes.contains("Exif"), "no EXIF block at all")
        XCTAssertFalse(bytes.contains("Test camera"))
        XCTAssertFalse(bytes.contains("secret"))
    }

    func testThePhotoArrivesUpright() throws {
        let avatar = try decoded(XCTUnwrap(avatarImage(cameraPhoto())))
        XCTAssertEqual(avatar.width, photoHeight, "turned by 90°: as wide as the photo was high")
        XCTAssertEqual(avatar.height, photoWidth)
        // Turned clockwise, the red left half of the photo is now at the top.
        let top = redBlue(avatar, x: avatar.width / 2, y: avatar.height / 4)
        let bottom = redBlue(avatar, x: avatar.width / 2, y: avatar.height * 3 / 4)
        XCTAssertGreaterThan(top.red, top.blue, "red at the top")
        XCTAssertGreaterThan(bottom.blue, bottom.red, "blue at the bottom")
    }

    func testALargePhotoIsScaledDown() throws {
        let big = UIImage(cgImage: picture(width: 3000, height: 1000) { _ in .green })
        let avatar = try decoded(XCTUnwrap(avatarImage(XCTUnwrap(big.pngData()))))
        XCTAssertEqual(avatar.width, avatarMaxEdge)
        XCTAssertTrue((170...171).contains(avatar.height), "\(avatar.height)")
    }

    func testACameraPhotoIsTurnedUprightAndScaledDown() throws {
        let photo = UIImage(cgImage: picture(width: 2000, height: 1000) { x in x < 1000 ? .red : .blue }, scale: 1, orientation: .right)
        let avatar = try decoded(XCTUnwrap(avatarImage(cameraPhoto: photo)))
        XCTAssertEqual(avatar.width, 256)
        XCTAssertEqual(avatar.height, avatarMaxEdge)
        XCTAssertTrue(onlyTheSize(try properties(XCTUnwrap(avatarImage(cameraPhoto: photo)))))
        let top = redBlue(avatar, x: avatar.width / 2, y: avatar.height / 4)
        XCTAssertGreaterThan(top.red, top.blue, "red at the top")
    }

    func testBytesThatAreNoPictureAreRefused() {
        XCTAssertNil(avatarImage(Data("hello".utf8)))
    }
}
