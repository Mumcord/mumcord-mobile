// SPDX-License-Identifier: AGPL-3.0-only

import CoreText
import SwiftUI
import XCTest

@testable import Mumcord

/// The generated Swift tokens against design/tokens.json, read independently.
final class DesignTokenTests: XCTestCase {
    private var root: [String: Any] = [:]

    override func setUpWithError() throws {
        root = try DesignSource.object("tokens.json")
    }

    func testColorsEqualTheSourceInBothThemes() throws {
        for (theme, colors) in [("dark", MumcordColors.dark), ("light", MumcordColors.light)] {
            let source = try DesignSource.section(root, "color", theme)
            let generated = colors.byKey
            XCTAssertEqual(Set(generated.keys), Set(source.keys), "\(theme) colour keys")
            for (key, value) in source {
                let expected = try RGBA(hex: XCTUnwrap(value as? String))
                let actual = try RGBA(XCTUnwrap(generated[key], "\(theme) \(key)"))
                XCTAssertTrue(actual.isClose(to: expected), "\(theme) colour \(key): \(actual) is not \(value)")
            }
        }
    }

    func testOpacitiesEqualTheSourceInBothThemes() throws {
        for (theme, opacities) in [("dark", MumcordOpacities.dark), ("light", MumcordOpacities.light)] {
            let source = try DesignSource.section(root, "opacity", theme)
            let generated = opacities.byKey
            XCTAssertEqual(Set(generated.keys), Set(source.keys), "\(theme) opacity keys")
            for (key, value) in source {
                XCTAssertEqual(try XCTUnwrap(generated[key]), try DesignSource.number(value), accuracy: 0.0001, "\(theme) \(key)")
            }
        }
    }

    func testShadowsEqualTheSourceInBothThemes() throws {
        for (theme, shadows) in [("dark", MumcordShadows.dark), ("light", MumcordShadows.light)] {
            let source = try DesignSource.section(root, "shadow", theme)
            let generated = shadows.byKey
            XCTAssertEqual(Set(generated.keys), Set(source.keys), "\(theme) shadow keys")
            for (key, value) in source {
                let expected = try XCTUnwrap(value as? [String: Any])
                let actual = try XCTUnwrap(generated[key], "\(theme) \(key)")
                XCTAssertEqual(Double(actual.y), try DesignSource.number(expected["y"]), "\(theme) \(key) y")
                XCTAssertEqual(Double(actual.blur), try DesignSource.number(expected["blur"]), "\(theme) \(key) blur")
                XCTAssertEqual(Double(actual.spread), try DesignSource.number(expected["spread"]), "\(theme) \(key) spread")
                let color = try RGBA(hex: XCTUnwrap(expected["color"] as? String))
                XCTAssertTrue(RGBA(actual.color).isClose(to: color), "\(theme) \(key) colour")
            }
        }
    }

    func testTextStylesEqualTheSource() throws {
        let source = try DesignSource.section(root, "text")
        XCTAssertEqual(Set(TextToken.byKey.keys), Set(source.keys))
        for (key, value) in source {
            let expected = try XCTUnwrap(value as? [String: Any])
            let actual = try XCTUnwrap(TextToken.byKey[key], key)
            XCTAssertEqual(actual.display, expected["font"] as? String == "display", "\(key) font")
            XCTAssertEqual(Double(actual.size), try DesignSource.number(expected["size"]), "\(key) size")
            XCTAssertEqual(Double(actual.weight), try DesignSource.number(expected["weight"]), "\(key) weight")
            XCTAssertEqual(Double(actual.lineHeight), try DesignSource.number(expected["line-height"]), accuracy: 0.0001, "\(key) line height")
            XCTAssertEqual(Double(actual.letterSpacing), try DesignSource.number(expected["letter-spacing"]), accuracy: 0.0001, "\(key) letter spacing")
        }
    }

    func testSpacingRadiiAndSizesEqualTheSource() throws {
        let sections: [(String, [String: CGFloat])] = [
            ("space", MumcordSpace.byKey),
            ("radius", MumcordRadius.byKey),
            ("size", MumcordSize.byKey),
        ]
        for (name, generated) in sections {
            let source = try DesignSource.section(root, name)
            XCTAssertEqual(Set(generated.keys), Set(source.keys), "\(name) keys")
            for (key, value) in source {
                XCTAssertEqual(Double(try XCTUnwrap(generated[key])), try DesignSource.number(value), "\(name) \(key)")
            }
        }
    }

    func testTouchTargetIsAtLeast44Points() {
        XCTAssertGreaterThanOrEqual(MumcordSize.touchTargetIos, 44)
        XCTAssertGreaterThanOrEqual(MumcordSize.touchVisual, MumcordSize.touchTargetIos)
    }

    func testFontsEqualTheSourceAndAreInTheAppBundle() throws {
        let source = try DesignSource.section(root, "font")
        for (key, token) in [("body", FontToken.body), ("display", FontToken.display)] {
            let expected = try XCTUnwrap(source[key] as? [String: Any])
            XCTAssertEqual(token.family, expected["family"] as? String)
            let axes = try XCTUnwrap(expected["axes"] as? [String: Any])
            XCTAssertEqual(Set(token.axes.keys), Set(axes.keys), "\(key) axes")
            for (axis, value) in axes {
                XCTAssertEqual(Double(try XCTUnwrap(token.axes[axis])), try DesignSource.number(value), "\(key) \(axis)")
            }
            XCTAssertNotNil(Bundle.main.url(forResource: token.file, withExtension: "ttf"), "\(token.file).ttf is not in the app bundle")
        }
    }

    func testTextStylesResolveToTheBundledFontsWithTheirWeight() throws {
        let cases: [(TextToken, String)] = [(.title, "Bricolage Grotesque"), (.bodyStrong, "Instrument Sans")]
        for (style, family) in cases {
            let font = MumcordFonts.font(style)
            XCTAssertEqual(CTFontCopyFamilyName(font) as String, family, "\(family) is not the font that draws this style")
            XCTAssertEqual(CTFontGetSize(font), style.size)
            let variation = try XCTUnwrap(CTFontCopyVariation(font) as? [NSNumber: NSNumber], "\(family) has no variation")
            XCTAssertEqual(variation[MumcordFonts.axisTag("wght")]?.doubleValue, Double(style.weight), "\(family) weight")
        }
    }

    func testTextSizeFollowsTheDynamicTypeScale() {
        XCTAssertEqual(CTFontGetSize(MumcordFonts.font(.body, scale: 1.5)), TextToken.body.size * 1.5)
    }

    func testThemeModeResolvesDarkLightAndSystem() {
        XCTAssertTrue(MumcordTheme.resolve(.dark, system: .light).isDark)
        XCTAssertFalse(MumcordTheme.resolve(.light, system: .dark).isDark)
        XCTAssertTrue(MumcordTheme.resolve(.system, system: .dark).isDark)
        XCTAssertFalse(MumcordTheme.resolve(.system, system: .light).isDark)
    }
}
