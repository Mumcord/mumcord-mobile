// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

final class ServerListStateTests: XCTestCase {
    private let servers = [
        savedServer(1, "Nordlicht"),
        savedServer(2, "Lantern Guild", address: "lanternguild.example", port: 40000),
    ]

    func testRowsShowNameAddressPingAndHighlightTheFirstServer() {
        let rows = serverRows(
            session: SessionState(servers: servers, serversLoaded: true),
            pings: [1: .online(peopleOnline: 5, latencyMs: 24), 2: .offline]
        )

        XCTAssertEqual(
            rows,
            [
                ServerRow(
                    id: 1,
                    name: "Nordlicht",
                    initials: "N",
                    address: "nordlicht.example",
                    ping: .online(peopleOnline: 5, latencyMs: 24),
                    connecting: false,
                    highlighted: true
                ),
                ServerRow(
                    id: 2,
                    name: "Lantern Guild",
                    initials: "LG",
                    address: "lanternguild.example:40000",
                    ping: .offline,
                    connecting: false,
                    highlighted: false
                ),
            ]
        )
    }

    func testAServerThatWasNotAskedYetHasNoPingState() {
        let rows = serverRows(session: SessionState(servers: servers, serversLoaded: true), pings: [:])

        XCTAssertEqual(rows.map(\.ping), [nil, nil])
    }

    func testOnlyTheServerOfALiveConnectionIsConnecting() {
        func connecting(_ phase: ConnectionPhase) -> [Bool] {
            serverRows(session: SessionState(phase: phase, servers: servers, activeServerId: 2), pings: [:])
                .map(\.connecting)
        }

        XCTAssertEqual(connecting(.connecting), [false, true])
        XCTAssertEqual(connecting(.failed(.banned)), [false, false])
        XCTAssertEqual(connecting(.idle), [false, false])
    }

    func testAPingAnswerBecomesOnlineAndSilenceBecomesOffline() {
        XCTAssertEqual(
            PingState(ServerPing(latencyMs: 31, peopleOnline: 12, maxPeople: 100)),
            .online(peopleOnline: 12, latencyMs: 31)
        )
        XCTAssertEqual(PingState(nil), .offline)
    }

    func testInitialsAreTheFirstLettersOfTheFirstTwoWords() {
        XCTAssertEqual(initials("Nordlicht"), "N")
        XCTAssertEqual(initials("  lantern   guild hall "), "LG")
        XCTAssertEqual(initials("ärger österreich"), "ÄÖ")
        XCTAssertEqual(initials("😀smile"), "😀")
        XCTAssertEqual(initials("   "), "?")
    }
}
