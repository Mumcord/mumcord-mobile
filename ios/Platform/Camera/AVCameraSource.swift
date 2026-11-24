// SPDX-License-Identifier: AGPL-3.0-only

import AVFoundation
import SwiftUI
import UIKit

/// The phone's camera through AVFoundation: the picture for `preview` and every frame for the
/// engine through `frames`, on a queue of its own. Late frames are dropped by the capture
/// output, so the camera never waits for the engine. The camera is opened by `start` and
/// released by `stop`; nothing of it runs before the user asked to share.
///
/// - Parameter onTrouble: called on the main thread when the camera cannot run.
@MainActor
final class AVCameraSource: CameraSource {
    let facings: Set<CameraFacing>
    private let frames: CameraFrames
    private lazy var capture = CameraRunner(frames: frames) { [weak self] in
        DispatchQueue.main.async {
            MainActor.assumeIsolated { self?.notOpen() }
        }
    }
    private let onTrouble: (CameraTrouble) -> Void
    private let log: @Sendable (String) -> Void
    /// The camera the caller wants while it runs; nil while stopped.
    private var wanted: CameraFacing?
    /// The device that runs now.
    private var device: AVCaptureDevice?
    /// Says how far to turn the frames to make them upright, as the phone is held.
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var rotationObservation: NSKeyValueObservation?
    private var observers: [NSObjectProtocol] = []
    private lazy var watch = CameraSessionWatch(
        resume: { [weak self] done in
            self?.capture.resume { running in
                DispatchQueue.main.async {
                    MainActor.assumeIsolated { done(running) }
                }
            }
        },
        fail: { [weak self] in self?.onTrouble(.failed) },
        log: self.log
    )

    init(frames: CameraFrames, onTrouble: @escaping (CameraTrouble) -> Void, log: @escaping @Sendable (String) -> Void) {
        self.frames = frames
        self.onTrouble = onTrouble
        self.log = log
        facings = Set(CameraFacing.allCases.filter { Self.device(facing: $0) != nil })
        let center = NotificationCenter.default
        let session = capture.session
        observers = [
            center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: .main) {
                [weak self] notification in
                let code = (notification.userInfo?[AVCaptureSessionErrorKey] as? AVError)?.code
                MainActor.assumeIsolated {
                    self?.watch.failed(mediaServicesWereReset: code == .mediaServicesWereReset, code: code?.rawValue ?? 0)
                }
            },
            center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main) {
                [weak self] notification in
                let reason = notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int
                MainActor.assumeIsolated { self?.watch.interrupted(reason: reason) }
            },
            center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: .main) {
                [weak self] _ in
                MainActor.assumeIsolated { self?.watch.interruptionEnded() }
            },
        ]
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
    }

    func start(_ facing: CameraFacing) {
        wanted = facing
        watch.started()
        guard let next = Self.device(facing: facing) ?? CameraFacing.allCases.lazy.compactMap(Self.device(facing:)).first else {
            onTrouble(.noCamera)
            return
        }
        guard next != device else {
            // The same camera, which the system may have stopped meanwhile.
            capture.resume()
            return
        }
        device = next
        followRotation(of: next)
        capture.run(next) { [weak self] opened in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    if opened {
                        // The preview's connection is a new one with each camera, also while the
                        // session goes on running: its rotation is set again.
                        NotificationCenter.default.post(name: cameraOpened, object: self.capture.session)
                    } else {
                        self.log("camera: the camera could not be opened")
                        self.onTrouble(.failed)
                    }
                }
            }
        }
    }

    func stop() {
        wanted = nil
        watch.stopped()
        device = nil
        rotationObservation = nil
        rotation = nil
        capture.halt()
    }

    func preview() -> AnyView {
        AnyView(CameraPreview(session: capture.session, device: device))
    }

    /// The engine refuses the pictures: it has closed the camera, so the camera stops.
    private func notOpen() {
        guard wanted != nil else { return }
        log("camera: the engine has not opened the camera")
        onTrouble(.notOpen)
    }

    private func followRotation(of device: AVCaptureDevice) {
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
        rotation = coordinator
        rotationObservation = coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.initial, .new]) {
            [capture] coordinator, _ in
            capture.setRotation(quarterTurns(coordinator.videoRotationAngleForHorizonLevelCapture))
        }
    }

    private static func device(facing: CameraFacing) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: facing == .front ? .front : .back)
    }
}

/// Posted with the capture session as its object once a camera runs in it.
private let cameraOpened = Notification.Name("app.mumcord.mobile.camera-opened")

/// An angle in degrees as the nearest of 0, 90, 180 and 270.
private func quarterTurns(_ degrees: CGFloat) -> UInt32 {
    let turns = Int((degrees / 90).rounded()) % 4
    return UInt32((turns + 4) % 4 * 90)
}

/// The capture session, run on its own queue (starting and stopping it blocks), and the frame
/// output on another, where every frame goes to the engine as the camera delivers it.
private final class CameraRunner: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let sessionQueue = DispatchQueue(label: "app.mumcord.mobile.camera-session")
    private let framesQueue = DispatchQueue(label: "app.mumcord.mobile.camera-frames")
    /// Only used on `framesQueue`.
    private let feed: CameraFrameFeed

    /// - Parameter notOpen: called on the frame queue when the engine has not opened the camera.
    init(frames: CameraFrames, notOpen: @escaping @Sendable () -> Void) {
        feed = CameraFrameFeed(frames: frames, notOpen: notOpen)
        super.init()
        // NV12, which the engine's push reads in place; late frames are dropped, never queued.
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: framesQueue)
    }

    /// Opens `device` in place of the camera that runs, and runs the session.
    func run(_ device: AVCaptureDevice, opened: @escaping @Sendable (Bool) -> Void) {
        sessionQueue.async { [self] in
            session.beginConfiguration()
            for input in session.inputs { session.removeInput(input) }
            // 16:9 frames of which the engine sends at most 640 x 360: the smallest 16:9 size
            // every iPhone camera offers, so a phone held upright still fills that size.
            session.sessionPreset = session.canSetSessionPreset(.hd1280x720) ? .hd1280x720 : .medium
            guard let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
                session.commitConfiguration()
                opened(false)
                return
            }
            session.addInput(input)
            if !session.outputs.contains(output) {
                guard session.canAddOutput(output) else {
                    session.commitConfiguration()
                    opened(false)
                    return
                }
                session.addOutput(output)
            }
            // The engine wants the picture as the camera sees it, never mirrored, for both
            // cameras (the bridge only turns, crops and scales it); only the preview mirrors the
            // front camera. The connection is a new one with each camera.
            if let connection = output.connection(with: .video), connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false
            }
            session.commitConfiguration()
            framesQueue.async { [feed] in feed.restart() }
            if !session.isRunning { session.startRunning() }
            opened(true)
        }
    }

    /// Runs the session again if the system stopped it; `done` says whether it runs afterwards.
    func resume(_ done: @escaping @Sendable (Bool) -> Void = { _ in }) {
        sessionQueue.async { [session] in
            if !session.isRunning && !session.inputs.isEmpty { session.startRunning() }
            done(session.isRunning)
        }
    }

    /// Stops the session and lets go of the camera.
    func halt() {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
            session.beginConfiguration()
            for input in session.inputs { session.removeInput(input) }
            session.commitConfiguration()
        }
    }

    func setRotation(_ degrees: UInt32) {
        framesQueue.async { [feed] in feed.rotation = degrees }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        feed.push(pixels)
    }
}

/// The camera's picture as the user sees it, filling its frame; the system mirrors the front
/// camera. A picture without words: the preview card names it for VoiceOver.
private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let device: AVCaptureDevice?

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ view: CameraPreviewView, context: Context) {
        view.follow(device)
    }
}

private final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    private var device: AVCaptureDevice?
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var observation: NSKeyValueObservation?
    private var sessionObservers: [NSObjectProtocol] = []

    var previewLayer: AVCaptureVideoPreviewLayer {
        // The layer class is set above, so the cast always holds.
        layer as! AVCaptureVideoPreviewLayer
    }

    deinit {
        for observer in sessionObservers { NotificationCenter.default.removeObserver(observer) }
    }

    /// Keeps the picture upright for the camera that runs, as the phone is held.
    func follow(_ next: AVCaptureDevice?) {
        if sessionObservers.isEmpty, let session = previewLayer.session {
            // The layer's connection exists only once the session runs with the camera, and is
            // a new one after a switch of the camera.
            sessionObservers = [AVCaptureSession.didStartRunningNotification, cameraOpened].map { name in
                NotificationCenter.default.addObserver(forName: name, object: session, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.applyRotation() }
                }
            }
        }
        guard next != device else { return }
        device = next
        observation = nil
        rotation = next.map { AVCaptureDevice.RotationCoordinator(device: $0, previewLayer: previewLayer) }
        observation = rotation?.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]) {
            [weak self] _, _ in
            DispatchQueue.main.async { self?.applyRotation() }
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        applyRotation()
    }

    private func applyRotation() {
        guard let angle = rotation?.videoRotationAngleForHorizonLevelPreview,
              let connection = previewLayer.connection,
              connection.isVideoRotationAngleSupported(angle),
              connection.videoRotationAngle != angle
        else { return }
        connection.videoRotationAngle = angle
    }
}
