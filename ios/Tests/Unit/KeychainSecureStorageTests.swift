// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

final class KeychainSecureStorageTests: XCTestCase {
    // An own service name, so the tests never touch items of the app itself.
    private let storage = KeychainSecureStorage(service: "app.mumcord.mobile.tests")
    private let account = "identity/1/0"

    override func setUpWithError() throws {
        try storage.delete(account: account)
    }

    override func tearDownWithError() throws {
        try storage.delete(account: account)
    }

    func testGetReturnsWhatSetStored() throws {
        // Several kilobytes with every byte value, like a certificate with its private key.
        let secret = Data((0..<4096).map { UInt8($0 % 256) })

        try storage.set(account: account, secret: secret)

        XCTAssertEqual(try storage.get(account: account), secret)
    }

    func testSetReplacesAnExistingItem() throws {
        try storage.set(account: account, secret: Data([1, 2, 3]))
        try storage.set(account: account, secret: Data([4, 5]))

        XCTAssertEqual(try storage.get(account: account), Data([4, 5]))
    }

    func testDeleteRemovesTheItem() throws {
        try storage.set(account: account, secret: Data([1, 2, 3]))

        try storage.delete(account: account)

        XCTAssertNil(try storage.get(account: account))
    }

    func testMissingItemIsNilOnGetAndNoErrorOnDelete() throws {
        XCTAssertNil(try storage.get(account: account))
        XCTAssertNoThrow(try storage.delete(account: account))
    }

    func testAccountsAreKeptApart() throws {
        let other = "identity/2/0"
        addTeardownBlock { try self.storage.delete(account: other) }

        try storage.set(account: account, secret: Data([1]))
        try storage.set(account: other, secret: Data([2]))

        XCTAssertEqual(try storage.get(account: account), Data([1]))
        XCTAssertEqual(try storage.get(account: other), Data([2]))
    }
}
