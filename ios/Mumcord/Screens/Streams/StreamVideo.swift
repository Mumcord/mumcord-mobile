// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// Where the pictures of watched streams come from and how the engine's news of them reaches
/// the views. The engine's `sink` is called on the bridge's video thread and only schedules a
/// redraw.
final class StreamVideo: Sendable {
    let frames: VideoFrames
    let router: VideoRouter
    let sink: VideoSink

    init(frames: VideoFrames, router: VideoRouter = VideoRouter()) {
        self.frames = frames
        self.router = router
        sink = RouterSink(router: router)
    }

    /// No pictures at all (tests that do not look at them).
    static let none = StreamVideo(frames: NoVideoFrames())
}

private final class RouterSink: VideoSink {
    let router: VideoRouter

    init(router: VideoRouter) {
        self.router = router
    }

    func onFrameReady(videoId: UInt64, width: UInt32, height: UInt32) {
        router.frameReady(videoId: videoId)
    }
}

private final class NoVideoFrames: VideoFrames {
    func acquire(videoId: UInt64) -> VideoFrame? { nil }
    func release(token: UInt64) {}
}

private struct StreamVideoKey: EnvironmentKey {
    static let defaultValue = StreamVideo.none
}

extension EnvironmentValues {
    /// The pictures the stream views draw; set by the app's root.
    var streamVideo: StreamVideo {
        get { self[StreamVideoKey.self] }
        set { self[StreamVideoKey.self] = newValue }
    }
}

/// The live picture of one stream, letterboxed on black.
struct StreamPicture: View {
    let videoId: UInt64
    @Environment(\.streamVideo) private var video

    var body: some View {
        // A picture without words: VoiceOver reads the stream's title around it.
        StreamPictureLayer(videoId: videoId, video: video)
            .accessibilityHidden(true)
    }
}

private struct StreamPictureLayer: UIViewRepresentable {
    let videoId: UInt64
    let video: StreamVideo

    func makeUIView(context: Context) -> VideoLayerView {
        VideoLayerView(frame: .zero)
    }

    func updateUIView(_ view: VideoLayerView, context: Context) {
        view.show(videoId: videoId, frames: video.frames, router: video.router)
    }
}
