// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import Observation

/// The largest text taken from a QR code; a part of a transfer is far smaller.
let maxQrCharacters = 4096

/// The largest identity file that is read; an identity file is a few kilobytes.
let maxIdentityFileBytes = 1024 * 1024

/// The identity screens and the scan of a transfer from the desktop app: their own state and
/// what the user does on them. Key material never comes here; the typed code and the passwords
/// of identity files only pass through, in memory, and none of them is ever logged.
///
/// An object of its own, apart from `AppModel`, like the Messages tab.
@MainActor
@Observable
final class IdentitiesController {
    private(set) var local = IdentitiesLocal()
    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let camera: CameraGateway
    /// The picked identity file while its password is asked for.
    @ObservationIgnored private var importData: Data?
    /// The bridge's side of the open scan.
    @ObservationIgnored private var transfer: TransferSession?
    /// The QR text refused last: the camera sees it many times a second.
    @ObservationIgnored private var refusedText: String?

    init(store: SessionStore, camera: CameraGateway) {
        self.store = store
        self.camera = camera
    }

    private var identities: [Identity] { store.state.identities ?? [] }

    func open() {
        local.open = true
    }

    func close() {
        local.open = false
        local.sheet = nil
        local.unreadableFile = false
    }

    func closeSheet() {
        importData = nil
        // "Back up first" came from the delete warning and returns to it.
        if case .export(let form) = local.sheet, form.fromDelete {
            local.sheet = .delete(DeleteForm(identityId: form.identityId))
        } else {
            local.sheet = nil
        }
    }

    func openDetails(_ identityId: Int64) {
        local.sheet = .details(identityId: identityId)
    }

    // MARK: Create

    func openCreate() {
        local.sheet = .create(CreateForm())
        local.unreadableFile = false
    }

    func changeCreateName(_ name: String) {
        guard case .create(var form) = local.sheet else { return }
        form.name = String(name.prefix(maxIdentityName))
        form.error = nil
        local.sheet = .create(form)
    }

    func create() {
        guard case .create(var form) = local.sheet, !form.saving else { return }
        let name = form.name.trimmingCharacters(in: .whitespaces)
        if name.isEmpty { return }
        form.saving = true
        form.error = nil
        local.sheet = .create(form)
        Task {
            let created = await store.call("create identity") { try await $0.createIdentity(name: name, label: nil) }
            guard case .create(var form) = local.sheet else { return }
            switch created {
            case .ok:
                local.sheet = nil
            case .failed(let error):
                form.saving = false
                form.error = error
                local.sheet = .create(form)
            }
        }
    }

    // MARK: Import a file

    /// The bytes of the file the user picked, or nil when it could not be read.
    func importFile(_ data: Data?) {
        importData = data
        if data == nil {
            local.unreadableFile = true
        } else {
            local.sheet = .importFile(ImportForm())
            local.unreadableFile = false
        }
    }

    func changeImportPassword(_ password: String) {
        guard case .importFile(var form) = local.sheet else { return }
        form.password = password
        form.problem = nil
        local.sheet = .importFile(form)
    }

    func confirmImport() {
        guard case .importFile(var form) = local.sheet, !form.saving, let data = importData else { return }
        let password = form.password
        form.saving = true
        form.problem = nil
        local.sheet = .importFile(form)
        Task {
            let imported = await store.call("import identity") {
                try await $0.importIdentity(data: data, password: password, label: nil)
            }
            guard case .importFile(var form) = local.sheet else { return }
            switch imported {
            case .ok:
                importData = nil
                local.sheet = nil
            case .failed(let error):
                form.saving = false
                form.password = ""
                form.problem = switch error {
                case .invalidInput: .wrongPasswordOrFile
                case .duplicateIdentity: .duplicate
                default: .failed
                }
                local.sheet = .importFile(form)
            }
        }
    }

    // MARK: Default, export, delete

    func setDefault(_ identityId: Int64) {
        Task {
            // A failure is logged by the store; the list simply stays as it was.
            _ = await store.call("set default identity") { try await $0.setDefaultIdentity(identityId: identityId) }
        }
    }

    func openExport(_ identityId: Int64) {
        var fromDelete = false
        if case .delete = local.sheet { fromDelete = true }
        local.sheet = .export(ExportForm(identityId: identityId, fromDelete: fromDelete))
    }

    func changeExportPassword(_ password: String) {
        guard case .export(var form) = local.sheet else { return }
        form.password = password
        form.failed = false
        local.sheet = .export(form)
    }

    func changeExportRepeat(_ repeated: String) {
        guard case .export(var form) = local.sheet else { return }
        form.repeated = repeated
        form.failed = false
        local.sheet = .export(form)
    }

    /// Makes the backup; it then waits in `local.exported` for the share sheet.
    func export() {
        guard case .export(var form) = local.sheet, form.canExport,
              let label = identities.first(where: { $0.id == form.identityId })?.label else { return }
        let (identityId, password) = (form.identityId, form.password)
        form.saving = true
        form.failed = false
        local.sheet = .export(form)
        Task {
            let exported = await store.call("export identity") {
                try await $0.exportIdentity(identityId: identityId, password: password)
            }
            guard case .export(var form) = local.sheet else { return }
            switch exported {
            case .ok(let bytes):
                local.sheet = form.fromDelete ? .delete(DeleteForm(identityId: identityId)) : nil
                local.exported = ExportedIdentity(fileName: exportFileName(label), bytes: bytes)
            case .failed:
                form.saving = false
                form.password = ""
                form.repeated = ""
                form.failed = true
                local.sheet = .export(form)
            }
        }
    }

    /// The backup was handed to the share sheet.
    func exportShared() {
        local.exported = nil
    }

    func openDelete(_ identityId: Int64) {
        local.sheet = .delete(DeleteForm(identityId: identityId))
    }

    func changeDeleteConfirmation(_ typed: String) {
        guard case .delete(var form) = local.sheet else { return }
        form.typed = typed
        form.error = nil
        local.sheet = .delete(form)
    }

    func delete() {
        guard case .delete(var form) = local.sheet, !form.deleting,
              let label = identities.first(where: { $0.id == form.identityId })?.label,
              identities.count > 1,
              form.typed.trimmingCharacters(in: .whitespaces) == label else { return }
        let identityId = form.identityId
        form.deleting = true
        form.error = nil
        local.sheet = .delete(form)
        Task {
            let deleted = await store.call("delete identity") { try await $0.deleteIdentity(identityId: identityId) }
            guard case .delete(var form) = local.sheet else { return }
            switch deleted {
            case .ok:
                local.sheet = nil
            case .failed(let error):
                form.deleting = false
                form.error = error
                local.sheet = .delete(form)
            }
        }
    }

    // MARK: Scan from the desktop app

    func openScan() {
        if local.scan != nil { return }
        refusedText = nil
        local.scan = ScanState(camera: cameraNotice(camera.permission()))
        local.unreadableFile = false
        Task {
            let started = await store.call("start transfer") { try $0.startTransfer() }
            switch started {
            case .ok(let scan):
                // Closed again before the engine was there.
                if local.scan == nil { release(scan) } else { transfer = scan }
            case .failed:
                updateScan { $0.note = .failed }
            }
        }
    }

    func closeScan() {
        local.scan = nil
        if let transfer { release(transfer) }
        transfer = nil
    }

    /// Forgets the scan in the bridge. Not on the main actor: it waits for a check of the code
    /// that is still running.
    private func release(_ scan: TransferSession) {
        Task.detached { scan.reset() }
    }

    /// The text of a QR code the camera sees; called for the same code again and again.
    func onQrText(_ text: String) {
        guard let scan = transfer, local.scan?.scanning == true, text != refusedText else { return }
        if text.count > maxQrCharacters {
            refusedText = text
            updateScan { $0.note = .notATransfer }
            return
        }
        do {
            do {
                _ = try scan.addPart(text: text)
            } catch TransferError.OtherTransfer {
                // The desktop made a new transfer: what was collected belongs to the old one.
                scan.reset()
                _ = try scan.addPart(text: text)
            }
            refusedText = nil
            let standing = standing(of: scan)
            updateScan { $0 = $0.afterPart(standing) }
        } catch let error as TransferError {
            refusedText = text
            updateScan { $0 = $0.afterPartRefused(error) }
        } catch {
            refusedText = text
            updateScan { $0.note = .failed }
        }
    }

    /// The phone has no camera that can be used.
    func cameraFailed() {
        updateScan { $0.note = .noCamera }
    }

    /// What the user typed into the code field; the check starts with the last digit.
    func typeCode(_ text: String) {
        guard let scan = transfer, case .code(var entry) = local.scan?.step else { return }
        let typed = codeInput(text, digits: entry.digits)
        if typed.count < entry.digits {
            entry.wrong = entry.wrong && typed.isEmpty
            entry.typed = typed
            updateScan {
                $0.step = .code(entry)
                $0.note = nil
            }
            return
        }
        updateScan {
            $0.step = .checking(digits: entry.digits, attemptsLeft: entry.attemptsLeft)
            $0.note = nil
        }
        // The bridge checks the code on its own threads; this task only waits for the answer.
        Task {
            let next: (inout ScanState) -> Void
            do {
                let preview = try await scan.open(code: typed)
                next = {
                    $0.step = preview.step
                    $0.note = nil
                }
            } catch let error as TransferError {
                let standing = standing(of: scan)
                next = { $0 = $0.afterCodeRefused(error, standing) }
            } catch {
                let standing = standing(of: scan)
                next = { $0 = $0.afterCodeRefused(.Failed, standing) }
            }
            // The screen may have been closed while the code was checked.
            if transfer === scan { updateScan(next) }
        }
    }

    /// The user confirmed what the preview lists.
    func importTransfer() {
        guard let scan = transfer, case .preview(var contents) = local.scan?.step, !contents.importing else { return }
        contents.importing = true
        contents.error = nil
        let importing = contents
        updateScan { $0.step = .preview(importing) }
        Task {
            var failed = importing
            failed.importing = false
            let next: ScanStep
            do {
                next = try await scan.importOpened().step
            } catch let error as BridgeError {
                failed.error = error.connectionError
                next = .preview(failed)
            } catch {
                failed.error = .internalError
                next = .preview(failed)
            }
            if transfer === scan { updateScan { $0.step = next } }
        }
    }

    private func standing(of scan: TransferSession) -> ScanStanding {
        ScanStanding(progress: scan.progress(), codeDigits: Int(scan.codeDigits()), attemptsLeft: Int(scan.attemptsLeft()))
    }

    private func updateScan(_ change: (inout ScanState) -> Void) {
        guard var scan = local.scan else { return }
        change(&scan)
        local.scan = scan
    }

    #if DEBUG
    /// Debug launches and tests wait for the bridge's scan before they hand in texts.
    var scanReady: Bool { transfer != nil }
    #endif

    // MARK: Camera permission

    /// The user put the explanation away without letting the system ask.
    func cameraNotNow() {
        updateScan { $0.camera = .denied }
    }

    /// Lets the system ask for the camera.
    func allowCamera() {
        camera.request { granted in
            Task { @MainActor [weak self] in self?.cameraAnswered(granted) }
        }
    }

    private func cameraAnswered(_ granted: Bool) {
        updateScan { $0.camera = granted ? nil : cameraNotice(.denied) }
    }

    /// The app is on screen again: the permission may have changed in the system settings.
    func recheckCamera() {
        guard local.scan?.camera != nil, camera.permission() == .granted else { return }
        updateScan { $0.camera = nil }
    }
}
