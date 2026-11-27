// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Width of the tile in the channel tree (`.stream--compact`).
private let tileWidth: CGFloat = 232

/// The tile under the sharer in the channel tree (FR-5.70). While it is on screen it may start
/// the stream by itself (see `wantedWatches`); a tap opens the stream full screen.
struct StreamTileLine: View {
    let stream: StreamUi
    let actions: StreamActions

    var body: some View {
        let picture: AnyView? = if case .playing(let videoId) = stream.tile {
            AnyView(StreamPicture(videoId: videoId))
        } else {
            nil
        }
        MumcordStreamTile(
            label: stream.title,
            liveLabel: Strings.streamLive,
            viewerCount: stream.viewers,
            accessibilityLabel: stream.tileDescription,
            icon: stream.icon,
            status: stream.status,
            picture: picture,
            action: { actions.open(stream.key) }
        )
        .frame(width: tileWidth)
        // Lines up with the names: avatar plus gap of a person row.
        .padding(.leading, 50)
        .padding(.bottom, 6)
        .accessibilityIdentifier("stream-tile")
        .onAppear { actions.tileShown(stream.key, true) }
        .onDisappear { actions.tileShown(stream.key, false) }
    }
}
