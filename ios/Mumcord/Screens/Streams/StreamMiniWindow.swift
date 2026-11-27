// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Width of the mini window (`.pip`).
private let miniWidth: CGFloat = 204

/// The mini window's distance from the edge of the tab's content.
let miniWindowMargin = MumcordSpace.s3

/// How far the mini window stays above the bottom of the tab's content: clear of the chat's
/// input bar.
let miniWindowBottom: CGFloat = 72

/// The floating mini window (FR-5.71, `.pip`): the stream keeps playing above the chat and the
/// channels. A tap or the expand button opens it full screen; the cross stops watching.
/// `onExpand` replaces the expand action where opening needs more, such as going back to the
/// Channels column of two columns, where the stream has its place.
struct StreamMiniWindow: View {
    let stream: StreamUi
    let actions: StreamActions
    var onExpand: (() -> Void)?
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        let expand = onExpand ?? actions.expand
        Button(action: expand) {
            ZStack {
                colors.stageBg
                if stream.playing {
                    StreamPicture(videoId: stream.videoId)
                }
                if let status = stream.openStatus {
                    StageStatus(text: status, retry: nil, small: true)
                }
            }
            .overlay(alignment: .bottomLeading) {
                LineText(stream.title, style: .captionStrong, color: colors.mediaText)
                    .padding(.horizontal, MumcordSpace.s2)
                    .padding(.vertical, 2)
                    .background(colors.mediaScrim, in: RoundedRectangle(cornerRadius: MumcordRadius.sm))
                    .padding(EdgeInsets(top: 0, leading: MumcordSpace.s2, bottom: 6, trailing: MumcordSpace.s2))
            }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(stream.title)
        .accessibilityHint(Strings.streamExpand)
        .accessibilityIdentifier("stream-mini-window")
        .overlay(alignment: .top) {
            HStack(spacing: 0) {
                MumcordLiveBadge(liveLabel: Strings.streamLive)
                    .padding(.leading, 6)
                    .accessibilityHidden(true)
                Spacer(minLength: 0)
                MumcordStageButton(
                    icon: MumcordIcons.maximize,
                    label: Strings.streamExpand,
                    size: 30,
                    iconSize: 16,
                    action: expand
                )
                .accessibilityIdentifier("stream-expand")
                MumcordStageButton(icon: MumcordIcons.x, label: Strings.streamStop, size: 30, iconSize: 16, action: actions.close)
                    .accessibilityIdentifier("stream-stop")
            }
            .background(LinearGradient(colors: [colors.mediaScrim, .clear], startPoint: .top, endPoint: .bottom))
        }
        .frame(width: miniWidth, height: miniWidth * 9 / 16)
        .clipShape(shape)
        .overlay { shape.strokeBorder(colors.borderStrong, lineWidth: 1) }
        .tokenShadow(theme.shadows.raised, shape: shape)
    }
}
