// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

/// Puts the identity screens into the state a screenshot shows (`-mumcordIdentities <preset>`),
/// through the same calls the screens make, so the tests reach states that need typing or a
/// file picked in the system's picker:
///
///     list               the identity list
///     file-unreadable    the list after a file that could not be read
///     details            the sheet of the identity "Work" (needs the option `two-identities`)
///     details-default    the sheet of the default identity
///     create             "New identity" with a name typed
///     import             the password question of a picked identity file
///     import-wrong       the same after a wrong password (needs the option `file-password`)
///     export             the backup sheet with the password typed twice
///     delete             the delete warning of "Work" (needs the option `two-identities`)
///     scan               "Code 3 of 7"
///     scan-foreign       a QR code of something else after three codes
///     scan-expired       a transfer older than two minutes (needs `transfer-expired`)
///     scan-code          all codes scanned, three digits typed
///     scan-checking      the code being checked (needs `transfer-slow`)
///     scan-wrong         a wrong code, two tries left
///     scan-rescan        the third wrong code
///     scan-preview       what the transfer brings, before "Import"
///     scan-import-failed the import failed (needs `transfer-import-fails`)
///     scan-done          what was imported and what was skipped
@MainActor
enum IdentitiesPreset {
    static func apply(_ preset: String, to identities: IdentitiesController) {
        Task { @MainActor in
            await run(preset, identities)
        }
    }

    private static func run(_ preset: String, _ identities: IdentitiesController) async {
        switch preset {
        case "list":
            identities.open()
        case "file-unreadable":
            identities.open()
            identities.importFile(nil)
        case "details":
            identities.open()
            identities.openDetails(workIdentity.id)
        case "details-default":
            identities.open()
            identities.openDetails(fakeIdentity.id)
        case "create":
            identities.open()
            identities.openCreate()
            identities.changeCreateName("Lea")
        case "import", "import-wrong":
            identities.open()
            identities.importFile(Data("fake identity file".utf8))
            if preset == "import-wrong" {
                identities.changeImportPassword("not the password")
                identities.confirmImport()
            }
        case "export":
            identities.open()
            identities.openExport(fakeIdentity.id)
            identities.changeExportPassword("backup")
            identities.changeExportRepeat("backup")
        case "delete":
            identities.open()
            identities.openDelete(workIdentity.id)
        default:
            await scan(preset, identities)
        }
    }

    private static func scan(_ preset: String, _ identities: IdentitiesController) async {
        identities.openScan()
        await until { identities.scanReady }
        let parts = FakeDesktopTransfer.parts
        switch preset {
        case "scan":
            parts.prefix(3).forEach(identities.onQrText)
        case "scan-foreign":
            parts.prefix(3).forEach(identities.onQrText)
            identities.onQrText(FakeDesktopTransfer.foreignText)
        case "scan-expired":
            identities.onQrText(parts[0])
        default:
            parts.forEach(identities.onQrText)
            await code(preset, identities)
        }
    }

    private static func code(_ preset: String, _ identities: IdentitiesController) async {
        switch preset {
        case "scan-code":
            identities.typeCode(String(FakeDesktopTransfer.code.prefix(3)))
        case "scan-checking":
            identities.typeCode(FakeDesktopTransfer.code)
        case "scan-wrong", "scan-rescan":
            for _ in 0..<(preset == "scan-wrong" ? 1 : 3) {
                await until { isCode(identities) }
                identities.typeCode(FakeDesktopTransfer.wrongCode)
                await until { !isChecking(identities) }
            }
        default:
            identities.typeCode(FakeDesktopTransfer.code)
            await until { isPreview(identities) }
            if preset == "scan-import-failed" || preset == "scan-done" {
                identities.importTransfer()
            }
        }
    }

    private static func isCode(_ identities: IdentitiesController) -> Bool {
        if case .code = identities.local.scan?.step { return true }
        return false
    }

    private static func isChecking(_ identities: IdentitiesController) -> Bool {
        if case .checking = identities.local.scan?.step { return true }
        return false
    }

    private static func isPreview(_ identities: IdentitiesController) -> Bool {
        if case .preview = identities.local.scan?.step { return true }
        return false
    }

    private static func until(_ condition: () -> Bool) async {
        var tries = 0
        while !condition() && tries < 200 {
            tries += 1
            try? await Task.sleep(for: .milliseconds(50))
        }
    }
}

/// The second identity of the option `two-identities`, used by one saved server.
let workIdentity = Identity(
    id: 2,
    label: "Work",
    commonName: "Lea Berger",
    fingerprint: "b31d09e2c4a7f655".padding(toLength: 40, withPad: "1", startingAt: 0),
    createdMs: 1_763_000_000_000,
    isDefault: false,
    serverIds: []
)
#endif
