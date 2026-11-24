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
        let invalid = CameraPush.invalid
        // The capture output is asked for NV12 (`AVCameraSource`): a Y plane and one plane of
        // interleaved U and V at half width and height.
        let format = CVPixelBufferGetPixelFormatType(pixels)
        guard format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            || format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            CVPixelBufferGetPlaneCount(pixels) == 2
        else { return invalid }
        guard CVPixelBufferLockBaseAddress(pixels, .readOnly) == kCVReturnSuccess else { return invalid }
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let luma = CVPixelBufferGetBaseAddressOfPlane(pixels, 0),
              let chroma = CVPixelBufferGetBaseAddressOfPlane(pixels, 1)
        else { return invalid }
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)
        let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1)
        let chromaLength = uvStride * CVPixelBufferGetHeightOfPlane(pixels, 1)
        let uv = chroma.assumingMemoryBound(to: UInt8.self)
        var image = MumcordCameraImage(
            width: UInt32(CVPixelBufferGetWidth(pixels)),
            height: UInt32(CVPixelBufferGetHeight(pixels)),
            rotation: rotation,
            y: UnsafePointer(luma.assumingMemoryBound(to: UInt8.self)),
            y_len: yStride * CVPixelBufferGetHeightOfPlane(pixels, 0),
            y_stride: UInt32(yStride),
            u: UnsafePointer(uv),
            u_len: chromaLength,
            // V is the second byte of each pair.
            v: UnsafePointer(uv + 1),
            v_len: chromaLength - 1,
            uv_stride: UInt32(uvStride),
            uv_pixel_stride: 2
        )
        return mumcord_camera_push_frame(current, &image)
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
