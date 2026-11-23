// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The rules of the mobile data switches and of getting back in after a network change
/// (FR-5.26, FR-5.40, FR-5.41), the same as on Android.
final class MobileDataTests: XCTestCase {
    private func settings(load: Bool = true, autoplay: Bool = false, reduced: Bool = false) -> MobileSettings {
        MobileSettings(
            talkModeChosen: true,
            defaultOutput: .ask,
            mobileDataAutoplayStreams: autoplay,
            mobileDataLoadMedia: load,
            mobileDataReducedVoice: reduced,
            idleDisconnect: false,
            notifySenderOnly: false,
            updateCheck: true
        )
    }

    private let otherWifi = NetworkStatus(id: "other-wifi", usable: true, metered: false)

    func testImagesLoadOnWifiAlwaysAndOnMobileDataAsTheSwitchSays() {
        XCTAssertTrue(loadMedia(fakeWifi, settings(load: false)))
        XCTAssertTrue(loadMedia(fakeMobileData, settings(load: true)))
        XCTAssertFalse(loadMedia(fakeMobileData, settings(load: false)))
        // On by default, also while the settings are unread.
        XCTAssertTrue(loadMedia(fakeMobileData, nil))
        XCTAssertFalse(loadMedia(.noNetwork, settings(load: false)))
    }

    func testStreamsStartByThemselvesOnWifiAndOnMobileDataOnlyWithTheSwitch() {
        XCTAssertTrue(autoplayStreams(fakeWifi, settings()))
        XCTAssertFalse(autoplayStreams(fakeMobileData, settings()))
        XCTAssertTrue(autoplayStreams(fakeMobileData, settings(autoplay: true)))
        XCTAssertFalse(autoplayStreams(fakeMobileData, nil))
    }

    func testTheVoiceIsLimitedTo24KbitOnlyOnMobileDataWithTheSwitch() {
        XCTAssertEqual(bitrateLimitKbps(fakeMobileData, settings(reduced: true)), 24)
        XCTAssertNil(bitrateLimitKbps(fakeMobileData, settings(reduced: false)))
        XCTAssertNil(bitrateLimitKbps(fakeWifi, settings(reduced: true)))
        XCTAssertNil(bitrateLimitKbps(fakeMobileData, nil))
    }

    func testAnExpensiveOrConstrainedPathIsMetered() {
        XCTAssertEqual(
            networkStatus(satisfied: true, interface: "wifi-en0", expensive: false, constrained: false),
            NetworkStatus(id: "wifi-en0", usable: true, metered: false)
        )
        XCTAssertTrue(networkStatus(satisfied: true, interface: "cellular-pdp_ip0", expensive: true, constrained: false).metered)
        XCTAssertTrue(networkStatus(satisfied: true, interface: "wifi-en0", expensive: false, constrained: true).metered)
        XCTAssertEqual(networkStatus(satisfied: false, interface: "wifi-en0", expensive: false, constrained: false), .noNetwork)
        XCTAssertEqual(networkStatus(satisfied: true, interface: nil, expensive: false, constrained: false), .noNetwork)
    }

    private func reconnecting(_ attempt: Int) -> ConnectionPhase {
        .reconnecting(Reconnecting(attempt: attempt, maxAttempts: 10))
    }

    func testANewNetworkGetsBackInFromTheThirdAttemptOn() {
        XCTAssertTrue(reconnectsNow(previous: fakeWifi, current: fakeMobileData, phase: reconnecting(3), running: false))
        XCTAssertTrue(reconnectsNow(previous: .noNetwork, current: fakeWifi, phase: reconnecting(5), running: false))
        XCTAssertTrue(reconnectsNow(previous: fakeWifi, current: otherWifi, phase: reconnecting(3), running: false))
    }

    func testEarlierAttemptsTheSameNetworkNoNetworkAndARunningReconnectAreLeftAlone() {
        XCTAssertFalse(reconnectsNow(previous: fakeWifi, current: fakeMobileData, phase: reconnecting(2), running: false))
        XCTAssertFalse(reconnectsNow(previous: fakeWifi, current: fakeWifi, phase: reconnecting(3), running: false))
        XCTAssertFalse(reconnectsNow(previous: fakeWifi, current: .noNetwork, phase: reconnecting(3), running: false))
        XCTAssertFalse(reconnectsNow(previous: fakeWifi, current: fakeMobileData, phase: reconnecting(3), running: true))
        XCTAssertFalse(reconnectsNow(previous: fakeWifi, current: fakeMobileData, phase: .connected, running: false))
    }

    func testTheChannelPathLeadsFromBelowTheRootToTheOwnChannel() {
        let channels = [channel(0, "Root"), channel(1, "Games", parentId: 0), channel(2, "Chess", parentId: 1)]
        func model(in channelId: UInt32) -> ServerModel {
            ServerModel(
                selfSession: 10,
                channels: Dictionary(uniqueKeysWithValues: channels.map { ($0.id, $0) }),
                people: [10: person(10, "Me", channelId: channelId)]
            )
        }
        XCTAssertEqual(model(in: 2).selfChannelPath(), ["Games", "Chess"])
        XCTAssertEqual(model(in: 0).selfChannelPath(), [])
    }

    func testAChannelLoopFromTheServerEndsTheWalk() {
        let channels = [channel(1, "A", parentId: 2), channel(2, "B", parentId: 1)]
        let model = ServerModel(
            selfSession: 10,
            channels: Dictionary(uniqueKeysWithValues: channels.map { ($0.id, $0) }),
            people: [10: person(10, "Me", channelId: 1)]
        )
        XCTAssertEqual(model.selfChannelPath().count, 2)
    }

    func testWhileResumingTheOrderlyEndAndTheNewAttemptAreHidden() {
        var state = SessionState()
        state.phase = reconnecting(3)
        state.activeServerId = 1
        XCTAssertEqual(state.foldResuming(.connectionChanged(state: .disconnected(error: nil))), state)
        XCTAssertEqual(state.foldResuming(.connectionChanged(state: .connecting)), state)
        XCTAssertEqual(state.foldResuming(.connectionChanged(state: .connected)).phase, .connected)
        let failed = state.foldResuming(.connectionChanged(state: .disconnected(error: .ServerUnreachable(detail: ""))))
        XCTAssertEqual(failed.phase, .failed(.serverUnreachable))
    }
}
