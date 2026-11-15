// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

final class ServerFormStateTests: XCTestCase {
    private let filled = ServerFormState(address: "nordlicht.example", username: "Me")

    private func changing(_ change: (inout ServerFormState) -> Void) -> ServerFormState {
        var form = filled
        change(&form)
        return form
    }

    func testANewFormStartsWithTheStandardPortAndIsNotValid() {
        let form = ServerFormState()

        XCTAssertEqual(form.port, "64738")
        XCTAssertFalse(form.isEdit)
        XCTAssertFalse(form.isValid)
        XCTAssertTrue(form.addressMissing)
        XCTAssertTrue(form.usernameMissing)
        XCTAssertNil(form.toInput())
    }

    func testPortsOutsideTheValidRangeAreRejected() {
        XCTAssertNil(changing { $0.port = "" }.portNumber)
        XCTAssertNil(changing { $0.port = "0" }.portNumber)
        XCTAssertNil(changing { $0.port = "65536" }.portNumber)
        XCTAssertNil(changing { $0.port = "12a" }.portNumber)
        XCTAssertNil(changing { $0.port = "-1" }.portNumber)
        XCTAssertEqual(changing { $0.port = " 65535 " }.portNumber, 65535)
        XCTAssertFalse(changing { $0.port = "0" }.isValid)
    }

    func testAddressAndUserNameAreRequiredAndTheNameIsNot() {
        XCTAssertFalse(changing { $0.address = " " }.isValid)
        XCTAssertFalse(changing { $0.username = " " }.isValid)
        XCTAssertTrue(changing { $0.name = "" }.isValid)
    }

    func testTheInputIsTrimmedAndANewServerWithoutPasswordKeepsNone() {
        let form = ServerFormState(name: " Nordlicht ", address: " nordlicht.example ", port: " 40000 ", username: " Me ")

        XCTAssertEqual(
            form.toInput(),
            ServerInput(
                name: "Nordlicht",
                address: "nordlicht.example",
                port: 40000,
                username: "Me",
                password: .keep,
                identityId: nil
            )
        )
    }

    func testATypedPasswordIsSetExactlyAsTyped() {
        XCTAssertEqual(changing { $0.password = " pass word " }.toInput()?.password, .set(value: " pass word "))
    }

    func testEditingKeepsTheSavedPasswordUnlessItIsReplacedOrRemoved() {
        let form = ServerFormState.edit(savedServer(7, "Nordlicht", hasPassword: true))

        XCTAssertTrue(form.isEdit)
        XCTAssertTrue(form.hasSavedPassword)
        // The saved password itself is never part of the form.
        XCTAssertEqual(form.password, "")
        XCTAssertEqual(form.toInput()?.password, .keep)
        var removed = form
        removed.removeSavedPassword = true
        XCTAssertEqual(removed.toInput()?.password, .clear)
        removed.password = "new"
        XCTAssertEqual(removed.toInput()?.password, .set(value: "new"))
    }

    func testEditingStartsFromTheSavedEntry() {
        let form = ServerFormState.edit(
            savedServer(7, "Nordlicht", address: "n.example", port: 1234, username: "Anna")
        )

        XCTAssertEqual(
            form,
            ServerFormState(serverId: 7, name: "Nordlicht", address: "n.example", port: "1234", username: "Anna")
        )
    }
}
