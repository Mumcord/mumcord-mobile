// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The longest name inside a new certificate.
let maxIdentityName = 64

/// One identity on the list (mockup `17-identity`).
struct IdentityRow: Equatable, Identifiable {
    let id: Int64
    let label: String
    let isDefault: Bool
    /// Saved servers that connect with it: by name, or for the default one also by naming none.
    let serverCount: Int
    /// The start of the certificate hash, as on the card.
    let fingerprintStart: String
}

/// What the sheets of one identity show.
struct IdentityDetails: Equatable {
    let id: Int64
    let label: String
    let commonName: String
    let fingerprint: String
    let createdMs: Int64
    let isDefault: Bool
    let serverCount: Int
    /// False for the only identity: the app always needs one.
    let canDelete: Bool
    /// The identity that becomes the default when this default one is deleted.
    let nextDefaultLabel: String?
}

enum ImportProblem: Equatable {
    /// The password is wrong or the file holds no identity; the engine cannot tell them apart yet.
    case wrongPasswordOrFile
    case duplicate
    case failed
}

/// The open sheet as the controller keeps it: only ids and what was typed.
enum IdentitySheetLocal: Equatable {
    case details(identityId: Int64)
    case create(CreateForm)
    case importFile(ImportForm)
    case export(ExportForm)
    case delete(DeleteForm)
}

struct CreateForm: Equatable {
    var name = ""
    var saving = false
    var error: ConnectionError?
}

struct ImportForm: Equatable {
    var password = ""
    var saving = false
    var problem: ImportProblem?
}

/// `fromDelete`: opened by "Back up first", so closing it returns to the delete warning.
struct ExportForm: Equatable {
    let identityId: Int64
    var password = ""
    var repeated = ""
    var saving = false
    var failed = false
    var fromDelete = false

    var mismatch: Bool { !repeated.isEmpty && password != repeated }

    /// A backup without a password is not allowed (Spec 03 FR-3.8).
    var canExport: Bool { !password.isEmpty && password == repeated && !saving }
}

struct DeleteForm: Equatable {
    let identityId: Int64
    var typed = ""
    var deleting = false
    var error: ConnectionError?
}

/// The open sheet with what it shows about its identity.
enum IdentitySheet: Equatable {
    case details(IdentityDetails)
    case create(CreateForm)
    case importFile(ImportForm)
    case export(IdentityDetails, ExportForm)
    /// `defaultLabel`: the default identity, which the servers of a deleted other one fall back to.
    case delete(IdentityDetails, DeleteForm, defaultLabel: String)
}

extension CreateForm {
    var canCreate: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && !saving }
}

/// The label has to be typed exactly (Spec 03 FR-3.9).
func canDelete(_ identity: IdentityDetails, _ form: DeleteForm) -> Bool {
    identity.canDelete && form.typed.trimmingCharacters(in: .whitespaces) == identity.label && !form.deleting
}

/// A backup waiting for the share sheet.
struct ExportedIdentity: Equatable, Identifiable {
    let id = UUID()
    let fileName: String
    let bytes: Data
}

/// The controller's own part of the identity screens.
struct IdentitiesLocal: Equatable {
    /// The identity list is open (from the More tab).
    var open = false
    var sheet: IdentitySheetLocal?
    /// A picked file that was not even read; shown on the screen, not in a sheet.
    var unreadableFile = false
    var exported: ExportedIdentity?
    /// The open "Scan from desktop app" screen.
    var scan: ScanState?
}

struct IdentitiesUiState: Equatable {
    var open = false
    /// False until the identities were read.
    var known = false
    var rows: [IdentityRow] = []
    var sheet: IdentitySheet?
    var unreadableFile = false
    var exported: ExportedIdentity?
    var scan: ScanState?
}

func identitiesUiState(session: SessionState, local: IdentitiesLocal) -> IdentitiesUiState {
    let identities = session.identities ?? []
    func details(_ id: Int64) -> IdentityDetails? {
        identities.first { $0.id == id }.map { identityDetails($0, all: identities, servers: session.servers) }
    }
    var sheet: IdentitySheet?
    switch local.sheet {
    case nil:
        sheet = nil
    case .create(let form):
        sheet = .create(form)
    case .importFile(let form):
        sheet = .importFile(form)
    case .details(let id):
        sheet = details(id).map { .details($0) }
    case .export(let form):
        sheet = details(form.identityId).map { .export($0, form) }
    case .delete(let form):
        let defaultLabel = identities.first { $0.isDefault }?.label ?? ""
        sheet = details(form.identityId).map { .delete($0, form, defaultLabel: defaultLabel) }
    }
    return IdentitiesUiState(
        open: local.open,
        known: session.identities != nil,
        rows: identities.map { identity in
            IdentityRow(
                id: identity.id,
                label: identity.label,
                isDefault: identity.isDefault,
                serverCount: serverCount(identity, session.servers),
                fingerprintStart: fingerprintStart(identity.fingerprint)
            )
        },
        sheet: sheet,
        unreadableFile: local.unreadableFile,
        exported: local.exported,
        scan: local.scan
    )
}

private func identityDetails(_ identity: Identity, all: [Identity], servers: [SavedServer]) -> IdentityDetails {
    IdentityDetails(
        id: identity.id,
        label: identity.label,
        commonName: identity.commonName,
        fingerprint: fingerprintGroups(identity.fingerprint),
        createdMs: identity.createdMs,
        isDefault: identity.isDefault,
        serverCount: serverCount(identity, servers),
        canDelete: all.count > 1,
        nextDefaultLabel: identity.isDefault ? nextDefault(identity, all)?.label : nil
    )
}

private func serverCount(_ identity: Identity, _ servers: [SavedServer]) -> Int {
    servers.filter { $0.identityId == identity.id || (identity.isDefault && $0.identityId == nil) }.count
}

private func nextDefault(_ deleted: Identity, _ all: [Identity]) -> Identity? {
    all.filter { $0.id != deleted.id }.min { ($0.createdMs, $0.id) < ($1.createdMs, $1.id) }
}

private let startBytes = 8

/// "4F:A9:1C:7E:D2:08:B3:6A", the first bytes of the hash as on the card.
func fingerprintStart(_ fingerprint: String) -> String {
    chunks(fingerprint.uppercased(), of: 2).prefix(startBytes).joined(separator: ":")
}

/// The whole hash in groups of four, as in the details.
func fingerprintGroups(_ fingerprint: String) -> String {
    chunks(fingerprint.uppercased(), of: 4).joined(separator: " ")
}

private func chunks(_ text: String, of size: Int) -> [String] {
    var result: [String] = []
    var rest = Substring(text)
    while !rest.isEmpty {
        result.append(String(rest.prefix(size)))
        rest = rest.dropFirst(size)
    }
    return result
}

private let maxFileLabel = 40

/// The name of a backup file: the label with letters, digits, `-` and `_` only.
func exportFileName(_ label: String) -> String {
    let safe = String(label.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "-" })
        .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        .prefix(maxFileLabel)
    return safe.isEmpty ? "mumcord-identity.p12" : "mumcord-identity-\(safe).p12"
}
