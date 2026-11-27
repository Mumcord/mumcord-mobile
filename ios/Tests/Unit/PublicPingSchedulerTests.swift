// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest
@testable import Mumcord

/// Which listed servers are pinged and when, as `PublicPingSchedulerTest` on Android. The pings
/// wait until the test answers them, so the test sees how many are on their way at once.
@MainActor
final class PublicPingSchedulerTests: XCTestCase {
    private let servers = (0..<10).map { publicServer("Server \($0)", "s\($0).example") }
    private var waiting: [String: CheckedContinuation<PublicPing, Never>] = [:]
    private var asked: [String] = []
    private var results: [(key: String, ping: PublicPing)] = []

    override func tearDown() async throws {
        // Pings still waiting end, so no task is left behind.
        for continuation in waiting.values { continuation.resume(returning: .noAnswer) }
        waiting = [:]
        await settle()
    }

    private func scheduler(concurrency: Int = 4) -> PublicPingScheduler {
        PublicPingScheduler(
            concurrency: concurrency,
            ping: { [unowned self] server in
                asked.append(server.host)
                return await withCheckedContinuation { waiting[server.key] = $0 }
            },
            onResult: { [unowned self] key, ping in results.append((key, ping)) }
        )
    }

    /// Lets the pings' tasks run up to their next wait.
    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
    }

    private func answer(_ index: Int, _ ping: PublicPing = .noAnswer) async {
        guard let continuation = waiting.removeValue(forKey: servers[index].key) else {
            return XCTFail("server \(index) is not being pinged")
        }
        continuation.resume(returning: ping)
        await settle()
    }

    func testNothingIsPingedBeforeItIsStarted() async {
        let scheduler = scheduler()
        scheduler.setVisible(Array(servers.prefix(3)))
        await settle()
        XCTAssertTrue(asked.isEmpty)

        scheduler.start()
        await settle()
        XCTAssertEqual(asked, ["s0.example", "s1.example", "s2.example"])
    }

    func testOnlyTheVisibleRowsArePingedAFewAtATimeTopToBottom() async {
        let scheduler = scheduler(concurrency: 2)
        scheduler.start()
        scheduler.setVisible(Array(servers.prefix(4)))
        await settle()
        XCTAssertEqual(asked, ["s0.example", "s1.example"])

        await answer(0, .answered(people: 3, maxPeople: 10, latencyMs: 20))
        XCTAssertEqual(asked, ["s0.example", "s1.example", "s2.example"])
        XCTAssertEqual(results.map(\.key), [servers[0].key])
        XCTAssertEqual(results.map(\.ping), [.answered(people: 3, maxPeople: 10, latencyMs: 20)])
    }

    func testAtMostFourAreOnTheirWayAtOnce() async {
        let scheduler = scheduler()
        scheduler.start()
        scheduler.setVisible(servers)
        await settle()
        XCTAssertEqual(asked.count, publicPingConcurrency)
        XCTAssertEqual(publicPingConcurrency, 4)
    }

    func testRowsThatScrolledAwayBeforeTheirTurnAreNotPinged() async {
        let scheduler = scheduler(concurrency: 1)
        scheduler.start()
        scheduler.setVisible(Array(servers.prefix(3)))
        await settle()
        scheduler.setVisible(Array(servers.dropFirst(5).prefix(2)))
        await answer(0)

        XCTAssertEqual(asked, ["s0.example", "s5.example"])
    }

    func testEachServerIsPingedOncePerLoadAndAgainAfterAReset() async {
        let scheduler = scheduler()
        scheduler.start()
        scheduler.setVisible(Array(servers.prefix(2)))
        await settle()
        await answer(0)
        await answer(1)
        scheduler.setVisible(Array(servers.prefix(2)))
        await settle()
        XCTAssertEqual(asked.count, 2)

        scheduler.reset()
        await settle()
        XCTAssertEqual(asked, ["s0.example", "s1.example", "s0.example", "s1.example"])
    }

    func testStoppingDropsThePingsOnTheirWaySendsNothingMoreAndStartingAsksThemAgain() async {
        let scheduler = scheduler(concurrency: 2)
        scheduler.start()
        scheduler.setVisible(Array(servers.prefix(4)))
        await settle()
        scheduler.stop()
        await settle()
        for continuation in waiting.values { continuation.resume(returning: .noAnswer) }
        waiting = [:]
        await settle()
        XCTAssertEqual(asked.count, 2)
        XCTAssertTrue(results.isEmpty, "no answer arrives after stopping")

        scheduler.setVisible(Array(servers.prefix(4)))
        await settle()
        XCTAssertEqual(asked.count, 2, "nothing is sent while stopped")

        scheduler.start()
        await settle()
        XCTAssertEqual(asked, ["s0.example", "s1.example", "s0.example", "s1.example"])
    }

    func testTheAnswerOfACancelledPingDoesNotEndTheNewPingOfTheSameServer() async {
        let scheduler = scheduler(concurrency: 1)
        scheduler.start()
        scheduler.setVisible([servers[0]])
        await settle()
        let first = waiting.removeValue(forKey: servers[0].key)
        scheduler.reset()
        await settle()
        XCTAssertEqual(asked, ["s0.example", "s0.example"])

        // The first ping answers late: it is dropped, and the second is still on its way.
        first?.resume(returning: .answered(people: 1, maxPeople: 2, latencyMs: 3))
        await settle()
        XCTAssertTrue(results.isEmpty)
        await answer(0, .noAnswer)
        XCTAssertEqual(results.map(\.ping), [.noAnswer])
    }
}
