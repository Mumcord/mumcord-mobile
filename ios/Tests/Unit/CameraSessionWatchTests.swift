// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// What the camera does about interruptions and failures of its capture session, without a
/// camera.
@MainActor
final class CameraSessionWatchTests: XCTestCase {
    /// Whether the session runs when it is asked to run again; nil holds the answer back.
    private var runsAgain: Bool? = true
    private var pending: [(Bool) -> Void] = []
    private var resumes = 0
    private var failures = 0

    private func watch() -> CameraSessionWatch {
        CameraSessionWatch(
            resume: { [unowned self] done in
                resumes += 1
                if let runsAgain { done(runsAgain) } else { pending.append(done) }
            },
            fail: { [unowned self] in failures += 1 },
            log: { _ in }
        )
    }

    func testThePictureComesBackAfterAnInterruption() {
        let watch = watch()
        watch.started()
        watch.interrupted(reason: 1)
        XCTAssertEqual(resumes, 0, "nothing to do while another app has the camera")
        XCTAssertEqual(failures, 0, "sharing stays on")
        watch.interruptionEnded()
        XCTAssertEqual(resumes, 1, "the session is run again if the system did not")
        XCTAssertEqual(failures, 0)
    }

    func testACameraThatCannotComeBackEndsSharing() {
        let watch = watch()
        watch.started()
        watch.interrupted(reason: 2)
        runsAgain = false
        watch.interruptionEnded()
        XCTAssertEqual(failures, 1, "\"Camera stopped.\"")
    }

    func testAResetOfTheMediaServicesIsRecoveredAndOtherFailuresEndSharing() {
        let watch = watch()
        watch.started()
        watch.failed(mediaServicesWereReset: true, code: -11819)
        XCTAssertEqual(resumes, 1)
        XCTAssertEqual(failures, 0)
        runsAgain = false
        watch.failed(mediaServicesWereReset: true, code: -11819)
        XCTAssertEqual(failures, 1, "a reset after which the session does not run")
        watch.failed(mediaServicesWereReset: false, code: -11800)
        XCTAssertEqual(failures, 2)
        XCTAssertEqual(resumes, 2)
    }

    func testAStoppedCameraIgnoresTheSession() {
        let watch = watch()
        watch.interruptionEnded()
        watch.failed(mediaServicesWereReset: false, code: -11800)
        XCTAssertEqual(resumes, 0, "nothing before the camera started")
        XCTAssertEqual(failures, 0)

        runsAgain = nil
        watch.started()
        watch.interruptionEnded()
        watch.stopped()
        pending.forEach { $0(false) }
        XCTAssertEqual(failures, 0, "a camera stopped meanwhile does not fail")
        watch.failed(mediaServicesWereReset: false, code: -11800)
        XCTAssertEqual(failures, 0)
    }
}
