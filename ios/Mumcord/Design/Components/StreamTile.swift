// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

// 8 pt from the inner edge of the 1 pt border, as CSS positions the overlays.
private let overlayInset: CGFloat = 9

/// Tile of a running stream (`.stream`) in 16:9 with a LIVE badge, a label and the number of
/// viewers. It shows a placeholder that stays dark in both themes, like real video; the video
/// itself is drawn by a later task. The tile is as wide as its container; set a width for the
/// compact form in the channel tree (232 pt in the mockups).
///
/// `accessibilityLabel` is the whole tile in words for VoiceOver, such as
/// "Mira's screen, live, 1 viewer".
struct MumcordStreamTile: View {
    let label: String
    let liveLabel: String
    let viewerCount: Int
    let accessibilityLabel: String
    var selected = false
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        let chip = RoundedRectangle(cornerRadius: MumcordRadius.sm)
        Button(action: action) {
            colors.mediaBg
                .aspectRatio(16.0 / 9.0, contentMode: .fit)
                .overlay {
                    Canvas { context, size in
                        // 120 % x 90 % at 80 % / 0 % and 90 % x 80 % at 0 % / 100 %, as in mock.css.
                        EllipticalGlow(
                            color: colors.mediaGlow1,
                            center: CGPoint(x: size.width * 0.8, y: 0),
                            radiusX: size.width * 1.2,
                            radiusY: size.height * 0.9
                        ).draw(in: context)
                        EllipticalGlow(
                            color: colors.mediaGlow2,
                            center: CGPoint(x: 0, y: size.height),
                            radiusX: size.width * 0.9,
                            radiusY: size.height * 0.8
                        ).draw(in: context)
                    }
                }
                .overlay {
                    MumcordIcon(icon: MumcordIcons.monitor, label: nil, tint: colors.mediaText, size: MumcordSize.iconLg)
                        .opacity(0.7)
                }
                .overlay(alignment: .topLeading) {
                    HStack(spacing: 5) {
                        Circle().fill(colors.onLive).frame(width: 6, height: 6)
                        LineText(liveLabel, style: .live, color: colors.onLive)
                    }
                    .padding(.horizontal, MumcordSpace.s2)
                    .frame(height: 22)
                    .background(colors.live, in: chip)
                    .padding(overlayInset)
                }
                .overlay(alignment: .bottomLeading) {
                    LineText(label, style: .captionStrong, color: colors.mediaText)
                        .padding(.horizontal, MumcordSpace.s2)
                        .frame(height: 24)
                        .background(colors.mediaScrim, in: chip)
                        .padding(overlayInset)
                }
                .overlay(alignment: .bottomTrailing) {
                    HStack(spacing: 5) {
                        MumcordIcon(icon: MumcordIcons.user, label: nil, tint: colors.mediaText, size: MumcordSize.iconSm)
                        LineText(String(viewerCount), style: .captionStrong, color: colors.mediaText)
                    }
                    .padding(.horizontal, MumcordSpace.s2)
                    .frame(height: 24)
                    .background(colors.mediaScrim, in: chip)
                    .padding(overlayInset)
                }
                .clipShape(shape)
                .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
                .overlay {
                    if selected {
                        // A 2 pt accent outline, 2 pt away from the tile, without changing its size.
                        RoundedRectangle(cornerRadius: MumcordRadius.lg + 4)
                            .strokeBorder(colors.accent, lineWidth: 2)
                            .padding(-4)
                    }
                }
                .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }
}
