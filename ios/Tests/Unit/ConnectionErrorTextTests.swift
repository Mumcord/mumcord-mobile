// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

final class ConnectionErrorTextTests: XCTestCase {
    func testTheErrorsOfTheConnectFlowHaveTheirOwnSentence() {
        XCTAssertEqual(ConnectionError.serverUnreachable.message, Strings.errorServerUnreachable)
        XCTAssertEqual(ConnectionError.wrongPassword.message, Strings.errorWrongPassword)
        XCTAssertEqual(ConnectionError.nameInUse.message, Strings.errorNameInUse)
        XCTAssertEqual(ConnectionError.certificateRejected.message, Strings.errorCertificateRejected)
        XCTAssertEqual(ConnectionError.certificateChanged.message, Strings.errorCertificateChanged)
        XCTAssertEqual(ConnectionError.banned.message, Strings.errorBanned)
    }

    func testEveryErrorHasASentenceOfItsOwn() {
        let messages = ConnectionError.allCases.map(\.message)

        XCTAssertEqual(Set(messages).count, ConnectionError.allCases.count)
    }
}
