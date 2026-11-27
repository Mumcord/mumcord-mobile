// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// What the camera does about the notices of its capture session, apart from AVFoundation so
/// the rules can be tested without a camera. A camera taken by the system or another app (a
/// call, multitasking, a video app) only interrupts the session: meanwhile no pictures come and
/// sharing stays on, and when the interruption ends the session runs again by itself, or is
/// run again here. A session that cannot run again, or fails otherwise, ends sharing.
@MainActor
final class CameraSessionWatch {
    /// Runs the session again if it stopped; says whether it runs afterwards.
    private let resume: (@escaping @MainActor (Bool) -> Void) -> Void
    private let fail: () -> Void
    private let log: (String) -> Void
    /// The camera should run: between `start` and `stop` of the camera.
    private(set) var active = false

    init(
        resume: @escaping (@escaping @MainActor (Bool) -> Void) -> Void,
        fail: @escaping () -> Void,
        log: @escaping (String) -> Void
    ) {
        self.resume = resume
        self.fail = fail
        self.log = log
    }

    func started() {
        active = true
    }

    func stopped() {
        active = false
    }

    /// The system took the camera for a while; `reason` is the raw value of AVFoundation's reason.
    func interrupted(reason: Int?) {
        guard active else { return }
        log("camera: the capture session was interrupted (\(reason ?? -1))")
    }

    func interruptionEnded() {
        guard active else { return }
        runAgain()
    }

    /// A runtime error of the session. A reset of the media services is recovered by running
    /// the session again; anything else ends sharing.
    func failed(mediaServicesWereReset: Bool, code: Int) {
        guard active else { return }
        if mediaServicesWereReset {
            runAgain()
            return
        }
        log("camera: the capture session failed (\(code))")
        fail()
    }

    private func runAgain() {
        resume { [weak self] running in
            guard let self, active, !running else { return }
            log("camera: the capture session did not run again")
            fail()
        }
    }
}
