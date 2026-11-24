// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest
@testable import Mumcord

/// The rules of the public server browser, as `PublicServersStateTest` on Android.
final class PublicServersStateTests: XCTestCase {
    private let nordic = publicServer("Nordic Friends", "voice.example.org", country: "Sweden", code: "SE")
    private let berlin = publicServer("berlin Gaming", "mumble.example.de", port: 40000)
    private let alpine = publicServer("Alpine Lounge", "alps.example.ch", country: "Switzerland", code: "CH")
    private let nowhere = publicServer("Somewhere", "somewhere.example", country: "", code: "")
    private var list: [PublicServer] { [nordic, berlin, alpine, nowhere] }

    func testSearchMatchesNameAddressAndCountryWhateverTheCase() {
        XCTAssertTrue(nordic.matches("FRIENDS", countryFilter: nil))
        XCTAssertTrue(nordic.matches("voice.example", countryFilter: nil))
        XCTAssertTrue(nordic.matches("swe", countryFilter: nil))
        XCTAssertTrue(nordic.matches("  ", countryFilter: nil))
        XCTAssertFalse(nordic.matches("berlin", countryFilter: nil))
    }

    func testTheCountryFilterKeepsOnlyThatCountry() {
        XCTAssertTrue(berlin.matches("", countryFilter: "de"))
        XCTAssertFalse(nordic.matches("", countryFilter: "DE"))
        XCTAssertFalse(nowhere.matches("", countryFilter: "DE"))
    }

    func testCountriesAreListedOnceByNameAndServersWithoutACountryAreLeftOut() {
        let list = list + [publicServer("Munich", "munich.example"), publicServer("Odd", "odd.example", country: "", code: "NO")]

        XCTAssertEqual(
            countriesOf(list),
            [
                PublicCountry(code: "DE", name: "Germany"),
                PublicCountry(code: "NO", name: "NO"),
                PublicCountry(code: "SE", name: "Sweden"),
                PublicCountry(code: "CH", name: "Switzerland"),
            ]
        )
    }

    func testASavedServerMatchesByAddressWhateverTheCaseAndByPort() {
        let saved = [
            savedServer(1, "Mine", address: "VOICE.example.org"),
            savedServer(2, "Other port", address: "mumble.example.de"),
        ]

        XCTAssertEqual(savedMatch(saved, nordic)?.id, 1)
        XCTAssertNil(savedMatch(saved, berlin))
    }

    func testRowsAreSortedByNameAndCarryAddressCountryPingAndTheSavedServer() {
        var local = PublicServersLocal(servers: list)
        local.pings = [nordic.key: .answered(people: 12, maxPeople: 50, latencyMs: 34), berlin.key: .noAnswer]
        local.adding = [alpine.key]
        let state = publicServersUiState(local, saved: [savedServer(9, "Mine", address: "voice.example.org")])

        XCTAssertEqual(state.status, .ready)
        XCTAssertEqual(state.total, 4)
        XCTAssertEqual(
            state.rows,
            [
                PublicServerRow(key: alpine.key, name: "Alpine Lounge", initials: "AL", address: "alps.example.ch", country: "Switzerland", ping: nil, savedId: nil, adding: true),
                PublicServerRow(key: berlin.key, name: "berlin Gaming", initials: "BG", address: "mumble.example.de:40000", country: "Germany", ping: .noAnswer, savedId: nil, adding: false),
                PublicServerRow(key: nordic.key, name: "Nordic Friends", initials: "NF", address: "voice.example.org", country: "Sweden", ping: .answered(people: 12, maxPeople: 50, latencyMs: 34), savedId: 9, adding: false),
                PublicServerRow(key: nowhere.key, name: "Somewhere", initials: "S", address: "somewhere.example", country: "", ping: nil, savedId: nil, adding: false),
            ]
        )
    }

    func testSearchAndCountryNarrowTheRowsButNotTheTotal() {
        let state = publicServersUiState(PublicServersLocal(servers: list, search: "example", country: "SE"), saved: [])

        XCTAssertEqual(state.rows.map(\.name), ["Nordic Friends"])
        XCTAssertEqual(state.total, 4)
        XCTAssertEqual(state.country, "SE")
    }

    func testTheStatusFollowsTheLoad() {
        func status(_ local: PublicServersLocal) -> PublicListStatus {
            publicServersUiState(local, saved: []).status
        }

        XCTAssertEqual(status(PublicServersLocal(loading: true)), .loading)
        XCTAssertEqual(status(PublicServersLocal(loadFailed: true)), .failed)
        XCTAssertEqual(status(PublicServersLocal(loading: true, loadFailed: true)), .loading)
        XCTAssertEqual(status(PublicServersLocal(servers: [])), .empty)
        XCTAssertEqual(status(PublicServersLocal(servers: list, search: "nothing like it")), .noMatch)
    }

    func testARefreshOverAShownListKeepsTheList() {
        let refreshing = publicServersUiState(PublicServersLocal(servers: list, loading: true), saved: [])
        XCTAssertEqual(refreshing.status, .ready)
        XCTAssertTrue(refreshing.refreshing)

        let failed = publicServersUiState(PublicServersLocal(servers: list, refreshFailed: true), saved: [])
        XCTAssertEqual(failed.rows.count, 4)
        XCTAssertTrue(failed.refreshFailed)
    }

    func testAPingBecomesAnAnswerOrNoAnswer() {
        XCTAssertEqual(PublicPing(ServerPing(latencyMs: 41, peopleOnline: 3, maxPeople: 20)), .answered(people: 3, maxPeople: 20, latencyMs: 41))
        XCTAssertEqual(PublicPing(nil), .noAnswer)
    }

    func testTheKeyIgnoresTheCaseOfTheHost() {
        XCTAssertEqual(publicServer("A", "Voice.Example.org").key, publicServer("B", "voice.example.ORG").key)
    }

    func testTheRowsOnScreenAreThoseInsideTheVisibleHeightTopToBottom() {
        let frames: [String: CGRect] = [
            "below": CGRect(x: 0, y: 600, width: 300, height: 76),
            "second": CGRect(x: 0, y: 76, width: 300, height: 76),
            "above": CGRect(x: 0, y: -76, width: 300, height: 76),
            "first": CGRect(x: 0, y: -20, width: 300, height: 76),
            "cut-at-the-bottom": CGRect(x: 0, y: 560, width: 300, height: 76),
        ]

        XCTAssertEqual(visibleRowKeys(frames, visibleHeight: 600), ["first", "second", "cut-at-the-bottom"])
    }

    func testTheTextsOfTheBrowserAreTheAndroidOnes() {
        XCTAssertEqual(Strings.publicCountAll(1), "1 server")
        XCTAssertEqual(Strings.publicCountShown(2, 6), "2 of 6 servers")
        XCTAssertEqual(Strings.publicPing(12, 50, 34), "12 / 50 · 34 ms")
        XCTAssertEqual(Strings.publicPingDescription(12, 50, 34), "12 of 50 people online, 34 ms")
        XCTAssertEqual(Strings.publicAddressCountry("voice.example.org", "Germany"), "voice.example.org · Germany")
        XCTAssertEqual(Strings.publicAddDescription("Nordic Friends"), "Add Nordic Friends to your servers")
    }
}
