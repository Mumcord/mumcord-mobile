// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// Which moderation entries show, the ban form and what a failure says.
final class ModerationTests: XCTestCase {
    private func model(_ state: ServerState = mockupServer()) -> ServerModel {
        SessionState().fold(.serverSynced(server: state)).server!
    }

    private func rows(_ server: ServerModel, _ session: UInt32) throws -> ModerationRows {
        server.moderationRows(try XCTUnwrap(server.people[session]))
    }

    private func admin() -> ServerState {
        mockupServer().granting { $0.grantAllRights() }
    }

    private func target(
        address: String? = "192.0.2.7",
        banListReadable: Bool = true,
        hasCertificate: Bool = true,
        canKick: Bool = true
    ) -> BanTarget {
        BanTarget(
            address: address,
            onlineSecs: 90,
            banListReadable: banListReadable,
            hasCertificate: hasCertificate,
            canKick: canKick,
            canBan: true
        )
    }

    func testWithoutAnyRightTheSectionIsAbsent() throws {
        let rows = try rows(model(), Mockup.jonas)
        XCTAssertEqual(rows, ModerationRows())
        XCTAssertFalse(rows.any)
    }

    func testTheServerAdminSeesEveryEntry() throws {
        XCTAssertEqual(
            try rows(model(admin()), Mockup.jonas),
            ModerationRows(move: true, serverMute: true, serverMuted: false, kick: true, ban: true)
        )
    }

    func testMoveAndMuteFollowThePersonsChannelKickAndBanTheRoot() throws {
        // A channel admin of Gaming: move and mute there, nothing on the root.
        let channelAdmin = model(mockupServer().granting { channel in
            guard channel.id == Mockup.gaming else { return }
            channel.permissions.movePeople = true
            channel.permissions.muteDeafen = true
        })
        XCTAssertEqual(try rows(channelAdmin, Mockup.jonas), ModerationRows(move: true, serverMute: true))
        // Jonas's channel decides, not the user's own: nothing for Ben in the Lobby.
        XCTAssertEqual(try rows(channelAdmin, Mockup.ben), ModerationRows())

        // Kick and ban granted on the root only: they show for a person in any channel.
        let rootOnly = model(mockupServer().granting { channel in
            guard channel.id == Mockup.root else { return }
            channel.permissions.kick = true
            channel.permissions.ban = true
        })
        XCTAssertEqual(try rows(rootOnly, Mockup.jonas), ModerationRows(kick: true, ban: true))
        XCTAssertEqual(try rows(rootOnly, Mockup.ben), ModerationRows(kick: true, ban: true))
    }

    func testAServerMutedPersonGetsTheEntryThatLiftsIt() throws {
        var server = admin()
        server.people = server.people.map { person in
            var changed = person
            if person.session == Mockup.jonas { changed.serverMuted = true }
            return changed
        }
        XCTAssertTrue(try rows(model(server), Mockup.jonas).serverMuted)
    }

    func testMuteIsNotOfferedInATemporaryChannel() throws {
        let server = mockupServer().granting { channel in
            channel.grantAllRights()
            if channel.id == Mockup.gaming { channel.temporary = true }
        }
        let rows = try rows(model(server), Mockup.jonas)
        XCTAssertFalse(rows.serverMute)
        XCTAssertTrue(rows.move && rows.kick && rows.ban)
    }

    func testTheUserThemselvesHasNoModeration() throws {
        XCTAssertEqual(try rows(model(admin()), Mockup.lea), ModerationRows())
    }

    func testTheMovePickerOffersEveryChannelButThePersonsOwn() throws {
        let server = model()
        let jonas = try XCTUnwrap(server.people[Mockup.jonas])
        guard case .move(let name, let choices) = moderationSheet(
            server: server,
            person: jonas,
            local: .move(session: Mockup.jonas)
        ) else { return XCTFail("not the channel picker") }
        XCTAssertEqual(name, "Jonas")
        XCTAssertEqual(choices.map(\.name), ["Nordlicht", "Lobby", "Music", "AFK"])
        XCTAssertFalse(choices.contains { $0.id == Mockup.gaming })
    }

    func testABanSendsTheAddressAlwaysAndTheCertificateWhenThePersonHasOne() {
        var form = BanForm(reason: "  spam  ", durationSecs: 86_400, byCertificate: true, removeNow: true)
        XCTAssertEqual(
            banInput(target: target(), form: form, force: false),
            BanInput(
                reason: "spam",
                durationSecs: 86_400,
                byCertificate: true,
                byAddress: true,
                removeNow: true,
                force: false,
                quick: false
            )
        )
        let noCertificate = banInput(target: target(hasCertificate: false), form: form, force: true)
        XCTAssertFalse(noCertificate.byCertificate)
        XCTAssertTrue(noCertificate.byAddress && noCertificate.force)
        XCTAssertFalse(target(hasCertificate: false).certificateChoice)
        form.byCertificate = false
        XCTAssertFalse(banInput(target: target(), form: form, force: false).byCertificate)
    }

    func testABlankReasonIsNoneAndRemovingNeedsTheKickRight() {
        let input = banInput(target: target(canKick: false), form: BanForm(reason: "   "), force: false)
        XCTAssertNil(input.reason)
        XCTAssertFalse(input.removeNow)
        XCTAssertFalse(target(canKick: false).removeNowChoice)
        XCTAssertTrue(target().removeNowChoice && target().certificateChoice)
    }

    func testWithoutAReadableBanListOrAnAddressOnlyTheServersDefaultBanIsPossible() {
        for quick in [target(banListReadable: false), target(address: nil)] {
            XCTAssertTrue(quick.quick)
            XCTAssertFalse(quick.certificateChoice || quick.removeNowChoice)
            let form = BanForm(reason: "bye", durationSecs: 3_600, byCertificate: false, removeNow: false)
            XCTAssertEqual(
                banInput(target: quick, form: form, force: true, durationSecs: 10),
                BanInput(
                    reason: "bye",
                    durationSecs: 0,
                    byCertificate: true,
                    byAddress: true,
                    removeNow: true,
                    force: false,
                    quick: true
                )
            )
        }
    }

    func testATestMayShortenTheLength() {
        let form = BanForm(durationSecs: 3_600)
        XCTAssertEqual(banInput(target: target(), form: form, force: false, durationSecs: 10).durationSecs, 10)
        XCTAssertEqual(banInput(target: target(), form: form, force: false).durationSecs, 3_600)
    }

    func testTheBanLengthOverrideComesFromTheLaunchArgumentOnly() throws {
        let name = "ModerationTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        XCTAssertNil(TestSettings.banSeconds(defaults))
        defaults.set("15", forKey: "mumcordBanSeconds")
        XCTAssertEqual(TestSettings.banSeconds(defaults), 15)
        defaults.set("-3", forKey: "mumcordBanSeconds")
        XCTAssertNil(TestSettings.banSeconds(defaults))
        defaults.set("soon", forKey: "mumcordBanSeconds")
        XCTAssertNil(TestSettings.banSeconds(defaults))
    }

    func testTheBanLengthsAreThoseOfTheDesktop() {
        XCTAssertEqual(banDurationsSecs, [3_600, 86_400, 7 * 86_400, 30 * 86_400, 0])
        XCTAssertEqual(BanForm().durationSecs, 3_600)
    }

    func testTheBanEndsAfterTheChosenLengthFromWhenTheSheetOpened() {
        let opened = Date(timeIntervalSince1970: 1_000)
        var sheet = BanSheetState(
            name: "Jonas",
            channelName: "Gaming",
            target: target(),
            form: BanForm(durationSecs: 3_600),
            openedAt: opened,
            busy: false,
            failure: nil,
            conflict: false
        )
        XCTAssertEqual(sheet.endsAt, Date(timeIntervalSince1970: 4_600))
        XCTAssertFalse(sheet.loading)
        sheet.form.durationSecs = 0
        XCTAssertNil(sheet.endsAt)
        sheet.form.durationSecs = 3_600
        sheet.target = target(banListReadable: false)
        XCTAssertNil(sheet.endsAt)
        sheet.target = nil
        XCTAssertNil(sheet.endsAt)
        XCTAssertTrue(sheet.loading)
        sheet.failure = .failed
        XCTAssertFalse(sheet.loading)
    }

    func testARefusalKeepsTheServersTextAsPlainCharacters() {
        // Control and format characters (a bell, a right-to-left override) could hide or reorder it.
        XCTAssertEqual(
            moderationFailure(.permissionDenied, refusal: " No\u{07} kicking\u{202E} here "),
            .refused(serverText: "No kicking here")
        )
        XCTAssertEqual(moderationFailure(.permissionDenied), .refused(serverText: ""))
        guard case .refused(let long) = moderationFailure(.permissionDenied, refusal: String(repeating: "x", count: 500))
        else { return XCTFail("not a refusal") }
        XCTAssertEqual(long.count, 200)
        XCTAssertEqual(moderationFailure(.notFound), .gone)
        XCTAssertEqual(moderationFailure(.notConnected), .disconnected)
        XCTAssertEqual(moderationFailure(.internalError, refusal: "ignored"), .failed)
    }
}
