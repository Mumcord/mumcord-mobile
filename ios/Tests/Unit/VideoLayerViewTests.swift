// SPDX-License-Identifier: AGPL-3.0-only

import UIKit
import XCTest

@testable import Mumcord

/// When a stream view takes pictures, with the real renderer and a source that counts.
@MainActor
final class VideoLayerViewTests: XCTestCase {
    private let frames = CountingVideoFrames()
    private let router = VideoRouter()
    private let videoId: UInt64 = 7

    /// A view in a window, showing the stream.
    private func shownView() throws -> (UIWindow, VideoLayerView) {
        _ = try XCTUnwrap(VideoRenderer.shared, "the simulator has Metal")
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 160, height: 90))
        let view = VideoLayerView(frame: window.bounds)
        window.addSubview(view)
        view.show(videoId: videoId, frames: frames, router: router)
        return (window, view)
    }

    /// Waits until every draw scheduled so far has run.
    private func drained() {
        VideoRenderer.shared?.queue.sync {}
    }

    func testNothingIsTakenOnceTheAppResignsActive() async throws {
        let (window, view) = try shownView()
        await assertEventually("the waiting picture is taken at once") { self.frames.acquired > 0 }

        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        drained()
        let before = frames.acquired
        router.frameReady(videoId: videoId)
        drained()
        XCTAssertEqual(frames.acquired, before, "a resigning app draws no more")

        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        await assertEventually("active again, the view takes the waiting picture") { self.frames.acquired > before }
        withExtendedLifetime((window, view)) {}
    }
}

/// Has no pictures, but counts how often one was asked for.
private final class CountingVideoFrames: VideoFrames, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var acquired: Int { lock.withLock { count } }

    func acquire(videoId: UInt64) -> VideoFrame? {
        lock.withLock { count += 1 }
        return nil
    }

    func release(token: UInt64) {}
}
