// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit
import XCTest

/// The shared design source (`design/` in the repository), read independently of the generated
/// Swift code. The simulator runs on the computer that built the tests, so the files are read
/// from where the repository is checked out.
enum DesignSource {
    static let directory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // Unit
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // repository
        .appendingPathComponent("design")

    static func object(_ file: String) throws -> [String: Any] {
        let data = try Data(contentsOf: directory.appendingPathComponent(file))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any], "\(file) is not a JSON object")
    }

    /// A section of tokens.json without its `comment` entry.
    static func section(_ root: [String: Any], _ path: String...) throws -> [String: Any] {
        var current = root
        for key in path {
            current = try XCTUnwrap(current[key] as? [String: Any], "missing section \(path.joined(separator: "."))")
        }
        return current.filter { $0.key != "comment" }
    }

    static func number(_ value: Any?) throws -> Double {
        try XCTUnwrap((value as? NSNumber)?.doubleValue, "not a number: \(String(describing: value))")
    }
}

/// Red, green, blue and alpha from 0 to 1 in sRGB.
struct RGBA {
    let red: Double
    let green: Double
    let blue: Double
    let alpha: Double

    /// `#rrggbb` or `#rrggbbaa` as written in tokens.json.
    init(hex: String) throws {
        let digits = String(hex.dropFirst())
        let value = try XCTUnwrap(UInt32(digits, radix: 16), "not a colour: \(hex)")
        let rgba = digits.count == 6 ? (value << 8) | 0xff : value
        red = Double((rgba >> 24) & 0xff) / 255
        green = Double((rgba >> 16) & 0xff) / 255
        blue = Double((rgba >> 8) & 0xff) / 255
        alpha = Double(rgba & 0xff) / 255
    }

    init(_ color: Color) {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        self.red = Double(red)
        self.green = Double(green)
        self.blue = Double(blue)
        self.alpha = Double(alpha)
    }

    init(red: Double, green: Double, blue: Double, alpha: Double) {
        self.red = red
        self.green = green
        self.blue = blue
        self.alpha = alpha
    }

    func isClose(to other: RGBA) -> Bool {
        let tolerance = 0.5 / 255
        return abs(red - other.red) < tolerance && abs(green - other.green) < tolerance
            && abs(blue - other.blue) < tolerance && abs(alpha - other.alpha) < tolerance
    }

    /// This colour drawn over an opaque background.
    func composited(over background: RGBA) -> RGBA {
        RGBA(
            red: red * alpha + background.red * (1 - alpha),
            green: green * alpha + background.green * (1 - alpha),
            blue: blue * alpha + background.blue * (1 - alpha),
            alpha: 1
        )
    }

    /// Relative luminance as defined by WCAG 2.
    var luminance: Double {
        func linear(_ channel: Double) -> Double {
            channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }
}
