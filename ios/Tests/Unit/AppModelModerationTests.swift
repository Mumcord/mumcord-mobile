// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// Move, server mute, kick and ban from the person sheet, against the fake engine as server admin.
@MainActor
final class AppModelModerationTests: XCTestCase {
    private let box = EngineBox()

    private var engine: FakeEngine { box.engine }

    /// Connected as server admin, with Jonas's sheet open.
    private func jonasSheet(configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }) async -> AppModel {
        let store = makeStore(
            box,
            server: mockupServer().granting { $0.grantAllRights() },
            servers: [savedServer(1, "Nordlicht")],
            configure: configure
        )
        let model = AppModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected != nil }
        model.openPerson(Mockup.jonas)
        return model
    }

    private func sheet(_ model: AppModel) -> ConnectedSheet? {
        model.uiState.connected?.sheet
    }

    private func rows(_ model: AppModel) -> ModerationRows? {
        if case .person(let person) = sheet(model) { return person.moderation }
        return nil
    }

    private func kickSheet(_ model: AppModel) -> KickSheetState? {
        if case .moderation(.kick(let kick)) = sheet(model) { return kick }
        return nil
    }

    private func banSheet(_ model: AppModel) -> BanSheetState? {
        if case .moderation(.ban(let ban)) = sheet(model) { return ban }
        return nil
    }

    private func jonasIsThere(_ model: AppModel) -> Bool {
        model.uiState.session.server?.people[Mockup.jonas] != nil
    }

    func testMovingClosesTheSheetsAndMovesThePerson() async throws {
        let model = await jonasSheet()
        XCTAssertEqual(rows(model)?.move, true)
        model.openMove()
        guard case .moderation(.move(let name, let choices)) = sheet(model) else {
            return XCTFail("not the channel picker")
        }
        XCTAssertEqual(name, "Jonas")
        XCTAssertTrue(choices.contains { $0.id == Mockup.afk })

        model.movePerson(to: Mockup.afk)

        XCTAssertNil(sheet(model))
        await assertEventually { model.uiState.session.server?.people[Mockup.jonas]?.channelId == Mockup.afk }
        XCTAssertEqual(engine.moderationCalls, ["move \(Mockup.jonas) \(Mockup.afk)"])
        XCTAssertNil(model.uiState.connected?.denied)
    }

    func testGoingBackFromAStepShowsThePersonSheetAgain() async throws {
        let model = await jonasSheet()
        for open in [model.openMove, model.openKick, model.openBan] {
            open()
            XCTAssertEqual(sheet(model)?.isModeration, true)
            model.dismissSheet()
            XCTAssertNotNil(rows(model), "back on the person sheet")
        }
        model.dismissSheet()
        XCTAssertNil(sheet(model))
    }

    func testServerMuteTogglesAndTheEntryFollowsTheServer() async throws {
        let model = await jonasSheet()
        XCTAssertEqual(rows(model)?.serverMuted, false)
        model.toggleServerMute()
        await assertEventually { self.rows(model)?.serverMuted == true }
        model.toggleServerMute()
        await assertEventually { self.rows(model)?.serverMuted == false }
        XCTAssertEqual(engine.moderationCalls, ["mute \(Mockup.jonas) true", "mute \(Mockup.jonas) false"])
    }

    func testAMuteThatFailsClosesTheSheetSoTheNoticeBehindItShows() async throws {
        let model = await jonasSheet { $0.moderationFailure = .Internal(detail: "broken") }
        model.toggleServerMute()
        await assertEventually { self.sheet(model) == nil && model.uiState.connected?.denied != nil }
        XCTAssertEqual(model.uiState.connected?.denied, DeniedBanner(channelName: nil, detail: ""))
    }

    func testARefusedMuteOrMoveShowsTheServersRefusalBehindTheSheet() async throws {
        let model = await jonasSheet { $0.moderationFailure = .PermissionDenied(detail: "Not allowed here") }
        model.toggleServerMute()
        await assertEventually { self.sheet(model) == nil && model.uiState.connected?.denied != nil }
        XCTAssertEqual(model.uiState.connected?.denied, DeniedBanner(channelName: nil, detail: "Not allowed here"))

        model.dismissDenied()
        engine.moderationFailure = .PermissionDenied(detail: "Stay where you are")
        model.openPerson(Mockup.jonas)
        model.openMove()
        model.movePerson(to: Mockup.afk)
        await assertEventually {
            model.uiState.connected?.denied == DeniedBanner(channelName: nil, detail: "Stay where you are")
        }
        XCTAssertEqual(model.uiState.session.server?.people[Mockup.jonas]?.channelId, Mockup.gaming)
        XCTAssertEqual(engine.moderationCalls, [])
    }

    func testAKickSendsTheTrimmedReasonAndClosesTheSheets() async throws {
        let model = await jonasSheet()
        model.openKick()
        XCTAssertEqual(kickSheet(model)?.channelName, "Gaming")
        model.changeKickReason("  cool down ")
        model.confirmKick()
        await assertEventually { self.sheet(model) == nil && !self.jonasIsThere(model) }
        XCTAssertEqual(engine.kickReasons, ["cool down"])
    }

    func testAKickWithoutAReasonSendsNone() async throws {
        let model = await jonasSheet()
        model.openKick()
        model.changeKickReason("   ")
        model.confirmKick()
        await assertEventually { self.engine.kickReasons.count == 1 }
        XCTAssertEqual(engine.kickReasons, [nil])
    }

    func testARefusedKickStaysInTheSheetWithTheServersTextAndCanBeTriedAgain() async throws {
        let model = await jonasSheet { $0.moderationFailure = .PermissionDenied(detail: "Not here") }
        model.openKick()
        model.confirmKick()
        await assertEventually { self.kickSheet(model)?.failure == .refused(serverText: "Not here") }
        XCTAssertEqual(kickSheet(model)?.busy, false)
        XCTAssertTrue(jonasIsThere(model))
        // The refusal is said in the sheet, not a second time behind it.
        XCTAssertNil(model.uiState.connected?.denied)

        model.confirmKick()
        await assertEventually { self.sheet(model) == nil && !self.jonasIsThere(model) }
    }

    func testAPersonWhoLeftMeanwhileIsSaidSo() async throws {
        let model = await jonasSheet { $0.moderationFailure = .NotFound(detail: "no such person") }
        model.openKick()
        model.confirmKick()
        await assertEventually { self.kickSheet(model)?.failure == .gone }
    }

    func testTheBanSheetAsksTheServerFirstThenBansWithTheChosenLength() async throws {
        let model = await jonasSheet()
        model.openBan()
        // Asked for, not yet answered.
        XCTAssertEqual(banSheet(model)?.loading, true)
        await assertEventually { self.banSheet(model)?.target != nil }
        XCTAssertEqual(banSheet(model)?.target, engine.banTargetAnswer)
        XCTAssertEqual(banSheet(model)?.form, BanForm())

        model.selectBanDuration(604_800)
        model.changeBanReason("spam")
        model.setBanRemoveNow(false)
        XCTAssertEqual(
            banSheet(model)?.endsAt,
            banSheet(model)?.openedAt.addingTimeInterval(604_800)
        )
        model.confirmBan()

        await assertEventually { self.sheet(model) == nil }
        XCTAssertEqual(engine.banInputs, [BanInput(
            reason: "spam",
            durationSecs: 604_800,
            byCertificate: true,
            byAddress: true,
            removeNow: false,
            force: false,
            quick: false
        )])
        // Not removed now: the ban applies when they connect again.
        XCTAssertTrue(jonasIsThere(model))
    }

    func testAPersonWithoutACertificateIsBannedByAddressOnly() async throws {
        let model = await jonasSheet { $0.banTargetAnswer.hasCertificate = false }
        model.openBan()
        await assertEventually { self.banSheet(model)?.target != nil }
        XCTAssertEqual(banSheet(model)?.form.byCertificate, false)
        XCTAssertEqual(banSheet(model)?.target?.certificateChoice, false)
        model.confirmBan()
        await assertEventually { self.sheet(model) == nil && !self.jonasIsThere(model) }
        XCTAssertEqual(engine.banInputs.map(\.byCertificate), [false])
        XCTAssertEqual(engine.banInputs.map(\.byAddress), [true])
    }

    func testAServerWhoseBanListCannotBeReadGetsItsDefaultBan() async throws {
        let model = await jonasSheet { $0.banTargetAnswer.banListReadable = false }
        model.openBan()
        await assertEventually { self.banSheet(model)?.target?.quick == true }
        XCTAssertNil(banSheet(model)?.endsAt)
        model.changeBanReason("bye")
        model.confirmBan()
        await assertEventually { self.sheet(model) == nil && !self.jonasIsThere(model) }
        XCTAssertEqual(engine.banInputs, [BanInput(
            reason: "bye",
            durationSecs: 0,
            byCertificate: true,
            byAddress: true,
            removeNow: true,
            force: false,
            quick: true
        )])
    }

    func testAConflictAsksBeforeSavingAnyway() async throws {
        let model = await jonasSheet { $0.banResults = [.conflict] }
        model.openBan()
        await assertEventually { self.banSheet(model)?.target != nil }
        model.confirmBan()
        await assertEventually { self.banSheet(model)?.conflict == true }
        XCTAssertEqual(banSheet(model)?.busy, false)
        XCTAssertTrue(jonasIsThere(model))

        model.banAnyway()
        await assertEventually { self.sheet(model) == nil && !self.jonasIsThere(model) }
        XCTAssertEqual(engine.banInputs.map(\.force), [false, true])
    }

    func testGoingBackFromAConflictKeepsThePersonAndBansNothingMore() async throws {
        let model = await jonasSheet { $0.banResults = [.conflict] }
        model.openBan()
        await assertEventually { self.banSheet(model)?.target != nil }
        model.confirmBan()
        await assertEventually { self.banSheet(model)?.conflict == true }
        model.dismissSheet()
        XCTAssertNotNil(rows(model))
        XCTAssertEqual(engine.banInputs.count, 1)
    }

    func testARefusedBanTargetLeavesOnlyTheRefusal() async throws {
        let model = await jonasSheet { $0.moderationFailure = .PermissionDenied(detail: "") }
        model.openBan()
        await assertEventually { self.banSheet(model)?.failure == .refused(serverText: "") }
        XCTAssertNil(banSheet(model)?.target)
        XCTAssertEqual(banSheet(model)?.loading, false)
        model.confirmBan()
        XCTAssertEqual(engine.banInputs, [])
    }

    func testARefusedBanStaysInTheSheetWithTheServersText() async throws {
        let model = await jonasSheet()
        model.openBan()
        await assertEventually { self.banSheet(model)?.target != nil }
        engine.moderationFailure = .PermissionDenied(detail: "No bans today")
        model.confirmBan()
        await assertEventually { self.banSheet(model)?.failure == .refused(serverText: "No bans today") }
        XCTAssertEqual(banSheet(model)?.busy, false)
        XCTAssertTrue(jonasIsThere(model))
    }

    func testKickOnlyInsteadKeepsTheReason() async throws {
        let model = await jonasSheet()
        model.openBan()
        await assertEventually { self.banSheet(model)?.target != nil }
        model.changeBanReason("calm down")
        model.kickInstead()
        XCTAssertEqual(kickSheet(model)?.reason, "calm down")
        model.confirmKick()
        await assertEventually { self.sheet(model) == nil && !self.jonasIsThere(model) }
        XCTAssertEqual(engine.kickReasons, ["calm down"])
        XCTAssertEqual(engine.banInputs, [])
    }

    func testOpeningAnotherPersonStartsWithTheirPersonSheet() async throws {
        let model = await jonasSheet()
        model.openKick()
        model.changeKickReason("typed for Jonas")
        model.openPerson(Mockup.ben)
        guard case .person(let person) = sheet(model) else { return XCTFail("not a person sheet") }
        XCTAssertEqual(person.name, "Ben")
        model.openKick()
        XCTAssertEqual(kickSheet(model)?.reason, "")
    }

    func testANormalUserHasNoModeration() async throws {
        let store = makeStore(box, server: mockupServer(), servers: [savedServer(1, "Nordlicht")])
        let model = AppModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected != nil }
        model.openPerson(Mockup.jonas)
        XCTAssertEqual(rows(model), ModerationRows())
        XCTAssertEqual(rows(model)?.any, false)
    }
}
