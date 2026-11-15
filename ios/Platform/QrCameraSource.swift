// SPDX-License-Identifier: AGPL-3.0-only

import AVFoundation
import SwiftUI
import UIKit

/// Reads QR codes with the phone's camera through the system's own reader
/// (`AVCaptureMetadataOutput`); no picture leaves the phone or is stored. The texts are handed
/// on as they are and never logged.
struct CameraQrSource: QrSource {
    @MainActor
    func viewfinder(onText: @escaping @MainActor (String) -> Void, onFailure: @escaping @MainActor () -> Void) -> AnyView {
        AnyView(QrCameraView(onText: onText, onFailure: onFailure))
    }
}

private struct QrCameraView: UIViewRepresentable {
    let onText: @MainActor (String) -> Void
    let onFailure: @MainActor () -> Void

    func makeCoordinator() -> QrCameraReader {
        QrCameraReader()
    }

    func makeUIView(context: Context) -> QrPreviewView {
        let view = QrPreviewView()
        context.coordinator.onText = onText
        context.coordinator.onFailure = onFailure
        context.coordinator.start(in: view)
        return view
    }

    func updateUIView(_ view: QrPreviewView, context: Context) {
        context.coordinator.onText = onText
        context.coordinator.onFailure = onFailure
    }

    static func dismantleUIView(_ view: QrPreviewView, coordinator: QrCameraReader) {
        coordinator.stop()
    }
}

/// A view whose layer is the camera picture.
final class QrPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        // The layer class is set above, so the cast always holds.
        layer as! AVCaptureVideoPreviewLayer
    }
}

/// Runs the capture session on its own queue (starting it blocks) and delivers the QR texts
/// on the main thread.
final class QrCameraReader: NSObject, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "app.mumcord.mobile.qr-camera")
    var onText: (@MainActor (String) -> Void)?
    var onFailure: (@MainActor () -> Void)?

    func start(in view: QrPreviewView) {
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        queue.async { [weak self] in self?.configureAndRun() }
    }

    func stop() {
        queue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configureAndRun() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            fail()
            return
        }
        let output = AVCaptureMetadataOutput()
        session.beginConfiguration()
        session.addInput(input)
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            fail()
            return
        }
        session.addOutput(output)
        // The types the output offers are known only once it is part of the session.
        guard output.availableMetadataObjectTypes.contains(.qr) else {
            session.commitConfiguration()
            fail()
            return
        }
        output.metadataObjectTypes = [.qr]
        output.setMetadataObjectsDelegate(self, queue: .main)
        session.commitConfiguration()
        session.startRunning()
    }

    private func fail() {
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.onFailure?() }
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        // Delivered on the main queue, as set above.
        for case let code as AVMetadataMachineReadableCodeObject in metadataObjects where code.type == .qr {
            guard let text = code.stringValue else { continue }
            MainActor.assumeIsolated { onText?(text) }
        }
    }
}
