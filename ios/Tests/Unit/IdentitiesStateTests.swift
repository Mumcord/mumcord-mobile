// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest
@testable import Mumcord

private func identity(_ id: Int64, _ label: String, isDefault: Bool = false, createdMs: Int64 = 0) -> Identity {
    Identity(
        id: id,
        label: label,
        commonName: label,
        fingerprint: String(repeating: "ab", count: 20),
        createdMs: createdMs,
        isDefault: isDefault,
        serverIds: []
    )
}

private func server(_ id: Int64, identityId: Int64?) -> SavedServer {
    var saved = savedServer(id, "Server \(id)")
    saved.identityId = identityId
    return saved
}

/// The pure state of the identity screens, as `IdentitiesStateTest` on Android.
final class IdentitiesStateTests: XCTestCase {
    private func state(
        _ identities: [Identity]?,
        servers: [SavedServer] = [],
        local: IdentitiesLocal = IdentitiesLocal()
    ) -> IdentitiesUiState {
        var session = SessionState()
        session.identities = identities
        session.servers = servers
        return identitiesUiState(session: session, local: local)
    }

    func testTheIdentitiesAreNotKnownBeforeTheyWereRead() {
        XCTAssertFalse(state(nil).known)
        XCTAssertTrue(state([]).known)
    }

    func testARowCountsTheServersThatConnectWithIt() {
        let rows = state(
            [identity(1, "Lea", isDefault: true), identity(2, "Work")],
            servers: [server(1, identityId: nil), server(2, identityId: 2), server(3, identityId: 1)]
        ).rows
        XCTAssertEqual(rows.map(\.serverCount), [2, 1])
    }

    func testTheFingerprintIsShownAsItsStartOnTheCardAndInBlocksInTheSheet() {
        var local = IdentitiesLocal()
        local.sheet = .details(identityId: 1)
        let shown = state([identity(1, "Lea", isDefault: true)], local: local)
        XCTAssertEqual(shown.rows[0].fingerprintStart, "AB:AB:AB:AB:AB:AB:AB:AB")
        guard case .details(let details) = shown.sheet else { return XCTFail("no details") }
        XCTAssertEqual(details.fingerprint, Array(repeating: "ABAB", count: 10).joined(separator: " "))
    }

    func testTheLastIdentityCannotBeDeleted() {
        var local = IdentitiesLocal()
        local.sheet = .details(identityId: 1)
        guard case .details(let details) = state([identity(1, "Lea", isDefault: true)], local: local).sheet else {
            return XCTFail("no details")
        }
        XCTAssertFalse(details.canDelete)
    }

    func testDeletingNeedsTheLabelTypedExactly() {
        var local = IdentitiesLocal()
        local.sheet = .delete(DeleteForm(identityId: 2, typed: "work"))
        let identities = [identity(1, "Lea", isDefault: true), identity(2, "Work")]
        guard case .delete(let details, let form, let defaultLabel) = state(identities, local: local).sheet else {
            return XCTFail("no delete sheet")
        }
        XCTAssertFalse(canDelete(details, form))
        XCTAssertTrue(canDelete(details, DeleteForm(identityId: 2, typed: " Work ")))
        XCTAssertEqual(defaultLabel, "Lea")
    }

    func testDeletingTheDefaultNamesTheOldestRemainingIdentityAsTheNextDefault() {
        var local = IdentitiesLocal()
        local.sheet = .details(identityId: 1)
        let identities = [
            identity(1, "Lea", isDefault: true, createdMs: 0),
            identity(3, "Newer", createdMs: 300),
            identity(2, "Older", createdMs: 200),
        ]
        guard case .details(let details) = state(identities, local: local).sheet else { return XCTFail("no details") }
        XCTAssertEqual(details.nextDefaultLabel, "Older")
    }

    func testASheetAboutAnIdentityThatIsGoneCloses() {
        var local = IdentitiesLocal()
        local.sheet = .details(identityId: 9)
        XCTAssertNil(state([identity(1, "Lea", isDefault: true)], local: local).sheet)
    }

    func testABackupNeedsTheSamePasswordTwiceAndNotAnEmptyOne() {
        XCTAssertFalse(ExportForm(identityId: 1).canExport)
        XCTAssertFalse(ExportForm(identityId: 1, password: "a", repeated: "b").canExport)
        XCTAssertTrue(ExportForm(identityId: 1, password: "a", repeated: "b").mismatch)
        XCTAssertFalse(ExportForm(identityId: 1, password: "a", repeated: "").mismatch)
        XCTAssertTrue(ExportForm(identityId: 1, password: "a", repeated: "a").canExport)
    }

    func testANewIdentityNeedsAName() {
        XCTAssertFalse(CreateForm(name: "  ").canCreate)
        XCTAssertTrue(CreateForm(name: "Lea").canCreate)
        XCTAssertFalse(CreateForm(name: "Lea", saving: true).canCreate)
    }

    func testTheBackupFileIsNamedAfterTheLabelWithSafeCharactersOnly() {
        XCTAssertEqual(exportFileName("Lea"), "mumcord-identity-Lea.p12")
        XCTAssertEqual(exportFileName("Lea / Work"), "mumcord-identity-Lea---Work.p12")
        XCTAssertEqual(exportFileName("///"), "mumcord-identity.p12")
    }
}
