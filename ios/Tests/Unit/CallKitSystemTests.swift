// SPDX-License-Identifier: AGPL-3.0-only

import CallKit
import XCTest

@testable import Mumcord

/// The real call service of the system, as far as a simulator has one: it can be created and
/// a call can be asked for and ended without the app falling over. Everything the system does
/// with a call (its screen, holding it for a phone call) needs a real phone.
@MainActor
final class CallKitSystemTests: XCTestCase {
    func testTheConfigurationKeepsSessionsOutOfTheRecentCalls() {
        let configuration = CallKitSystem.configuration()
        XCTAssertFalse(configuration.includesCallsInRecents)
        XCTAssertFalse(configuration.supportsVideo)
        XCTAssertEqual(configuration.maximumCallGroups, 1)
        XCTAssertEqual(configuration.maximumCallsPerCallGroup, 1)
        XCTAssertEqual(configuration.supportedHandleTypes, [.generic])
    }

    func testACallCanBeStartedAndEndedInTheSimulator() async throws {
        var log: [String] = []
        let system = CallKitSystem(log: { log.append($0) })
        let call = UUID()
        let answered = XCTestExpectation(description: "the system answers the request for a call")
        var accepted = false
        system.start(call, name: "Test") {
            accepted = $0
            answered.fulfill()
        }
        let outcome = await XCTWaiter().fulfillment(of: [answered], timeout: 20)
        try XCTSkipUnless(outcome == .completed, "skipped: the simulator's call service did not answer")
        try XCTSkipUnless(accepted, "skipped: the simulator's call service refused the call: \(log)")

        system.rename(call, name: "Test · Renamed")
        system.setMuted(call, muted: true)
        system.end(call)
        // Lets the system work through the requests; whatever it answers must not crash.
        try await Task.sleep(nanoseconds: 2_000_000_000)
    }
}
