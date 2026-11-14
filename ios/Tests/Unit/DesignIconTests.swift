// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import XCTest

@testable import Mumcord

/// The generated icon set against design/icons.json.
final class DesignIconTests: XCTestCase {
    func testIconSetIsCompleteAndEveryIconDrawsInsideItsGrid() throws {
        let root = try DesignSource.object("icons.json")
        let source = try XCTUnwrap(root["icons"] as? [String: [String]])
        XCTAssertFalse(source.isEmpty)
        XCTAssertEqual(Set(MumcordIcons.all.keys), Set(source.keys))
        XCTAssertEqual(Double(MumcordIcons.viewBox), try DesignSource.number(root["viewBox"]))
        XCTAssertEqual(Double(MumcordIcons.strokeWidth), try DesignSource.number(root["strokeWidth"]))

        // Half a stroke width of slack around the grid.
        let grid = CGRect(x: 0, y: 0, width: MumcordIcons.viewBox, height: MumcordIcons.viewBox)
            .insetBy(dx: -MumcordIcons.strokeWidth / 2, dy: -MumcordIcons.strokeWidth / 2)
        for (id, paths) in source {
            let icon = try XCTUnwrap(MumcordIcons.all[id], id)
            XCTAssertEqual(icon.id, id)
            XCTAssertFalse(icon.path.isEmpty, "\(id) is empty")
            XCTAssertTrue(grid.contains(icon.path.boundingRect), "\(id) leaves the grid: \(icon.path.boundingRect)")
            // Every path of the source starts one or more subpaths.
            var subpaths = 0
            icon.path.forEach { element in
                if case .move = element { subpaths += 1 }
            }
            XCTAssertGreaterThanOrEqual(subpaths, paths.count, "\(id) lost a path")
        }
    }
}
