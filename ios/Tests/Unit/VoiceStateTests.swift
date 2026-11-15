// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The voice bar and the full voice view as a value.
final class VoiceStateTests: XCTestCase {
    private func session(
        _ server: ServerState = mockupVoiceServer(),
        talkMode: TalkMode = .voiceActivity,
        reconnecting: Bool = false
    ) -> SessionState {
        var state = SessionState()
        state.talkMode = talkMode
        state = state.fold(.connectionChanged(state: .connected)).fold(.serverSynced(server: server))
        if reconnecting {
            state = state.fold(.connectionChanged(state: .reconnecting(attempt: 1, maxAttempts: 5, nextRetrySecs: 1)))
        }
        return state
    }

    private func voice(
        _ session: SessionState,
        local: ConnectedLocal = ConnectedLocal(),
        microphone: MicrophoneNotice = .none
    ) -> VoiceUiState? {
        connectedUiState(session: session, local: local, serverName: "Nordlicht", microphone: microphone)?.voice
    }

    private func server(changing session: UInt32, _ change: (inout Person) -> Void) -> ServerState {
        var server = mockupVoiceServer()
        server.people = server.people.map { person in
            var changed = person
            if person.session == session { change(&changed) }
            return changed
        }
        return server
    }

    func testTheBarNamesTheOwnChannelTheServerAndWhetherTheUserTalks() throws {
        let quiet = try XCTUnwrap(voice(session()))
        XCTAssertEqual(quiet.channelName, "Lobby")
        XCTAssertEqual(quiet.serverName, "Nordlicht")
        XCTAssertTrue(quiet.connected)
        XCTAssertFalse(quiet.talking)
        XCTAssertFalse(quiet.open)

        let talking = try XCTUnwrap(voice(session(server(changing: Mockup.lea) { $0.talking = true })))
        XCTAssertTrue(talking.talking)
    }

    func testWhileTheConnectionIsRestoredTheBarStaysAndSaysSo() throws {
        let state = try XCTUnwrap(voice(session(reconnecting: true)))
        XCTAssertFalse(state.connected)
        XCTAssertEqual(state.channelName, "Lobby")
    }

    func testWithoutTheOwnEntryThereIsNoVoiceBar() {
        var server = mockupVoiceServer()
        server.people.removeAll { $0.session == Mockup.lea }
        XCTAssertNil(voice(session(server)))
    }

    func testTheMuteButtonFollowsTheOwnMuteAndDeafenImpliesMute() throws {
        XCTAssertEqual(try XCTUnwrap(voice(session())).mute, .unmuted)
        let muted = try XCTUnwrap(voice(session(server(changing: Mockup.lea) { $0.selfMuted = true })))
        XCTAssertEqual(muted.mute, .muted)
        XCTAssertFalse(muted.deafened)
        let deafened = try XCTUnwrap(voice(session(server(changing: Mockup.lea) { $0.selfDeafened = true })))
        XCTAssertEqual(deafened.mute, .muted)
        XCTAssertTrue(deafened.deafened)
        let serverDeafened = try XCTUnwrap(voice(session(server(changing: Mockup.lea) { $0.serverDeafened = true })))
        XCTAssertTrue(serverDeafened.deafened)
    }

    func testAMuteByTheServerOrTheChannelComesBeforeEverythingElse() throws {
        let serverMuted = server(changing: Mockup.lea) {
            $0.serverMuted = true
            $0.selfMuted = true
        }
        XCTAssertEqual(try XCTUnwrap(voice(session(serverMuted), microphone: .denied)).mute, .serverMuted)
        let suppressed = server(changing: Mockup.lea) { $0.suppressed = true }
        XCTAssertEqual(try XCTUnwrap(voice(session(suppressed))).mute, .serverMuted)
    }

    func testAMissingMicrophoneComesBeforeTheOwnMute() throws {
        let muted = session(server(changing: Mockup.lea) { $0.selfMuted = true })
        XCTAssertEqual(try XCTUnwrap(voice(muted, microphone: .denied)).mute, .noMicrophone)
        XCTAssertEqual(try XCTUnwrap(voice(muted, microphone: .blocked)).mute, .noMicrophone)
        // While the explanation is open nothing has been refused yet.
        XCTAssertEqual(try XCTUnwrap(voice(muted, microphone: .explain)).mute, .muted)
    }

    func testTheTalkModeAndTheHeldButtonReachTheView() throws {
        var local = ConnectedLocal()
        local.voiceOpen = true
        local.talkHeld = true
        let state = try XCTUnwrap(voice(session(talkMode: .pushToTalk), local: local))
        XCTAssertEqual(state.talkMode, .pushToTalk)
        XCTAssertTrue(state.talkHeld)
        XCTAssertTrue(state.open)
        XCTAssertEqual(try XCTUnwrap(voice(session())).talkMode, .voiceActivity)
    }

    func testThePeopleOfTheOwnChannelStandInRowsOfThreeInTheOrderTheyCameWithTheUserLast() throws {
        let rows = try XCTUnwrap(voice(session())).tileRows
        XCTAssertEqual(rows.map { $0.map(\.name) }, [["Anna", "Ben", "Mira"], ["Jonas", "Lea"]])
        XCTAssertEqual(rows.last?.last?.isSelf, true)
        XCTAssertEqual(rows.flatMap { $0 }.filter(\.talking).map(\.name), ["Ben"])
    }

    func testPeopleOfOtherChannelsAreNotInTheView() throws {
        let rows = try XCTUnwrap(voice(session(mockupServer()))).tileRows
        XCTAssertEqual(rows.map { $0.map(\.name) }, [["Anna", "Ben", "Lea"]])
    }

    func testEveryKindOfMuteShowsTheBadge() {
        func muted(_ change: (inout Person) -> Void) -> Bool {
            var one = person(7, "Kim", channelId: Mockup.lobby)
            change(&one)
            return voiceTileRows(people: [one], channelId: Mockup.lobby)[0][0].muted
        }
        XCTAssertFalse(muted { _ in })
        XCTAssertTrue(muted { $0.selfMuted = true })
        XCTAssertTrue(muted { $0.selfDeafened = true })
        XCTAssertTrue(muted { $0.serverMuted = true })
        XCTAssertTrue(muted { $0.suppressed = true })
    }

    func testTheWhisperBarNamesThePersonOrTheChannelAndGoesWhenTheyAreGone() throws {
        var local = ConnectedLocal()
        local.whisperTarget = .person(session: Mockup.jonas)
        local.whisperHeld = true
        XCTAssertEqual(
            try XCTUnwrap(voice(session(), local: local)).whisper,
            WhisperBarState(name: "Jonas", shout: false, held: true)
        )
        local.whisperTarget = .channel(channelId: Mockup.gaming)
        local.whisperHeld = false
        XCTAssertEqual(
            try XCTUnwrap(voice(session(), local: local)).whisper,
            WhisperBarState(name: "Gaming", shout: true, held: false)
        )
        local.whisperTarget = .person(session: 99)
        XCTAssertNil(try XCTUnwrap(voice(session(), local: local)).whisper)
    }
}
