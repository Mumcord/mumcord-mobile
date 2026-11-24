// SPDX-License-Identifier: AGPL-3.0-only

import ImageIO
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The longest edge of the picture handed to the engine, which scales it to 128 pixels itself.
let avatarMaxEdge = 512

/// Turns a chosen photo into the avatar to send: upright, at most `avatarMaxEdge` pixels, as a
/// PNG that holds nothing but the pixels. Nil when the bytes are no picture.
///
/// Why on the phone: a camera photo carries EXIF data, among it the place it was taken. The
/// engine re-encodes avatars too, which drops that data, but it ignores the orientation flag, so
/// a photo taken upright would arrive lying on its side. The system's decoder applies that flag
/// here, and the new PNG is written without any of the photo's properties. Runs off the main
/// thread.
func avatarImage(_ bytes: Data) -> Data? {
    guard let source = CGImageSourceCreateWithData(bytes as CFData, nil), CGImageSourceGetCount(source) > 0 else {
        return nil
    }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        // Turns the photo the way its camera held it.
        kCGImageSourceCreateThumbnailWithTransform: true,
        // Decodes a large photo straight to the small size, so it never fills the memory.
        kCGImageSourceThumbnailMaxPixelSize: avatarMaxEdge,
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
    return pngWithoutProperties(image)
}

/// A photo the camera just took, as the avatar to send (see `avatarImage`). The system's
/// camera hands it over as pixels with an orientation; drawing turns it upright.
func avatarImage(cameraPhoto photo: UIImage) -> Data? {
    let width = photo.size.width * photo.scale
    let height = photo.size.height * photo.scale
    let longest = max(width, height)
    guard longest > 0 else { return nil }
    let scale = min(1, CGFloat(avatarMaxEdge) / longest)
    let size = CGSize(width: (width * scale).rounded(), height: (height * scale).rounded())
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let upright = UIGraphicsImageRenderer(size: size, format: format).image { _ in
        photo.draw(in: CGRect(origin: .zero, size: size))
    }
    return upright.cgImage.flatMap(pngWithoutProperties)
}

/// The pixels as a PNG, with no metadata of the picture they came from.
private func pngWithoutProperties(_ image: CGImage) -> Data? {
    let data = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil) else {
        return nil
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { return nil }
    return data as Data
}

/// An avatar's bytes as a picture of at most `maxPixelSize` pixels on its longer side, or nil
/// for bytes that are no picture. The bytes come from the server and are untrusted: a small file
/// can claim a huge picture, so it is decoded straight to the size it is drawn at, as the chat's
/// pictures are. Runs off the main thread.
func avatarPicture(_ bytes: Data, maxPixelSize: Int) -> CGImage? {
    let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
    guard let source = CGImageSourceCreateWithData(bytes as CFData, sourceOptions), CGImageSourceGetCount(source) > 0 else {
        return nil
    }
    return decodeFrame(source, 0, maxPixelSize: maxPixelSize)
}

/// Draws an avatar from its bytes, decoded once per change of the bytes, off the main thread and
/// at the size `points` it is drawn at (see `avatarPicture`). `content` gets nil while there is
/// no picture or it is being decoded.
struct AvatarPicture<Content: View>: View {
    let bytes: Data?
    /// The diameter the picture is drawn at.
    let points: CGFloat
    @ViewBuilder let content: (UIImage?) -> Content
    @Environment(\.displayScale) private var displayScale
    @State private var picture: UIImage?

    var body: some View {
        content(picture)
            .task(id: bytes) {
                guard let bytes else {
                    picture = nil
                    return
                }
                let pixels = Int((points * displayScale).rounded(.up))
                let decoded = await Task.detached(priority: .userInitiated) { avatarPicture(bytes, maxPixelSize: pixels) }.value
                if Task.isCancelled { return }
                picture = decoded.map { UIImage(cgImage: $0) }
            }
    }
}
