// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

final class LinkConfirmStateTests: XCTestCase {
    private let servers = [savedServer(1, "Nordlicht", username: "Anna")]

    func testALinkToASavedServerReusesItsEntryAndItsUserName() {
        let link = MumbleLink(address: "NORDLICHT.example", port: 64738, username: "Someone", channelPath: ["Lobby"])

        let state = linkConfirmState(link: link, servers: servers)

        XCTAssertEqual(state.savedServer, servers.first)
        XCTAssertEqual(state.username, "Anna")
        XCTAssertFalse(state.willAddServer)
        XCTAssertFalse(state.asksForUsername)
        XCTAssertTrue(state.canConnect)
    }

    func testTheSameAddressOnAnotherPortIsAnotherServer() {
        let state = linkConfirmState(
            link: MumbleLink(address: "nordlicht.example", port: 40000, username: "Ben", channelPath: []),
            servers: servers
        )

        XCTAssertNil(state.savedServer)
        XCTAssertTrue(state.willAddServer)
        XCTAssertEqual(state.username, "Ben")
        XCTAssertEqual(
            state.toInput(),
            ServerInput(
                name: "",
                address: "nordlicht.example",
                port: 40000,
                username: "Ben",
                password: .keep,
                identityId: nil
            )
        )
    }

    func testALinkWithoutUserNameAsksForOneBeforeItCanConnect() {
        var state = linkConfirmState(
            link: MumbleLink(address: "new.example", port: 64738, username: nil, channelPath: []),
            servers: servers
        )

        XCTAssertTrue(state.asksForUsername)
        XCTAssertFalse(state.canConnect)
        state.typedUsername = " Ben "
        XCTAssertTrue(state.canConnect)
        XCTAssertEqual(state.toInput().username, "Ben")
    }

    func testNothingCanBeConfirmedTwiceWhileItIsWorking() {
        var state = linkConfirmState(
            link: MumbleLink(address: "new.example", port: 64738, username: "Ben", channelPath: []),
            servers: servers
        )

        state.working = true

        XCTAssertFalse(state.canConnect)
    }
}
