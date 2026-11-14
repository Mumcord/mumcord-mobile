// SPDX-License-Identifier: AGPL-3.0-only

import CoreText
import OSLog
import SwiftUI

/// Instrument Sans (body) and Bricolage Grotesque (titles) from the app bundle. Both are
/// variable fonts: every text style is the same file with its own weight, and the other axes
/// fixed to the values of design/tokens.json.
enum MumcordFonts {
    private static let logger = Logger(subsystem: "app.mumcord.mobile", category: "design")

    /// The font descriptor of each family, read from its file once. Loading also registers the
    /// file with the system for this process, so the families can be found by name as well.
    private static let descriptors: [String: CTFontDescriptor] = {
        var result: [String: CTFontDescriptor] = [:]
        for token in [FontToken.body, FontToken.display] {
            guard let url = Bundle.main.url(forResource: token.file, withExtension: "ttf") else {
                logger.error("Font file \(token.file, privacy: .public).ttf is missing in the app bundle")
                continue
            }
            var error: Unmanaged<CFError>?
            if !CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error) {
                let reason = error?.takeRetainedValue().localizedDescription ?? "unknown reason"
                logger.error("Font \(token.family, privacy: .public) could not be registered: \(reason, privacy: .public)")
            }
            guard
                let found = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor],
                let descriptor = found.first
            else {
                logger.error("Font file \(token.file, privacy: .public).ttf contains no font")
                continue
            }
            result[token.family] = descriptor
        }
        return result
    }()

    /// Registers the font files. Called once when the app starts.
    static func register() {
        _ = descriptors
    }

    /// The font of a text style. `scale` is the Dynamic Type factor of the current view.
    static func font(_ token: TextToken, scale: CGFloat = 1) -> CTFont {
        let family = token.display ? FontToken.display : FontToken.body
        let size = token.size * scale
        guard let base = descriptors[family.family] else {
            // Already logged when the file was looked up; the system font keeps text readable.
            return CTFontCreateUIFontForLanguage(.system, size, nil) ?? CTFontCreateWithName("Helvetica" as CFString, size, nil)
        }
        var variation: [NSNumber: NSNumber] = [axisTag("wght"): NSNumber(value: Double(token.weight))]
        for (axis, value) in family.axes {
            variation[axisTag(axis)] = NSNumber(value: Double(value))
        }
        let attributes = [kCTFontVariationAttribute as String: variation] as CFDictionary
        return CTFontCreateWithFontDescriptor(CTFontDescriptorCreateCopyWithAttributes(base, attributes), size, nil)
    }

    /// The height of one line as the font draws it, before the line height of the style.
    static func naturalLineHeight(_ font: CTFont) -> CGFloat {
        CTFontGetAscent(font) + CTFontGetDescent(font) + CTFontGetLeading(font)
    }

    /// A four letter axis name such as `wght` as the number Core Text uses for it.
    static func axisTag(_ name: String) -> NSNumber {
        NSNumber(value: name.utf8.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) })
    }
}
