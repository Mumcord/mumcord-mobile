// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import Metal

/// One decoded I420 picture (YUV 4:2:0, 8 bit, BT.601 limited range), lent by its source until
/// `VideoFrames.release(token:)`. The planes are read-only memory of the source: row `r` of the
/// Y plane starts at `y + r * strideY`, U and V likewise at half width and height (rounded up).
struct VideoFrame {
    let token: UInt64
    let width: Int
    let height: Int
    let y: UnsafePointer<UInt8>
    let u: UnsafePointer<UInt8>
    let v: UnsafePointer<UInt8>
    let strideY: Int
    let strideU: Int
    let strideV: Int

    var chromaWidth: Int { (width + 1) / 2 }
    var chromaHeight: Int { (height + 1) / 2 }
}

/// Where the newest picture of a stream comes from. Called on the render queue; neither call
/// waits for the decoder.
protocol VideoFrames: AnyObject, Sendable {
    /// The newest picture of `videoId`, or nil when there is no new one since the last call.
    func acquire(videoId: UInt64) -> VideoFrame?
    /// Gives the picture of `token` back; its planes must not be read afterwards.
    func release(token: UInt64)
}

/// The engine's pictures, through the bridge's C functions (`bridge/include/mumcord_video.h`).
/// The handle is asked for when the first picture is wanted, because the engine is created
/// later than this object; it stays valid until the process ends once it is not 0.
final class EngineVideoFrames: VideoFrames, @unchecked Sendable {
    private let handle: @Sendable () -> UInt64
    private let lock = NSLock()
    private var cached: UInt64 = 0

    init(handle: @escaping @Sendable () -> UInt64) {
        self.handle = handle
    }

    private var current: UInt64 {
        lock.withLock {
            if cached == 0 { cached = handle() }
            return cached
        }
    }

    func acquire(videoId: UInt64) -> VideoFrame? {
        var frame = MumcordVideoFrame()
        guard mumcord_video_frame_acquire(current, videoId, &frame),
              let y = frame.y, let u = frame.u, let v = frame.v
        else { return nil }
        return VideoFrame(
            token: frame.token,
            width: Int(frame.width),
            height: Int(frame.height),
            y: y,
            u: u,
            v: v,
            strideY: Int(frame.stride_y),
            strideU: Int(frame.stride_u),
            strideV: Int(frame.stride_v)
        )
    }

    func release(token: UInt64) {
        mumcord_video_frame_release(current, token)
    }
}

/// Passes "a stream has a new picture" on to the one view that shows that stream. The engine
/// says so again only after the picture was taken, so a view that starts showing a stream takes
/// the waiting picture itself (see `VideoLayerView`). Also keeps the last picture of each
/// watched stream for the next view of it (`pictures`).
final class VideoRouter: @unchecked Sendable {
    let pictures = VideoPictures()
    private let lock = NSLock()
    private var views: [UInt64: (id: ObjectIdentifier, ready: @Sendable () -> Void)] = [:]

    /// Called on the bridge's video thread: only schedules a redraw.
    func frameReady(videoId: UInt64) {
        let ready = lock.withLock { views[videoId]?.ready }
        ready?()
    }

    /// `owner` is now the one to hear about `videoId`; a view shown before it is not told any more.
    func listen(videoId: UInt64, owner: AnyObject, ready: @escaping @Sendable () -> Void) {
        lock.withLock { views[videoId] = (ObjectIdentifier(owner), ready) }
    }

    /// Stops telling `owner`, unless another view took `videoId` over meanwhile.
    func stop(videoId: UInt64, owner: AnyObject) {
        lock.withLock {
            if views[videoId]?.id == ObjectIdentifier(owner) { views[videoId] = nil }
        }
    }
}

/// The last picture of one stream as it was uploaded: its three planes. Read and written on
/// the render queue only.
final class VideoPicture: @unchecked Sendable {
    var planes: [MTLTexture] = []
    var size = CGSize.zero
}

/// The last picture of each watched stream, shared by the views of that stream. A view that
/// takes a stream over (tile, full screen, mini window) draws it at once: the view before it has
/// usually taken the waiting picture already, and a still screen share may not send the next one
/// for a long time. Only watched streams are kept (`keep`); a view of any other stream holds its
/// own picture, which goes with the view.
final class VideoPictures: @unchecked Sendable {
    private let lock = NSLock()
    private var kept: Set<UInt64> = []
    private var pictures: [UInt64: VideoPicture] = [:]

    /// The streams that are watched now; the pictures of all others are dropped.
    func keep(_ videoIds: Set<UInt64>) {
        lock.withLock {
            kept = videoIds
            pictures = pictures.filter { videoIds.contains($0.key) }
        }
    }

    /// The picture a view of `videoId` draws: the shared one while the stream is watched,
    /// otherwise `held` (the view's own picture of this stream) or a new, empty one.
    func picture(for videoId: UInt64, held: VideoPicture?) -> VideoPicture {
        lock.withLock {
            if let shared = pictures[videoId] { return shared }
            let picture = held ?? VideoPicture()
            if kept.contains(videoId) { pictures[videoId] = picture }
            return picture
        }
    }
}

/// The part of an area of `area` that shows a picture of `picture` whole and undistorted,
/// centred: the letterbox of the stage.
func fitPicture(area: CGSize, picture: CGSize) -> CGRect {
    guard picture.width > 0, picture.height > 0, area.width > 0, area.height > 0 else {
        return CGRect(origin: .zero, size: area)
    }
    let scale = min(area.width / picture.width, area.height / picture.height)
    let size = CGSize(width: (picture.width * scale).rounded(), height: (picture.height * scale).rounded())
    return CGRect(
        x: ((area.width - size.width) / 2).rounded(),
        y: ((area.height - size.height) / 2).rounded(),
        width: size.width,
        height: size.height
    )
}
