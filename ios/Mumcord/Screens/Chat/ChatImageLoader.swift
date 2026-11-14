// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import ImageIO
import MumcordBridge
import UIKit

/// A decoded picture, ready to draw. An animated one has `frames`, which play over `duration`.
final class LoadedImage: @unchecked Sendable {
    /// The picture, or the first frame of an animated one.
    let image: UIImage
    /// The frames of an animated picture; empty for a still one.
    let frames: [UIImage]
    let duration: TimeInterval
    /// The size of the picture as it was sent, in pixels.
    let width: Int
    let height: Int

    init(image: UIImage, frames: [UIImage] = [], duration: TimeInterval = 0, width: Int, height: Int) {
        self.image = image
        self.frames = frames
        self.duration = duration
        self.width = width
        self.height = height
    }

    var animated: Bool { frames.count > 1 }
}

/// Gets the pictures of the chat. The screens only know this seam.
protocol ChatImageLoader: AnyObject, Sendable {
    /// The picture, decoded no larger than needed for a box whose longer side is
    /// `maxPixelSize`, or nil when it cannot or may not be shown.
    func load(_ source: ChatImageSource, maxPixelSize: Int) async -> LoadedImage?
}

/// Reads pictures through the bridge (never from the network itself) and decodes them with
/// ImageIO, which also reads the frames of animated GIFs and WebPs. The encoded bytes of recent
/// pictures are kept, so scrolling back does not fetch them again.
final class BridgeImageLoader: ChatImageLoader, @unchecked Sendable {
    private static let cacheBytes = 24 * 1024 * 1024

    private let chats: ChatStore
    private let log: @Sendable (String) -> Void
    private let cache = NSCache<SourceKey, CachedData>()

    /// - Parameter log: receives the kind of a failure only, never an address or a name.
    init(chats: ChatStore, log: @escaping @Sendable (String) -> Void) {
        self.chats = chats
        self.log = log
        cache.totalCostLimit = Self.cacheBytes
    }

    func load(_ source: ChatImageSource, maxPixelSize: Int) async -> LoadedImage? {
        let key = SourceKey(source)
        let bytes: Data
        if let cached = cache.object(forKey: key) {
            bytes = cached.bytes
        } else if let fetched = await fetch(source) {
            bytes = fetched.bytes
            cache.setObject(CachedData(bytes), forKey: key, cost: bytes.count)
        } else {
            return nil
        }
        // Decoding is work for a background thread, never for the one that draws.
        let decoding = Task.detached(priority: .userInitiated) { decodeChatImage(bytes, maxPixelSize: maxPixelSize) }
        let image = await decoding.value
        if image == nil { log("decoding a chat image failed") }
        return image
    }

    private func fetch(_ source: ChatImageSource) async -> ImageData? {
        switch source {
        case .embedded(let chat, let messageId, let imageId, _, _):
            return await chats.image(chat, messageId: messageId, imageId: imageId)
        case .remote(let url, _):
            return await chats.remoteImage(url)
        }
    }
}

private final class SourceKey: NSObject {
    let source: ChatImageSource

    init(_ source: ChatImageSource) {
        self.source = source
    }

    override var hash: Int { source.hashValue }

    override func isEqual(_ object: Any?) -> Bool {
        (object as? SourceKey)?.source == source
    }
}

private final class CachedData {
    let bytes: Data

    init(_ bytes: Data) {
        self.bytes = bytes
    }
}

// MARK: Decoding

/// What the frames of one animated picture may take in memory, decoded.
let animationPixelBudget = 6_000_000
/// More frames than this are thinned out evenly; the animation keeps its length.
let animationMaxFrames = 120
private let defaultFrameDelay = 0.1
private let shortestFrameDelay = 0.02

/// Which frames of an animation of `count` frames of `pixelsPerFrame` each are kept so that
/// they fit the memory budget: all of them, or every n-th. Empty when not even two fit, so the
/// picture is shown still.
func animationFrameIndices(count: Int, pixelsPerFrame: Int) -> [Int] {
    guard count > 1, pixelsPerFrame > 0 else { return [] }
    let allowed = min(animationMaxFrames, animationPixelBudget / pixelsPerFrame)
    guard allowed >= 2 else { return [] }
    if count <= allowed { return Array(0..<count) }
    let step = Int((Double(count) / Double(allowed)).rounded(.up))
    return Array(stride(from: 0, to: count, by: step))
}

/// Decodes a picture no larger than needed for a box whose longer side is `maxPixelSize`. Nil
/// when the bytes are not a picture.
func decodeChatImage(_ bytes: Data, maxPixelSize: Int) -> LoadedImage? {
    let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
    guard let source = CGImageSourceCreateWithData(bytes as CFData, sourceOptions) else { return nil }
    let count = CGImageSourceGetCount(source)
    guard count > 0, let first = decodeFrame(source, 0, maxPixelSize: maxPixelSize) else { return nil }
    let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
    let width = (properties[kCGImagePropertyPixelWidth] as? Int) ?? first.width
    let height = (properties[kCGImagePropertyPixelHeight] as? Int) ?? first.height
    let still = LoadedImage(image: UIImage(cgImage: first), width: width, height: height)

    let indices = animationFrameIndices(count: count, pixelsPerFrame: first.width * first.height)
    guard indices.count > 1 else { return still }
    var frames: [UIImage] = []
    for index in indices {
        guard let frame = decodeFrame(source, index, maxPixelSize: maxPixelSize) else { return still }
        frames.append(UIImage(cgImage: frame))
    }
    let duration = (0..<count).reduce(0) { $0 + frameDelay(source, $1) }
    return LoadedImage(image: UIImage(cgImage: first), frames: frames, duration: duration, width: width, height: height)
}

private func decodeFrame(_ source: CGImageSource, _ index: Int, maxPixelSize: Int) -> CGImage? {
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        // Turns a photo the way its camera held it.
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceThumbnailMaxPixelSize: max(maxPixelSize, 1),
    ]
    return CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary)
}

private func frameDelay(_ source: CGImageSource, _ index: Int) -> TimeInterval {
    let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] ?? [:]
    let gif = properties[kCGImagePropertyGIFDictionary] as? [CFString: Any]
    let webp = properties[kCGImagePropertyWebPDictionary] as? [CFString: Any]
    let delay = (gif?[kCGImagePropertyGIFUnclampedDelayTime] as? Double)
        ?? (gif?[kCGImagePropertyGIFDelayTime] as? Double)
        ?? (webp?[kCGImagePropertyWebPUnclampedDelayTime] as? Double)
        ?? (webp?[kCGImagePropertyWebPDelayTime] as? Double)
        ?? defaultFrameDelay
    // Browsers play frames without a usable delay at ten a second; so does this app.
    return delay < shortestFrameDelay ? defaultFrameDelay : delay
}

/// The size of a `width` x `height` picture fitted into `maxWidth` x `maxHeight` in its own
/// proportions. A picture of unknown size gets a 16:9 frame.
func fittedSize(width: Int, height: Int, maxWidth: CGFloat, maxHeight: CGFloat) -> CGSize {
    if width <= 0 || height <= 0 { return CGSize(width: maxWidth, height: maxWidth * 9 / 16) }
    let scale = min(maxWidth / CGFloat(width), maxHeight / CGFloat(height))
    return CGSize(width: CGFloat(width) * scale, height: CGFloat(height) * scale)
}
