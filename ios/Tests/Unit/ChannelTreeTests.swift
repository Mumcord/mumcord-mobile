// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

final class ChannelTreeTests: XCTestCase {
    func testWithoutARootThereIsNoTree() {
        XCTAssertNil(buildChannelTree(channels: [channel(1, "Lobby", parentId: 0)], people: [Person]()))
    }

    func testChannelsAreOrderedByPositionThenNameAndPeopleByName() throws {
        let root = try XCTUnwrap(buildChannelTree(
            channels: [
                channel(0, "Root"),
                channel(1, "beta", parentId: 0, position: 1),
                channel(2, "Alpha", parentId: 0, position: 1),
                channel(3, "Zulu", parentId: 0, position: 0),
            ],
            people: [person(2, "bob"), person(1, "Alice"), person(3, "Carl", channelId: 3)]
        ))
        XCTAssertEqual(root.children.map(\.channel.name), ["Zulu", "Alpha", "beta"])
        XCTAssertEqual(root.people.map(\.name), ["Alice", "bob"])
        XCTAssertEqual(root.children.first?.people.map(\.name), ["Carl"])
    }

    func testChannelsThatDoNotHangOnTheRootAndTheirPeopleAreLeftOut() throws {
        let root = try XCTUnwrap(buildChannelTree(
            channels: [
                channel(0, "Root"),
                // Two channels that name each other as parent, and one whose parent is gone.
                channel(5, "Loop A", parentId: 6),
                channel(6, "Loop B", parentId: 5),
                channel(7, "Orphan", parentId: 99),
            ],
            people: [person(1, "Lost", channelId: 7)]
        ))
        XCTAssertEqual(root.children, [])
        XCTAssertEqual(root.people.map(\.name), [])
    }
}
