// SPDX-License-Identifier: AGPL-3.0-only

import CoreVideo
import Foundation

/// Where the camera's pictures go. Called on the camera's frame queue, one picture at a time;
/// never waits.
protocol CameraFrames: AnyObject, Sendable {
    /// Hands one picture to the engine. `rotation` is the clockwise turn in degrees (0, 90, 180,
    /// 270) that makes it upright. Returns a `MumcordCameraPush` value (`CameraPush`).
    @discardableResult
    func push(_ pixels: CVPixelBuffer, rotation: UInt32) -> Int32
}

/// The values of `MumcordCameraPush` (`bridge/include/mumcord_camera.h`) the app acts on.
enum CameraPush {
    static let sent = Int32(MUMCORD_CAMERA_SENT.rawValue)
    /// The engine has not opened the camera: the header asks to stop capturing.
    static let notOpen = Int32(MUMCORD_CAMERA_NOT_OPEN.rawValue)
    static let invalid = Int32(MUMCORD_CAMERA_INVALID.rawValue)
}

/// The planes of one NV12 picture as the engine's push reads them: a Y plane and one plane of
/// interleaved U and V at half width and height, U first (`uvPixelStride` 2).
struct CameraPlanes {
    let width: UInt32
    let height: UInt32
    let y: UnsafePointer<UInt8>
    let yLength: Int
    let yStride: UInt32
    let u: UnsafePointer<UInt8>
    let uLength: Int
    /// The second byte of each pair; one byte shorter than U, so it ends where the plane ends.
    let v: UnsafePointer<UInt8>
    let vLength: Int
    let uvStride: UInt32
    let uvPixelStride: UInt32 = 2
}

/// Reads the planes of `pixels` in place while `body` runs, with the buffer locked; nothing is
/// copied or allocated. Nil for a picture that is not NV12 (the capture output is asked for it,
/// `AVCameraSource`) or cannot be read.
func withCameraPlanes<Result>(_ pixels: CVPixelBuffer, _ body: (CameraPlanes) -> Result) -> Result? {
    let format = CVPixelBufferGetPixelFormatType(pixels)
    guard format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        || format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        CVPixelBufferGetPlaneCount(pixels) == 2
    else { return nil }
    guard CVPixelBufferLockBaseAddress(pixels, .readOnly) == kCVReturnSuccess else { return nil }
    defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
    guard let luma = CVPixelBufferGetBaseAddressOfPlane(pixels, 0),
          let chroma = CVPixelBufferGetBaseAddressOfPlane(pixels, 1)
    else { return nil }
    let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)
    let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1)
    let chromaLength = uvStride * CVPixelBufferGetHeightOfPlane(pixels, 1)
    let uv = UnsafePointer(chroma.assumingMemoryBound(to: UInt8.self))
    return body(CameraPlanes(
        width: UInt32(CVPixelBufferGetWidth(pixels)),
        height: UInt32(CVPixelBufferGetHeight(pixels)),
        y: UnsafePointer(luma.assumingMemoryBound(to: UInt8.self)),
        yLength: yStride * CVPixelBufferGetHeightOfPlane(pixels, 0),
        yStride: UInt32(yStride),
        u: uv,
        uLength: chromaLength,
        v: uv + 1,
        vLength: chromaLength - 1,
        uvStride: UInt32(uvStride)
    ))
}

/// The engine's camera input, through the C function of `bridge/include/mumcord_camera.h`. The
/// planes are read in place while the buffer is locked: nothing is copied and nothing is
/// allocated here. The handle is asked for when the first picture comes, because the engine is
/// created later than this object; once it is not 0 it stays valid until the process ends.
final class EngineCameraFrames: CameraFrames, @unchecked Sendable {
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

    func push(_ pixels: CVPixelBuffer, rotation: UInt32) -> Int32 {
        withCameraPlanes(pixels) { planes in
            var image = MumcordCameraImage(
                width: planes.width,
                height: planes.height,
                rotation: rotation,
                y: planes.y,
                y_len: planes.yLength,
                y_stride: planes.yStride,
                u: planes.u,
                u_len: planes.uLength,
                v: planes.v,
                v_len: planes.vLength,
                uv_stride: planes.uvStride,
                uv_pixel_stride: planes.uvPixelStride
            )
            return mumcord_camera_push_frame(current, &image)
        } ?? CameraPush.invalid
    }
}

/// Hands every picture of the camera to the engine, on the camera's frame queue. When the
/// engine answers that it has not opened the camera, `notOpen` is told once per run of the
/// camera, so the camera stops as the header asks, without a call for every picture.
final class CameraFrameFeed: @unchecked Sendable {
    private let frames: CameraFrames
    private let notOpen: @Sendable () -> Void
    /// The clockwise turn that makes the pictures upright. Only on the frame queue, as all of it.
    var rotation: UInt32 = 0
    private var told = false

    init(frames: CameraFrames, notOpen: @escaping @Sendable () -> Void) {
        self.frames = frames
        self.notOpen = notOpen
    }

    /// The camera runs again: a refusal is told again.
    func restart() {
        told = false
    }

    func push(_ pixels: CVPixelBuffer) {
        guard frames.push(pixels, rotation: rotation) == CameraPush.notOpen, !told else { return }
        told = true
        notOpen()
    }
}
