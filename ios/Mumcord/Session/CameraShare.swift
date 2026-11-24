// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import Observation

/// The phone's camera for sharing it (FR-5.74, FR-5.75). The engine decides when the camera is
/// open (from the preview until sharing ends) and says so through `CameraCapture`; this runs the
/// camera only while the engine wants it, the app's own state shows it on, the app is on screen
/// and the user sees the camera (its preview or the "Camera on" bar). So it pauses in the
/// background and behind another sheet and resumes on return while sharing goes on (voice is
/// unaffected), and it closes by itself whenever the app's state says the camera is off, also
/// when the engine never stops it. Nothing of the camera exists before the user opened the
/// preview.
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
    /// The own camera as the session last reported it.
    @ObservationIgnored private var session = OwnCameraState.off
    /// The preview was asked for and the session does not show it yet.
    @ObservationIgnored private var opening = false
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
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                self?.follow(state.ownCamera.state)
            }
        }
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
        opening = true
        store.setCameraCapture(capture)
        let result = await store.openCameraPreview()
        if case .failed = result {
            opening = false
            follow()
        }
        return result
    }

    /// Cancel or Stop: the camera is let go at once, also while the engine is still busy with a
    /// start.
    func close() {
        closing = true
        opening = false
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

    /// The session's own camera changed. The connection may end without the engine stopping the
    /// camera (a lost connection, a disconnect that timed out): the app's off is enough.
    private func follow(_ state: OwnCameraState) {
        guard state != session else { return }
        session = state
        if state != .off { opening = false }
        follow()
    }

    private func follow() {
        let wanted = engineWants && !closing && (opening || session != .off)
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

    private func trouble(_ trouble: CameraTrouble) {
        switch trouble {
        case .notOpen:
            // The engine has closed the camera, even if its stop never arrives.
            setEngineWants(false)
        case .noCamera, .failed:
            // Either ends sharing; the engine tells the user that the camera stopped.
            store.cameraCaptureFailed(.cameraFailed)
        }
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
