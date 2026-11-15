// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest
@testable import Mumcord

/// The steps of "Scan from desktop app", as `ScanStateTest` on Android.
final class ScanStateTests: XCTestCase {
    private func standing(_ have: UInt8?, of total: UInt8 = 7, attemptsLeft: Int = 3) -> ScanStanding {
        ScanStanding(
            progress: have.map { TransferProgress(have: $0, total: total) },
            codeDigits: 8,
            attemptsLeft: attemptsLeft
        )
    }

    private let atCode = ScanState(step: .code(CodeEntry(digits: 8, typed: "", attemptsLeft: 3)))

    func testEveryNewCodeMovesTheProgressOn() {
        let state = ScanState(note: .notATransfer).afterPart(standing(3))
        XCTAssertEqual(state.step, .scanning(have: 3, total: 7))
        XCTAssertNil(state.note)
        XCTAssertEqual(scanPercent(have: 3, total: 7), 43)
    }

    func testAfterTheLastCodeTheCodeOfTheDesktopIsAskedFor() {
        XCTAssertEqual(ScanState().afterPart(standing(7)).step, .code(CodeEntry(digits: 8, attemptsLeft: 3)))
    }

    func testAForeignOrBadlyReadCodeKeepsWhatWasCollected() {
        let collecting = ScanState(step: .scanning(have: 2, total: 7))
        let foreign = collecting.afterPartRefused(.NotATransfer)
        XCTAssertEqual(foreign.step, collecting.step)
        XCTAssertEqual(foreign.note, .notATransfer)
        XCTAssertEqual(collecting.afterPartRefused(.BadChecksum).note, .unreadable)
    }

    func testAnExpiredTransferStartsTheScanOver() {
        let state = ScanState(step: .scanning(have: 2, total: 7)).afterPartRefused(.Expired)
        XCTAssertEqual(state.step, .waiting)
        XCTAssertEqual(state.note, .expired)
        XCTAssertEqual(ScanState().afterPartRefused(.ClockSkew).note, .clockSkew)
    }

    func testAWrongCodeCanBeTypedAgainWithTheTriesThatAreLeft() {
        let state = atCode.afterCodeRefused(.WrongCode(attemptsLeft: 2), standing(7, attemptsLeft: 2))
        XCTAssertEqual(state.step, .code(CodeEntry(digits: 8, typed: "", attemptsLeft: 2, wrong: true)))
    }

    func testTheThirdWrongCodeMeansScanningAgain() {
        let state = atCode.afterCodeRefused(.RescanRequired, standing(nil))
        XCTAssertEqual(state.step, .waiting)
        XCTAssertEqual(state.note, .rescanRequired)
    }

    func testADamagedTransferMeansScanningAgain() {
        let state = atCode.afterCodeRefused(.Damaged, standing(nil))
        XCTAssertEqual(state.step, .waiting)
        XCTAssertEqual(state.note, .damaged)
    }

    func testACodeOfTheWrongLengthIsNotAnAttempt() {
        let state = atCode.afterCodeRefused(.InvalidCode(digits: 6), standing(7))
        XCTAssertEqual(state.step, .code(CodeEntry(digits: 6, attemptsLeft: 3)))
    }

    func testACheckThePhoneCouldNotDoLeavesTheCodeToBeTypedAgain() {
        let memory = atCode.afterCodeRefused(.OutOfMemory, standing(7))
        XCTAssertEqual(memory.step, .code(CodeEntry(digits: 8, attemptsLeft: 3)))
        XCTAssertEqual(memory.note, .outOfMemory)
        XCTAssertEqual(atCode.afterCodeRefused(.Failed, standing(7)).note, .failed)
    }

    func testACodeTypedTooEarlyGoesBackToScanning() {
        XCTAssertEqual(atCode.afterCodeRefused(.Incomplete, standing(5)).step, .scanning(have: 5, total: 7))
    }

    func testTheCodeFieldTakesDigitsOnlyAndNoMoreThanTheCodeHas() {
        XCTAssertEqual(codeInput("48 2a-1730599", digits: 8), "48217305")
        XCTAssertEqual(codeInput("", digits: 8), "")
    }

    func testTheCameraOnlyRunsWhileThereIsSomethingToScanAndItIsAllowed() {
        XCTAssertTrue(ScanState().scanning)
        XCTAssertFalse(ScanState(camera: .explain).scanning)
        XCTAssertFalse(atCode.scanning)
    }

    func testThePreviewAndTheSummaryNameWhatArrives() {
        let preview = TransferPreview(
            identities: [TransferIdentityPreview(label: "Lea", fingerprint: "ab", isDefault: true)],
            servers: nil
        )
        XCTAssertEqual(preview.step, .preview(TransferContents(identities: ["Lea"], servers: nil)))
        let report = ImportReport(
            identitiesImported: [],
            identitiesSkipped: [SkippedIdentity(label: "Lea", reason: .duplicate)],
            serversImported: [savedServer(1, "Nordlicht")],
            serversSkipped: [SkippedServer(name: "Tidepool", address: "tidepool.example", reason: .invalid)]
        )
        XCTAssertEqual(
            report.step,
            .done(ImportSummary(
                identitiesImported: 0,
                serversImported: 1,
                skipped: [SkippedEntry(name: "Lea", alreadyThere: true), SkippedEntry(name: "Tidepool", alreadyThere: false)]
            ))
        )
        XCTAssertEqual(transferCounts(1, 4), "1 identity, 4 servers")
        XCTAssertEqual(transferCounts(0, 2, zeroIdentities: false), "2 servers")
        XCTAssertEqual(transferCounts(2, nil), "2 identities")
    }
}
