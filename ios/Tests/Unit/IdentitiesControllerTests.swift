// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest
@testable import Mumcord

/// What the user does on the identity screens and the scan, through the controller, against
/// the fake engine and a scripted transfer, as `IdentitiesControllerTest` on Android.
@MainActor
final class IdentitiesControllerTests: XCTestCase {
    private let box = EngineBox()
    private var camera = FakeCameraGateway()
    private var transfer: FakeTransferScan?

    private func makeController(
        identities: [Identity] = [fakeIdentity],
        servers: [SavedServer] = [],
        configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }
    ) async -> (SessionStore, IdentitiesController) {
        let store = makeStore(box, server: mockupServer(), servers: servers) { engine in
            engine.identities = identities
            engine.transfer = FakeDesktopTransfer.scan(for: engine)
            configure(engine)
        }
        await store.loadServers()
        transfer = box.engine.transfer as? FakeTransferScan
        return (store, IdentitiesController(store: store, camera: camera))
    }

    private func labels(_ store: SessionStore) -> [String] {
        (store.state.identities ?? []).map(\.label)
    }

    // MARK: Create, import, export, delete

    func testANewIdentityIsCreatedWithTheTypedNameAndTheFirstOneIsTheDefault() async {
        let (store, controller) = await makeController(identities: [])
        controller.openCreate()
        controller.changeCreateName("  Lea  ")
        controller.create()

        await assertEventually { controller.local.sheet == nil }
        await assertEventually { self.labels(store) == ["Lea"] }
        XCTAssertEqual(store.state.identities?.first?.isDefault, true)
    }

    func testANameIsNeverLongerThanACertificateTakes() async {
        let (_, controller) = await makeController()
        controller.openCreate()
        controller.changeCreateName(String(repeating: "x", count: 100))
        guard case .create(let form) = controller.local.sheet else { return XCTFail("no create sheet") }
        XCTAssertEqual(form.name.count, maxIdentityName)
    }

    func testAnIdentityFileIsImportedWithItsPassword() async {
        let (store, controller) = await makeController { $0.filePassword = "secret" }
        controller.importFile(Data("file".utf8))
        controller.changeImportPassword("wrong")
        controller.confirmImport()
        await assertEventually { controller.local.sheet == .importFile(ImportForm(problem: .wrongPasswordOrFile)) }

        controller.changeImportPassword("secret")
        controller.confirmImport()
        await assertEventually { controller.local.sheet == nil }
        await assertEventually { self.labels(store) == ["Lea", fakeImportedName] }
    }

    func testTheSameIdentityIsNotImportedTwice() async {
        let (_, controller) = await makeController()
        controller.importFile(Data(fakeIdentity.fingerprint.utf8))
        controller.confirmImport()
        await assertEventually { controller.local.sheet == .importFile(ImportForm(problem: .duplicate)) }
    }

    func testAFileThatCannotBeReadIsSaidOnTheScreenAndNothingIsAskedFor() async {
        let (_, controller) = await makeController()
        controller.importFile(nil)
        XCTAssertTrue(controller.local.unreadableFile)
        XCTAssertNil(controller.local.sheet)
    }

    func testABackupIsMadeWithThePasswordTypedTwiceAndHandedToTheShareSheetOnce() async {
        let (_, controller) = await makeController()
        controller.openExport(fakeIdentity.id)
        controller.changeExportPassword("backup")
        controller.export()
        XCTAssertNil(controller.local.exported, "one password is not enough")

        controller.changeExportRepeat("backup")
        controller.export()
        await assertEventually { controller.local.exported != nil }
        XCTAssertEqual(controller.local.exported?.fileName, "mumcord-identity-Lea.p12")
        XCTAssertNil(controller.local.sheet)
        controller.exportShared()
        XCTAssertNil(controller.local.exported)
    }

    func testAFailedBackupSaysSoAndForgetsThePasswords() async {
        let (_, controller) = await makeController { $0.exportError = .Io(detail: "disk full") }
        controller.openExport(fakeIdentity.id)
        controller.changeExportPassword("backup")
        controller.changeExportRepeat("backup")
        controller.export()
        await assertEventually {
            controller.local.sheet == .export(ExportForm(identityId: fakeIdentity.id, failed: true))
        }
        XCTAssertNil(controller.local.exported)
    }

    func testAnotherIdentityBecomesTheDefault() async {
        let (store, controller) = await makeController(identities: [fakeIdentity, workIdentity])
        controller.setDefault(workIdentity.id)
        await assertEventually { store.state.identities?.first?.label == "Work" }
        XCTAssertEqual(store.state.identities?.first?.isDefault, true)
    }

    func testDeletingNeedsTheTypedLabelAndTheLastIdentityStays() async {
        let (store, controller) = await makeController(identities: [fakeIdentity, workIdentity])
        controller.openDelete(workIdentity.id)
        controller.changeDeleteConfirmation("work")
        controller.delete()
        XCTAssertEqual(controller.local.sheet, .delete(DeleteForm(identityId: workIdentity.id, typed: "work")))

        controller.changeDeleteConfirmation("Work")
        controller.delete()
        await assertEventually { self.labels(store) == ["Lea"] }
        XCTAssertNil(controller.local.sheet)

        controller.openDelete(fakeIdentity.id)
        controller.changeDeleteConfirmation("Lea")
        controller.delete()
        XCTAssertEqual(controller.local.sheet, .delete(DeleteForm(identityId: fakeIdentity.id, typed: "Lea")))
    }

    func testBackUpFirstReturnsToTheDeleteWarning() async {
        let (_, controller) = await makeController(identities: [fakeIdentity, workIdentity])
        controller.openDelete(workIdentity.id)
        controller.openExport(workIdentity.id)
        controller.closeSheet()
        XCTAssertEqual(controller.local.sheet, .delete(DeleteForm(identityId: workIdentity.id)))
    }

    // MARK: Scan

    private func openScan(_ controller: IdentitiesController) async {
        controller.openScan()
        await assertEventually { controller.scanReady }
    }

    private func scanAll(_ controller: IdentitiesController) {
        FakeDesktopTransfer.parts.reversed().forEach(controller.onQrText)
    }

    func testTheCodesAreCollectedInAnyOrderAndCountedOnce() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        let parts = FakeDesktopTransfer.parts
        controller.onQrText(parts[4])
        controller.onQrText(parts[4])
        controller.onQrText(parts[1])
        XCTAssertEqual(controller.local.scan?.step, .scanning(have: 2, total: 7))
        scanAll(controller)
        XCTAssertEqual(controller.local.scan?.step, .code(CodeEntry(digits: 8, attemptsLeft: 3)))
    }

    func testAForeignQrCodeIsSaidAndWhatWasCollectedStays() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        controller.onQrText(FakeDesktopTransfer.parts[0])
        controller.onQrText(FakeDesktopTransfer.foreignText)
        XCTAssertEqual(controller.local.scan?.note, .notATransfer)
        XCTAssertEqual(controller.local.scan?.step, .scanning(have: 1, total: 7))
        controller.onQrText(String(repeating: "x", count: maxQrCharacters + 1))
        XCTAssertEqual(controller.local.scan?.note, .notATransfer)
    }

    func testTheCodeIsCheckedAtItsLastDigitAndIsGoneFromTheStateAfterwards() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        scanAll(controller)
        controller.typeCode("4821730")
        XCTAssertEqual(transfer?.checkedCodes, 0)
        controller.typeCode(FakeDesktopTransfer.code)
        await assertEventually {
            if case .preview = controller.local.scan?.step { return true }
            return false
        }
        XCTAssertEqual(transfer?.checkedCodes, 1)
        XCTAssertFalse(String(describing: controller.local).contains(FakeDesktopTransfer.code))
    }

    func testAWrongCodeIsRefusedThreeTimesAndThenTheScanStartsOver() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        scanAll(controller)
        for left in [2, 1] {
            controller.typeCode(FakeDesktopTransfer.wrongCode)
            await assertEventually {
                controller.local.scan?.step == .code(CodeEntry(digits: 8, attemptsLeft: left, wrong: true))
            }
        }
        controller.typeCode(FakeDesktopTransfer.wrongCode)
        await assertEventually { controller.local.scan?.note == .rescanRequired }
        XCTAssertEqual(controller.local.scan?.step, .waiting)
    }

    func testTheWrongMarkGoesAwayWhenTheUserTypesAgain() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        scanAll(controller)
        controller.typeCode(FakeDesktopTransfer.wrongCode)
        await assertEventually {
            if case .code(let entry) = controller.local.scan?.step { return entry.wrong }
            return false
        }
        controller.typeCode("4")
        XCTAssertEqual(controller.local.scan?.step, .code(CodeEntry(digits: 8, typed: "4", attemptsLeft: 2)))
    }

    func testAnExpiredTransferStartsOverWithAClearNote() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        controller.onQrText(FakeDesktopTransfer.parts[0])
        transfer?.failNext = .Expired
        controller.onQrText(FakeDesktopTransfer.parts[1])
        XCTAssertEqual(controller.local.scan?.note, .expired)
        XCTAssertEqual(controller.local.scan?.step, .waiting)
    }

    func testACodeOfANewTransferReplacesTheOldOne() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        controller.onQrText(FakeDesktopTransfer.parts[0])
        transfer?.failNext = .OtherTransfer
        controller.onQrText(FakeDesktopTransfer.parts[1])
        XCTAssertEqual(transfer?.resets, 1)
        XCTAssertEqual(controller.local.scan?.step, .scanning(have: 1, total: 7))
    }

    func testTheImportRunsOnlyAfterTheUserConfirmsAndSaysWhatWasSkipped() async {
        let (store, controller) = await makeController(servers: [savedServer(1, "Nordlicht", address: "nordlicht.example.org")])
        await openScan(controller)
        scanAll(controller)
        controller.typeCode(FakeDesktopTransfer.code)
        await assertEventually {
            if case .preview = controller.local.scan?.step { return true }
            return false
        }
        XCTAssertEqual(labels(store), ["Lea"], "nothing is imported before Import")

        controller.importTransfer()
        await assertEventually {
            if case .done = controller.local.scan?.step { return true }
            return false
        }
        XCTAssertEqual(
            controller.local.scan?.step,
            .done(ImportSummary(
                identitiesImported: 1,
                serversImported: 3,
                skipped: [SkippedEntry(name: "Nordlicht", alreadyThere: true)]
            ))
        )
        await assertEventually { store.state.servers.count == 4 && self.labels(store).count == 2 }
    }

    func testAFailedImportKeepsThePreviewForAnotherTry() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        scanAll(controller)
        controller.typeCode(FakeDesktopTransfer.code)
        await assertEventually {
            if case .preview = controller.local.scan?.step { return true }
            return false
        }
        transfer?.importFailure = .SecureStorageUnavailable(detail: "locked")
        controller.importTransfer()
        await assertEventually {
            if case .preview(let contents) = controller.local.scan?.step { return contents.error == .secureStorageUnavailable }
            return false
        }
        controller.importTransfer()
        await assertEventually {
            if case .done = controller.local.scan?.step { return true }
            return false
        }
    }

    func testClosingTheScanForgetsItInTheBridge() async {
        let (_, controller) = await makeController()
        await openScan(controller)
        controller.onQrText(FakeDesktopTransfer.parts[0])
        controller.closeScan()
        XCTAssertNil(controller.local.scan)
        await assertEventually { self.transfer?.resets == 1 }
    }

    func testAnEngineWithoutATransferSaysThatScanningFailed() async {
        let (_, controller) = await makeController { $0.transfer = nil }
        controller.openScan()
        await assertEventually { controller.local.scan?.note == .failed }
    }

    // MARK: Camera

    func testTheCameraIsExplainedBeforeTheSystemAsksAndNotNowLeavesAClearState() async {
        camera = FakeCameraGateway(permission: .undetermined, answer: false)
        let (_, controller) = await makeController()
        controller.openScan()
        XCTAssertEqual(controller.local.scan?.camera, .explain)
        XCTAssertEqual(camera.requests, 0)
        controller.cameraNotNow()
        XCTAssertEqual(controller.local.scan?.camera, .denied)
    }

    func testAGrantedCameraStartsTheScan() async {
        camera = FakeCameraGateway(permission: .undetermined, answer: true)
        let (_, controller) = await makeController()
        controller.openScan()
        controller.allowCamera()
        await assertEventually { controller.local.scan?.camera == nil }
        XCTAssertEqual(controller.local.scan?.scanning, true)
    }

    func testARefusalPointsToTheSystemSettings() async {
        camera = FakeCameraGateway(permission: .undetermined, answer: false)
        let (_, controller) = await makeController()
        controller.openScan()
        controller.allowCamera()
        await assertEventually { controller.local.scan?.camera == .blocked }

        // Allowed in the system settings meanwhile.
        camera.current = .granted
        controller.recheckCamera()
        XCTAssertNil(controller.local.scan?.camera)
    }

    func testAPhoneWhoseCameraDoesNotStartSaysSo() async {
        let (_, controller) = await makeController()
        controller.openScan()
        controller.cameraFailed()
        XCTAssertEqual(controller.local.scan?.note, .noCamera)
    }
}
