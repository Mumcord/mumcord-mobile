// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI
import XCTest

@testable import Mumcord

/// Which theme and which version of it the app is drawn in, and how its colours reach the
/// design system.
final class AppThemeTests: XCTestCase {
    private let settings = ThemeSettings(
        mode: .system,
        appThemeId: "preset:paper",
        customThemes: [],
        serverThemes: [1: "custom:1"]
    )

    func testWithoutAShownServerTheAppThemeIsShown() {
        XCTAssertEqual(shownThemeId(settings, shownServerId: nil), "preset:paper")
    }

    func testAServerWithItsOwnThemeIsShownInIt() {
        XCTAssertEqual(shownThemeId(settings, shownServerId: 1), "custom:1")
    }

    func testAServerWithoutItsOwnThemeIsShownInTheAppTheme() {
        XCTAssertEqual(shownThemeId(settings, shownServerId: 2), "preset:paper")
    }

    func testDarkAndLightAreFixedAndSystemFollowsThePhone() {
        XCTAssertEqual(themeVariant(.dark, systemDark: false), .dark)
        XCTAssertEqual(themeVariant(.light, systemDark: true), .light)
        XCTAssertEqual(themeVariant(.system, systemDark: true), .dark)
        XCTAssertEqual(themeVariant(.system, systemDark: false), .light)
    }

    func testTheAppThemePicksTheVersionByModeAndPhone() {
        let dark = tokens(.dark, base: .dark)
        let light = tokens(.light, base: .light)
        XCTAssertEqual(AppTheme(mode: .system, dark: dark, light: light).tokens(systemDark: true), dark)
        XCTAssertEqual(AppTheme(mode: .system, dark: dark, light: light).tokens(systemDark: false), light)
        XCTAssertEqual(AppTheme(mode: .light, dark: dark, light: light).tokens(systemDark: true), light)
        XCTAssertTrue(AppTheme(mode: .dark, dark: dark, light: light).theme(systemDark: false).isDark)
        XCTAssertFalse(AppTheme(mode: .system, dark: dark, light: light).theme(systemDark: false).isDark)
    }

    func testTheThemesColoursReplaceTheirTokensAndTheRestComesFromTheMatchingBase() throws {
        let colors = tokens(.dark, base: .dark, background: "#0f2a3a").toColors()
        XCTAssertTrue(RGBA(colors.bgChat).isClose(to: try RGBA(hex: "#0f2a3a")))
        XCTAssertTrue(RGBA(colors.accent).isClose(to: try RGBA(hex: "#123456")))
        XCTAssertEqual(colors.avatar1, MumcordColors.dark.avatar1, "avatar colours come from the dark set")
        XCTAssertEqual(colors.backdrop, MumcordColors.dark.backdrop)
        XCTAssertEqual(colors.onTalking, MumcordColors.dark.onTalking, "no theme token for it")

        let light = tokens(.dark, base: .light).toColors()
        XCTAssertEqual(light.backdrop, MumcordColors.light.backdrop, "a dark version with light colours takes the light set")
        XCTAssertFalse(tokens(.dark, base: .light).isDark)
        XCTAssertTrue(tokens(.light, base: .dark).isDark)
    }

    /// The colours of the built-in Mumcord theme are the app's own: a phone in that theme looks
    /// as it did before themes existed. The fake engine's tokens are the engine's for Mumcord.
    func testTheMumcordTokensGiveTheAppsOwnColours() {
        for (tokens, own) in [(MockupThemes.mumcordDark, MumcordColors.dark), (MockupThemes.mumcordLight, MumcordColors.light)] {
            let drawn = tokens.toColors().byKey
            for (key, colour) in own.byKey {
                XCTAssertEqual(drawn[key], colour, "\(tokens.variant) \(key)")
            }
        }
    }

    func testAColourThatIsNotRrggbbIsRefused() {
        XCTAssertNil(hexColor("red"))
        XCTAssertNil(hexColor("#12345"))
        XCTAssertNil(hexColor("#+12345"))
        XCTAssertNil(hexColor("#12345g"))
        XCTAssertNotNil(hexColor("#A0b1C2"))
    }

    func testABrokenTokenKeepsTheColourOfTheBase() {
        let colors = tokens(.light, base: .light, background: "transparent").toColors()
        XCTAssertEqual(colors.bgChat, MumcordColors.light.bgChat)
    }

    private func tokens(_ variant: ThemeVariant, base: ThemeVariant, background: String = "#000000") -> ThemeTokens {
        ThemeTokens(
            themeId: "custom:1",
            variant: variant,
            base: base,
            bgServers: background,
            bgSidebar: background,
            bgChat: background,
            bgElevated: background,
            bgHover: background,
            bgSelected: background,
            text: "#ffffff",
            textSecondary: "#ffffff",
            textMuted: "#ffffff",
            accent: "#123456",
            onAccent: "#000000",
            accentText: "#123456",
            success: "#00ff00",
            warning: "#ffff00",
            danger: "#ff0000",
            localMuted: "#ff0000",
            talking: "#00ff00",
            accentStop1: "#123456",
            accentStop2: "#123456",
            accentStop3: "#123456",
            primaryBg: "#ffffff",
            primaryText: "#000000",
            border: "#333333",
            borderStrong: "#999999",
            focus: "#123456"
        )
    }
}
