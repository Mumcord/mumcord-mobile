// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// Which server addresses count as local network, and what follows before a connect.
final class LocalNetworkRuleTests: XCTestCase {
    private func v4(_ a: UInt8, _ b: UInt8, _ c: UInt8, _ d: UInt8) -> IpAddress {
        IpAddress(bytes: [a, b, c, d])
    }

    private func v6(_ head: [UInt8]) -> IpAddress {
        IpAddress(bytes: head + [UInt8](repeating: 0, count: 15 - head.count) + [1])
    }

    private var wifi: [IpPrefix] { [IpPrefix(address: v4(192, 168, 1, 37), length: 24)] }

    func testAnAddressOnTheDevicesOwnNetworkIsOnLink() {
        XCTAssertEqual(localityOf(addresses: [v4(192, 168, 1, 20)], onLink: wifi), .onLink)
        let home = [IpPrefix(address: v6([0x20, 0x01, 0x0D, 0xB8, 0, 1, 0, 2]), length: 64)]
        XCTAssertEqual(localityOf(addresses: [v6([0x20, 0x01, 0x0D, 0xB8, 0, 1, 0, 2])], onLink: home), .onLink)
        XCTAssertEqual(localityOf(addresses: [v6([0x20, 0x01, 0x0D, 0xB8, 0, 1, 0, 3])], onLink: home), .other)
    }

    func testAPrefixThatDoesNotEndOnAByteIsCompared() {
        let prefix = IpPrefix(address: v4(10, 20, 16, 1), length: 20)
        XCTAssertTrue(prefix.contains(v4(10, 20, 31, 255)))
        XCTAssertFalse(prefix.contains(v4(10, 20, 32, 0)))
        XCTAssertFalse(prefix.contains(v6([0x0A, 0x14, 0x10])))
        XCTAssertTrue(IpPrefix(address: v4(1, 2, 3, 4), length: 32).contains(v4(1, 2, 3, 4)))
        XCTAssertTrue(IpPrefix(address: v4(1, 2, 3, 4), length: 0).contains(v4(9, 9, 9, 9)))
    }

    func testAPrivateAddressBehindARouterIsOnlyPrivate() {
        for address in [v4(10, 0, 0, 5), v4(172, 20, 1, 1), v4(192, 168, 50, 2), v4(100, 64, 3, 4), v6([0xFD, 0x12])] {
            XCTAssertEqual(localityOf(addresses: [address], onLink: wifi), .privateRange)
        }
    }

    func testInternetAddressesAreNeitherLocalNorPrivate() {
        for address in [v4(8, 8, 8, 8), v4(172, 32, 0, 1), v4(127, 0, 0, 1), v6([0x20, 0x01, 0x0D, 0xB8])] {
            XCTAssertEqual(localityOf(addresses: [address], onLink: wifi), .other)
        }
        // The loopback is the device itself, also when a network claims it.
        let loopback = [IpPrefix(address: v4(127, 0, 0, 1), length: 8)]
        XCTAssertEqual(localityOf(addresses: [v4(127, 0, 0, 1)], onLink: loopback), .other)
        XCTAssertEqual(localityOf(addresses: [IpAddress(bytes: [UInt8](repeating: 0, count: 15) + [1])], onLink: []), .other)
        XCTAssertEqual(localityOf(addresses: [], onLink: wifi), .other)
    }

    func testLinkLocalMulticastAndBroadcastAreOnLinkWithoutAnyNetwork() {
        for address in [v4(169, 254, 3, 4), v4(224, 0, 0, 251), v4(255, 255, 255, 255), v6([0xFE, 0x80]), v6([0xFF, 0x02])] {
            XCTAssertEqual(localityOf(addresses: [address], onLink: []), .onLink)
        }
    }

    func testTheMostLocalOfSeveralAddressesDecides() {
        XCTAssertEqual(localityOf(addresses: [v4(8, 8, 8, 8), v4(10, 0, 0, 5)], onLink: wifi), .privateRange)
        XCTAssertEqual(localityOf(addresses: [v4(10, 0, 0, 5), v4(192, 168, 1, 20)], onLink: wifi), .onLink)
    }

    func testNamesEndingInLocalAreLocalNames() {
        XCTAssertTrue(isLocalName("nas.local"))
        XCTAssertTrue(isLocalName("NAS.Local."))
        XCTAssertFalse(isLocalName("local.example.org"))
        XCTAssertFalse(isLocalName("localhost"))
        XCTAssertFalse(isLocalName("192.168.1.20"))
    }

    // MARK: Before a connect

    private func gateway(explained: Bool = false) -> FakeLocalNetworkGateway {
        FakeLocalNetworkGateway(
            prefixes: wifi,
            addresses: [
                "home.example": [v4(192, 168, 1, 20)],
                "vpn.example": [v4(10, 8, 0, 3)],
                "mumble.example": [v4(203, 0, 113, 9)],
            ],
            explained: explained
        )
    }

    func testALocalServerIsExplainedOnceByAddressAndByName() async {
        let first = await gateway().check(host: "home.example")
        XCTAssertEqual(first, LocalNetworkCheck(step: .explain, suspect: true))
        let later = await gateway(explained: true).check(host: "home.example")
        XCTAssertEqual(later, LocalNetworkCheck(step: .connect, suspect: true))
    }

    func testALocalNameIsExplainedWithoutBeingResolved() async {
        let check = await gateway().check(host: "nas.local")
        XCTAssertEqual(check, LocalNetworkCheck(step: .explain, suspect: true))
    }

    func testAnInternetServerConnectsWithoutAnyQuestion() async {
        let check = await gateway().check(host: "mumble.example")
        XCTAssertEqual(check, LocalNetworkCheck(step: .connect, suspect: false))
    }

    func testAPrivateAddressBehindARouterConnectsButIsRememberedAsSuspect() async {
        let check = await gateway().check(host: "vpn.example")
        XCTAssertEqual(check, LocalNetworkCheck(step: .connect, suspect: true))
    }

    func testANameThatDoesNotResolveConnectsAsBefore() async {
        let check = await gateway().check(host: "unknown.example")
        XCTAssertEqual(check, LocalNetworkCheck(step: .connect, suspect: false))
    }
}
