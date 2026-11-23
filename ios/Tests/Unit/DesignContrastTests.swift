// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import XCTest

@testable import Mumcord

/// WCAG 2 contrast of the text and background pairs the components use, in both themes.
final class DesignContrastTests: XCTestCase {
    private typealias Pair = (name: String, foreground: Color, background: Color)

    private let themes = [("dark", MumcordColors.dark), ("light", MumcordColors.light)]

    private func contrast(_ foreground: Color, _ background: Color) -> Double {
        let back = RGBA(background)
        let a = RGBA(foreground).composited(over: back).luminance + 0.05
        let b = back.luminance + 0.05
        return a > b ? a / b : b / a
    }

    private func textPairs(_ colors: MumcordColors) -> [Pair] {
        let surfaces = [
            ("bgServers", colors.bgServers),
            ("bgSidebar", colors.bgSidebar),
            ("bgChat", colors.bgChat),
            ("bgElevated", colors.bgElevated),
            ("bgHover", colors.bgHover),
            ("bgSelected", colors.bgSelected),
        ]
        var pairs: [Pair] = []
        for (surfaceName, surface) in surfaces {
            pairs.append(("text on \(surfaceName)", colors.text, surface))
            pairs.append(("textSecondary on \(surfaceName)", colors.textSecondary, surface))
            pairs.append(("textMuted on \(surfaceName)", colors.textMuted, surface))
            pairs.append(("accentText on \(surfaceName)", colors.accentText, surface))
            pairs.append(("danger on \(surfaceName)", colors.danger, surface))
        }
        let mediaLabel = RGBA(colors.mediaScrim).composited(over: RGBA(colors.mediaBg))
        pairs.append(("localMuted on bgSidebar", colors.localMuted, colors.bgSidebar))
        pairs.append(("success on bgSidebar", colors.success, colors.bgSidebar))
        pairs.append(("warning on bgSelected", colors.warning, colors.bgSelected))
        pairs.append(("onAccent on accent", colors.onAccent, colors.accent))
        pairs.append(("onAccent on danger", colors.onAccent, colors.danger))
        pairs.append(("onAccent on badge", colors.onAccent, colors.accentStop2))
        pairs.append(("primaryText on primaryBg", colors.primaryText, colors.primaryBg))
        pairs.append(("onTalking on talking", colors.onTalking, colors.talking))
        pairs.append(("onLive on live", colors.onLive, colors.live))
        pairs.append((
            "mediaText on media label",
            colors.mediaText,
            Color(.sRGB, red: mediaLabel.red, green: mediaLabel.green, blue: mediaLabel.blue, opacity: 1)
        ))
        pairs.append(("toast", colors.bgSidebar, colors.text))
        for slot in 1...8 {
            pairs.append(("avatarText on avatar \(slot)", colors.avatarText, colors.avatar(slot)))
        }
        return pairs
    }

    private func failures(_ pairs: (MumcordColors) -> [Pair], below minimum: Double) -> [String] {
        var failures: [String] = []
        for (theme, colors) in themes {
            for pair in pairs(colors) {
                let ratio = contrast(pair.foreground, pair.background)
                if ratio < minimum {
                    failures.append("\(theme): \(pair.name) = \(String(format: "%.2f", ratio))")
                }
            }
        }
        return failures
    }

    func testTextMeetsAaInBothThemes() {
        let found = failures(textPairs, below: 4.5)
        XCTAssertTrue(found.isEmpty, "Below 4.5:1:\n" + found.joined(separator: "\n"))
    }

    func testIconsAndControlOutlinesMeetThreeToOne() {
        let found = failures({ colors in
            [
                ("accent icon on bgServers", colors.accent, colors.bgServers),
                ("talking ring on bgSidebar", colors.talking, colors.bgSidebar),
                ("talking border on bgSelected", colors.talking, colors.bgSelected),
                ("borderStrong on bgSidebar", colors.borderStrong, colors.bgSidebar),
                ("borderStrong on bgElevated", colors.borderStrong, colors.bgElevated),
                ("slider thumb on bgHover", colors.text, colors.bgHover),
            ]
        }, below: 3.0)
        XCTAssertTrue(found.isEmpty, "Below 3:1:\n" + found.joined(separator: "\n"))
    }
}
