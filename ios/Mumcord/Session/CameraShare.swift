// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import Observation

/// The phone's camera for sharing it (FR-5.74, FR-5.75). The engine decides when the camera is
/// open (from the preview until sharing ends) and says so through `CameraCapture`; this runs the
/// camera only while the engine wants it, the app is on screen and the user sees the camera (its
/// preview or the "Camera on" bar), so it pauses in the background and behind another sheet and
/// resumes on return while sharing goes on (voice is unaffected). Nothing of the camera exists
/// before the user opened the preview.
@MainActor
@Observable
final class CameraShare {
    /// The camera the user picked in the preview.
    private(set) var facing = CameraFacing.front
    /// The camera while the engine has it open (also while paused), for the preview.
    private(set) var source: CameraSource?

    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let newSource: (@escaping (CameraTrouble) -> Void) -> CameraSource
    @ObservationIgnored private var engineWants = false
    @ObservationIgnored private var onScreen = false
    @ObservationIgnored private var visible = false
    /// The user turned the camera off; it stays closed until the next preview, whatever the
    /// engine still wants.
    @ObservationIgnored private var closing = false
    /// The camera `source` was last started with; nil while it is stopped.
    @ObservationIgnored private var running: CameraFacing?
    @ObservationIgnored private lazy var capture = EngineCapture(owner: self)

    /// - Parameter newSource: makes the camera when the engine opens it, with the callback for a
    ///   camera that cannot run.
    init(store: SessionStore, newSource: @escaping (@escaping (CameraTrouble) -> Void) -> CameraSource) {
        self.store = store
        self.newSource = newSource
    }

    /// The cameras of the phone; empty while the camera is closed.
    var facings: Set<CameraFacing> {
        source?.facings ?? []
    }

    /// The app came on screen or left it.
    func setOnScreen(_ shown: Bool) {
        guard shown != onScreen else { return }
        onScreen = shown
        follow()
    }

    /// The user sees the camera, or no longer: its preview is the sheet on screen, or the
    /// "Camera on" bar is shown.
    func setVisible(_ shown: Bool) {
        guard shown != visible else { return }
        visible = shown
        follow()
    }

    /// Opens the preview with the front camera; the engine then starts the camera.
    func openPreview() async -> Outcome<Void> {
        facing = .front
        closing = false
        store.setCameraCapture(capture)
        return await store.openCameraPreview()
    }

    /// Cancel or Stop: the camera is let go at once, also while the engine is still busy with a
    /// start.
    func close() {
        closing = true
        follow()
    }

    /// Front to back and back; also while sharing.
    func switchCamera() {
        facing = facing == .front ? .back : .front
        follow()
    }

    fileprivate func setEngineWants(_ wanted: Bool) {
        guard wanted != engineWants else { return }
        engineWants = wanted
        follow()
    }

    private func follow() {
        let wanted = engineWants && !closing
        if wanted && onScreen && visible {
            let camera = source ?? newSource { [weak self] trouble in self?.trouble(trouble) }
            if source == nil { source = camera }
            if running != facing {
                running = facing
                camera.start(facing)
            }
            return
        }
        // Paused while the user cannot see the camera (sharing stays on), or closed.
        if running != nil {
            running = nil
            source?.stop()
        }
        if !wanted { source = nil }
    }

    /// Either trouble ends sharing; the engine tells the user that the camera stopped.
    private func trouble(_ trouble: CameraTrouble) {
        store.cameraCaptureFailed(.cameraFailed)
    }
}

/// The engine's side of the camera. The engine calls it on its own thread, possibly holding its
/// locks: the wish is only handed to the main thread, in the order the engine said it.
private final class EngineCapture: CameraCapture, @unchecked Sendable {
    private weak var owner: CameraShare?

    init(owner: CameraShare) {
        self.owner = owner
    }

    func start() {
        tell(true)
    }

    func stop() {
        tell(false)
    }

    private func tell(_ wanted: Bool) {
        DispatchQueue.main.async { [self] in
            MainActor.assumeIsolated { owner?.setEngineWants(wanted) }
        }
    }
}
