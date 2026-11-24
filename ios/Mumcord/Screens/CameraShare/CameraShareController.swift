// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import Observation

/// Sharing the own camera from the screens: the preview sheet, the "Camera on" bar and the
/// notice of a camera that stopped by itself. The camera itself belongs to `camera`, which
/// follows the app on and off screen. An object of its own, read only by the camera views.
@MainActor
@Observable
final class CameraShareController {
    private var notice: CameraProblem?
    private var ownCamera = cameraOff
    /// The camera explanation before the system's question, or the way to the system settings
    /// after a refusal; nil while none is shown.
    private(set) var question: CameraPermission?

    @ObservationIgnored let camera: CameraShare
    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let permission: CameraGateway
    /// The last command sent to the engine; the next one waits for it, so a stop never
    /// overtakes the start it ends.
    @ObservationIgnored private var commands: Task<Void, Never>?

    init(store: SessionStore, camera: CameraShare, permission: CameraGateway) {
        self.store = store
        self.camera = camera
        self.permission = permission
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                self?.follow(state.ownCamera)
            }
        }
    }

    var uiState: CameraShareUiState {
        cameraShareUiState(camera: ownCamera, facing: camera.facing, facings: camera.facings, notice: notice)
    }

    private func follow(_ after: OwnCamera) {
        guard after != ownCamera else { return }
        if let stopped = stoppedNotice(before: ownCamera, after: after) { notice = stopped }
        ownCamera = after
    }

    /// The voice view's camera button while the camera is off. The camera is explained before
    /// the system asks for it, as for a photo; after a refusal only the system settings help.
    func cameraButton() {
        let now = permission.permission()
        if now == .granted {
            open()
        } else {
            question = now
        }
    }

    /// "Continue" in the explanation: the system asks, and the preview opens once it is allowed.
    func allowCamera() {
        question = nil
        permission.request { granted in
            guard granted else { return }
            DispatchQueue.main.async { [weak self] in
                MainActor.assumeIsolated { self?.open() }
            }
        }
    }

    /// "Not now", or the way to the system settings was taken.
    func dismissQuestion() {
        question = nil
    }

    /// Opens the preview; the camera permission is granted.
    func open() {
        notice = nil
        send { [weak self] _ in
            guard let self else { return }
            if case .failed = await camera.openPreview() { notice = .cameraFailed }
        }
    }

    func switchCamera() {
        camera.switchCamera()
    }

    /// "Start sharing" in the preview.
    func start() {
        send { store in _ = await store.startCameraSharing() }
    }

    /// Cancel in the preview, or Stop while sharing.
    func stop() {
        send { store in _ = await store.stopCamera() }
    }

    func dismissNotice() {
        notice = nil
    }

    private func send(_ command: @escaping @MainActor (SessionStore) async -> Void) {
        let previous = commands
        commands = Task { [store] in
            await previous?.value
            await command(store)
        }
    }
}
