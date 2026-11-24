// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// Sharing the own camera through the model, against the fake engine and a fake camera.
@MainActor
final class CameraShareControllerTests: XCTestCase {
    private let box = EngineBox()
    private var sources: [FakeCameraSource] = []
    private var camera: CameraShare!

    private var engine: FakeEngine { box.engine }
    private var source: FakeCameraSource { sources[sources.count - 1] }

    /// Connected, with the app on screen.
    private func connected(permission: CameraGateway = FakeCameraGateway()) async -> (AppModel, CameraShareController) {
        let store = makeStore(box, server: mockupServer(), servers: [savedServer(1, "Nordlicht")])
        camera = CameraShare(store: store) { [unowned self] onTrouble in
            let source = FakeCameraSource(onTrouble: onTrouble)
            sources.append(source)
            return source
        }
        let model = AppModel(
            store: store,
            setupStore: InMemorySetupStore(firstStartDone: true),
            camera: permission,
            cameraShare: camera
        )
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected != nil }
        camera.setOnScreen(true)
        return (model, model.cameraShare!)
    }

    func testThePreviewRunsTheFrontCameraAndNothingIsSharedBeforeStart() async {
        let (_, controller) = await connected()
        XCTAssertTrue(sources.isEmpty, "no camera before the preview")

        controller.open()
        await assertEventually { self.sources.first?.running == .front }
        XCTAssertNotNil(engine.appCamera, "the engine got the app's camera")
        await assertEventually { controller.uiState.sheet != nil }
        let sheet = controller.uiState.sheet
        XCTAssertEqual(sheet?.facing, .front)
        XCTAssertEqual(sheet?.canSwitch, true)
        XCTAssertNil(controller.uiState.sharing, "FR-5.74: nothing is shared from the preview")
        XCTAssertEqual(engine.ownCameraState, .preview)

        controller.switchCamera()
        XCTAssertEqual(source.running, .back)
        XCTAssertEqual(controller.uiState.sheet?.facing, .back)

        controller.start()
        await assertEventually { controller.uiState.sharing?.viewers == 0 }
        XCTAssertNil(controller.uiState.sheet)
        engine.watchCamera(2)
        await assertEventually { controller.uiState.sharing?.viewers == 2 }
        XCTAssertEqual(controller.uiState.sharing?.facing, .back)
        XCTAssertEqual(sources.count, 1)
    }

    func testTheCameraPausesInTheBackgroundAndResumesWithTheSameCamera() async {
        let (_, controller) = await connected()
        controller.open()
        await assertEventually { self.sources.first?.running == .front }
        controller.switchCamera()
        controller.start()
        await assertEventually { controller.uiState.sharing != nil }

        camera.setOnScreen(false)
        XCTAssertNil(source.running, "FR-5.75: the camera is released in the background")
        XCTAssertEqual(engine.ownCameraState, .sharing, "sharing goes on")
        XCTAssertNotNil(controller.uiState.sharing)

        camera.setOnScreen(true)
        XCTAssertEqual(source.running, .back, "the same camera again")
        XCTAssertEqual(sources.count, 1)
    }

    func testCancelAndStopReleaseTheCamera() async {
        let (_, controller) = await connected()
        controller.open()
        await assertEventually { self.sources.first?.running == .front }
        controller.stop()
        await assertEventually { self.sources.first?.calls.last == .some(nil) }
        XCTAssertNil(source.running)
        await assertEventually { !controller.uiState.on }
        XCTAssertNil(camera.source, "the closed camera is let go")

        controller.switchCamera()
        controller.open()
        await assertEventually { self.sources.count == 2 && self.source.running == .front }
        controller.start()
        await assertEventually { controller.uiState.sharing != nil }
        controller.stop()
        await assertEventually { controller.uiState == CameraShareUiState() }
        XCTAssertNil(source.running)
        XCTAssertNil(controller.uiState.notice, "no notice after the user's own stop")
    }

    func testARefusedStartStaysInThePreviewWithTheReason() async {
        let (_, controller) = await connected()
        engine.cameraStartProblem = .directConnectionsOff
        controller.open()
        await assertEventually { controller.uiState.sheet != nil }
        controller.start()
        await assertEventually { controller.uiState.sheet?.problem == .directConnectionsOff }
        XCTAssertEqual(controller.uiState.sheet?.starting, false)
        XCTAssertNil(controller.uiState.sharing)
        XCTAssertEqual(source.running, .front, "the preview keeps running")
    }

    func testACameraThatStopsByItselfEndsSharingWithANotice() async {
        let (_, controller) = await connected()
        controller.open()
        await assertEventually { self.sources.first?.running == .front }
        controller.start()
        await assertEventually { controller.uiState.sharing != nil }

        source.trouble(.failed)
        XCTAssertEqual(engine.captureFailures, [.cameraFailed])
        await assertEventually { controller.uiState.notice == .cameraFailed }
        XCTAssertNil(controller.uiState.sharing)
        await assertEventually { self.sources.first?.running == nil }

        controller.dismissNotice()
        XCTAssertNil(controller.uiState.notice)
    }

    func testLeavingTheChannelOrTheServerEndsSharingWithoutANotice() async {
        let (model, controller) = await connected()
        controller.open()
        await assertEventually { self.sources.first?.running == .front }
        controller.start()
        await assertEventually { controller.uiState.sharing != nil }
        model.join(Mockup.gaming)
        await assertEventually { !controller.uiState.on }
        await assertEventually { self.sources.first?.running == nil }
        XCTAssertNil(controller.uiState.notice)

        controller.open()
        await assertEventually { self.sources.count == 2 && self.source.running == .front }
        controller.start()
        await assertEventually { controller.uiState.sharing != nil }
        model.disconnect()
        await assertEventually { self.source.running == nil }
        XCTAssertFalse(controller.uiState.on)
        XCTAssertEqual(model.uiState.session.ownCamera, cameraOff)
    }

    func testTheCameraIsExplainedBeforeTheSystemAsksForIt() async {
        let gateway = FakeCameraGateway(permission: .undetermined, answer: true)
        let (_, controller) = await connected(permission: gateway)
        controller.cameraButton()
        XCTAssertEqual(controller.question, .undetermined)
        XCTAssertEqual(gateway.requests, 0, "the system does not ask before the explanation")
        XCTAssertTrue(sources.isEmpty)

        controller.allowCamera()
        XCTAssertNil(controller.question)
        XCTAssertEqual(gateway.requests, 1)
        await assertEventually { self.sources.first?.running == .front }
        await assertEventually { controller.uiState.sheet != nil }
    }

    func testAfterARefusalOnlyTheSystemSettingsHelp() async {
        let gateway = FakeCameraGateway(permission: .denied)
        let (_, controller) = await connected(permission: gateway)
        controller.cameraButton()
        XCTAssertEqual(controller.question, .denied)
        controller.dismissQuestion()
        XCTAssertNil(controller.question)
        XCTAssertEqual(gateway.requests, 0)
        XCTAssertTrue(sources.isEmpty, "the camera is never opened without the permission")
    }

    func testOnlyACameraThatWasSharedOrStartingLeavesANotice() {
        let off = OwnCamera(state: .off, problem: .relayLost, viewers: 0)
        let sharing = OwnCamera(state: .sharing, problem: nil, viewers: 1)
        let preview = OwnCamera(state: .preview, problem: nil, viewers: 0)
        XCTAssertEqual(stoppedNotice(before: sharing, after: off), .relayLost)
        XCTAssertNil(stoppedNotice(before: preview, after: cameraOff), "the user's own cancel")
        XCTAssertNil(stoppedNotice(before: sharing, after: cameraOff))
        XCTAssertNil(stoppedNotice(before: sharing, after: preview))
    }

    func testAPhoneWithOneCameraHasNoSwitch() {
        let state = cameraShareUiState(
            camera: OwnCamera(state: .starting, problem: nil, viewers: 0),
            facing: .front,
            facings: [.front],
            notice: nil
        )
        XCTAssertEqual(state.sheet?.canSwitch, false)
        XCTAssertEqual(state.sheet?.starting, true)
        XCTAssertTrue(state.on)
    }
}
