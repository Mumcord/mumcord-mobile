// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The More tab (activity badge, settings) through the model, against the fake engine.
@MainActor
final class MoreControllerTests: XCTestCase {
    private let box = EngineBox()
    private let messageSettings = InMemoryMessageNotificationSettings()
    private let speaker = AudioOutput(id: OutputId.speaker, kind: .speaker, name: "")
    private let earpiece = AudioOutput(id: OutputId.earpiece, kind: .earpiece, name: "")

    private var engine: FakeEngine { box.engine }

    /// A model that is already connected to the mockup server, on a phone with these outputs.
    private func makeConnected(
        outputs: [AudioOutput] = [],
        messageSettings: ((SessionStore) -> MessageNotificationSettings)? = nil,
        configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }
    ) async -> AppModel {
        let store = makeStore(box, server: mockupVoiceServer(), servers: [savedServer(1, "Nordlicht")], configure: configure)
        let device = FakeOutputDevice(OutputState(available: outputs, current: outputs.first))
        let model = PhoneFakes().makeModel(
            store: store,
            setupStore: InMemorySetupStore(firstStartDone: true),
            messageSettings: messageSettings?(store) ?? self.messageSettings,
            outputs: outputs.isEmpty ? nil : VoiceOutput(store: store, outputs: SessionOutputs(device: device))
        )
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.moreUiState != nil }
        return model
    }

    private func settings(_ model: AppModel) -> SettingsUiState? {
        model.moreUiState?.settings
    }

    private func activity(_ id: UInt64, isSelf: Bool = false) -> BridgeEvent {
        .activityAdded(entry: ActivityEntry(
            id: id,
            kind: isSelf ? .moved : .joined,
            session: UInt32(id),
            name: "Person \(id)",
            isSelf: isSelf,
            channelId: Mockup.gaming,
            channelName: "Gaming",
            atMs: Int64(id)
        ))
    }

    // MARK: Settings

    func testTheSettingsOpenFromMoreWithTheDefaultsOfTheSpecification() async throws {
        let model = await makeConnected()
        XCTAssertNil(settings(model), "closed at first")

        model.selectTab(.more)
        model.more.openSettings()
        await assertEventually { self.settings(model)?.noiseSuppression != nil }

        let shown = try XCTUnwrap(settings(model))
        XCTAssertFalse(shown.mobile.mobileDataAutoplayStreams, "Start streams automatically")
        XCTAssertTrue(shown.mobile.mobileDataLoadMedia, "Load images and GIFs")
        XCTAssertFalse(shown.mobile.mobileDataReducedVoice, "Reduced voice quality")
        XCTAssertFalse(shown.mobile.idleDisconnect, "Disconnect when alone")
        XCTAssertTrue(shown.app.notifyPrivateMessages, "Private messages")
        XCTAssertTrue(shown.app.notifyMentions, "Mentions")
        XCTAssertFalse(shown.senderOnly, "Show sender only")
        XCTAssertNil(shown.app.historyMaxAgeDays, "history is kept")
        XCTAssertEqual(shown.talkMode, .voiceActivity)
        XCTAssertEqual(shown.noiseSuppression, false, "read from the engine")
        XCTAssertFalse(shown.hasEarpiece, "this phone has no earpiece")

        model.more.back()
        XCTAssertNil(settings(model))
        XCTAssertEqual(model.moreUiState?.page, .main)
    }

    func testEverySwitchIsSavedAtOnceAndLeavesTheOthersAlone() async throws {
        let model = await makeConnected()
        let before = engine.storedMobileSettings
        model.more.openSettings()

        model.more.changeMobile { $0.mobileDataAutoplayStreams = true }
        model.more.changeMobile { $0.mobileDataLoadMedia = false }
        model.more.changeMobile { $0.mobileDataReducedVoice = true }
        model.more.changeMobile { $0.idleDisconnect = true }
        model.more.changeApp { $0.notifyPrivateMessages = false }
        model.more.changeApp { $0.notifyMentions = false }
        model.more.setNoiseSuppression(true)
        model.more.setSenderOnly(true)

        var expected = before
        expected.mobileDataAutoplayStreams = true
        expected.mobileDataLoadMedia = false
        expected.mobileDataReducedVoice = true
        expected.idleDisconnect = true
        await assertEventually {
            self.engine.storedMobileSettings == expected && !self.engine.storedAppSettings.notifyMentions
                && self.engine.noiseSuppression
        }
        XCTAssertFalse(engine.storedAppSettings.notifyPrivateMessages)
        XCTAssertTrue(engine.storedAppSettings.linkPreviews, "the other shared settings stay")
        XCTAssertTrue(messageSettings.senderOnly)

        await assertEventually { self.settings(model)?.senderOnly == true && self.settings(model)?.app == self.engine.storedAppSettings }
        let shown = try XCTUnwrap(settings(model))
        XCTAssertEqual(shown.mobile, expected, "the screen shows what was saved")
        XCTAssertEqual(shown.noiseSuppression, true)
    }

    /// "Show sender only" is saved by the notification settings, the other switches by the More
    /// tab: two writers of the same record, which must not save over each other.
    func testASwitchAndShowSenderOnlyFlippedTogetherAreBothSaved() async {
        let model = await makeConnected(
            messageSettings: { EngineMessageNotificationSettings(store: $0) },
            configure: { $0.mobileSettingsSaveDelay = .milliseconds(50) }
        )
        model.more.openSettings()

        model.more.changeMobile { $0.idleDisconnect = true }
        model.more.setSenderOnly(true)

        await assertEventually { self.engine.storedMobileSettings.notifySenderOnly }
        await assertEventually { self.engine.storedMobileSettings.idleDisconnect }
        XCTAssertTrue(engine.storedMobileSettings.notifySenderOnly, "both switches are stored")
        XCTAssertEqual(settings(model)?.mobile, engine.storedMobileSettings, "the screen shows what was saved")
    }

    func testShowSenderOnlyChangesItsValueBothWays() async {
        let model = await makeConnected()
        model.more.openSettings()
        await assertEventually { self.settings(model) != nil }
        XCTAssertEqual(settings(model)?.senderOnly, false)

        model.more.setSenderOnly(true)
        XCTAssertTrue(messageSettings.senderOnly)
        await assertEventually { self.settings(model)?.senderOnly == true }

        model.more.setSenderOnly(false)
        XCTAssertFalse(messageSettings.senderOnly)
        await assertEventually { self.settings(model)?.senderOnly == false }
    }

    func testShowSenderOnlyOutlivesTheConnection() async {
        let model = await makeConnected()
        model.more.setSenderOnly(true)
        await assertEventually { model.more.senderOnly }
        model.disconnect()
        await assertEventually { model.moreUiState == nil }
        XCTAssertTrue(model.more.senderOnly)
    }

    func testAChoiceFromASheetIsSavedAndClosesIt() async {
        let model = await makeConnected(outputs: [speaker, earpiece])
        model.more.openSettings()
        await assertEventually { self.settings(model) != nil }
        XCTAssertEqual(settings(model)?.hasEarpiece, true)

        model.more.openSheet(.talkMode)
        XCTAssertEqual(settings(model)?.sheet, .talkMode)
        model.more.selectTalkMode(.pushToTalk)
        XCTAssertNil(settings(model)?.sheet)
        await assertEventually { self.engine.currentTalkMode == .pushToTalk && self.settings(model)?.talkMode == .pushToTalk }

        model.more.openSheet(.historyAge)
        model.more.selectHistoryAge(30)
        XCTAssertNil(settings(model)?.sheet)
        await assertEventually {
            self.engine.storedAppSettings.historyMaxAgeDays == 30 && self.settings(model)?.app.historyMaxAgeDays == 30
        }
        model.more.selectHistoryAge(nil)
        await assertEventually { self.settings(model)?.app.historyMaxAgeDays == nil }
        XCTAssertNil(engine.storedAppSettings.historyMaxAgeDays)

        model.more.openSheet(.defaultOutput)
        model.more.selectDefaultOutput(.earpiece)
        XCTAssertNil(settings(model)?.sheet)
        await assertEventually { self.engine.storedMobileSettings.defaultOutput == .earpiece }
    }

    func testASwitchFlippedWhileTheVoiceSettingsAreReadWins() async {
        let model = await makeConnected()
        model.more.openSettings()
        model.more.setNoiseSuppression(true)
        await assertEventually { self.engine.noiseSuppression }
        XCTAssertEqual(model.more.local.noiseSuppression, true)
    }

    func testNoiseSuppressionThatCannotBeSavedGoesBackAndIsReported() async {
        let model = await makeConnected()
        model.more.openSettings()
        await assertEventually { model.more.local.noiseSuppression == false }

        engine.voiceSettingsFailure = .Internal(detail: "disk full")
        model.more.setNoiseSuppression(true)
        XCTAssertEqual(model.more.local.noiseSuppression, true, "shown at once")
        await assertEventually { model.more.local.noiseSuppression == false && model.uiState.connected?.denied != nil }
        XCTAssertFalse(engine.noiseSuppression)
    }

    func testVoiceSettingsThatCannotBeReadAreReportedAndTheRowStaysAway() async {
        let model = await makeConnected(configure: { $0.voiceSettingsFailure = .Internal(detail: "disk full") })
        model.more.openSettings()
        await assertEventually { model.uiState.connected?.denied != nil }
        XCTAssertNotNil(settings(model), "the other settings are shown")
        XCTAssertNil(settings(model)?.noiseSuppression)
    }

    func testBringingAnotherTabToTheFrontClosesTheSheet() async {
        let model = await makeConnected()
        model.selectTab(.more)
        model.more.openSettings()
        model.more.openSheet(.historyAge)
        model.showTab(.messages)
        XCTAssertNil(model.more.local.sheet)
    }

    // MARK: Activity

    func testTheActivityBadgeCountsWhatOthersDidUntilTheLogIsOpened() async {
        let model = await makeConnected()
        XCTAssertEqual(model.moreUiState?.activityBadge, 0)

        engine.emit(activity(1))
        engine.emit(activity(2))
        engine.emit(activity(3, isSelf: true))
        await assertEventually { model.moreUiState?.activity.map(\.id) == [3, 2, 1] }
        XCTAssertEqual(model.moreUiState?.activityBadge, 2, "the user's own move is not news")

        model.more.openActivity()
        engine.emit(activity(4))
        await assertEventually { model.moreUiState?.activity.count == 4 }
        XCTAssertEqual(model.moreUiState?.page, .activity)
        XCTAssertEqual(model.moreUiState?.activityBadge, 0)

        model.more.back()
        XCTAssertEqual(model.moreUiState?.page, .main)
        XCTAssertEqual(model.moreUiState?.activityBadge, 0, "seen while the log was open")

        engine.emit(activity(5))
        await assertEventually { model.moreUiState?.activityBadge == 1 }
    }

    func testTheEndOfTheConnectionStartsTheMoreTabAfresh() async {
        let model = await makeConnected()
        engine.emit(activity(1))
        model.more.openActivity()
        model.more.selectFilter(.streams)
        XCTAssertEqual(model.moreUiState?.filter, .streams)

        model.disconnect()
        await assertEventually { model.more.local == MoreLocal() }
        XCTAssertNil(model.moreUiState)
        XCTAssertEqual(model.uiState.session.activity, [])
    }
}
