// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The system's file picker for an identity file and its share sheet for a backup. Neither
/// needs a permission: the picker hands over only the file the user chose.
struct IdentityFiles: ViewModifier {
    @Binding var picking: Bool
    let exported: ExportedIdentity?
    let identities: IdentitiesController

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $picking, allowedContentTypes: identityFileTypes) { result in
                switch result {
                case .success(let url):
                    Task { @MainActor in
                        let data = await Task.detached { readIdentityFile(url) }.value
                        identities.importFile(data)
                    }
                case .failure:
                    identities.importFile(nil)
                }
            }
            .sheet(item: Binding(get: { exported }, set: { if $0 == nil { identities.exportShared() } })) { file in
                BackupShareSheet(file: file, onDone: identities.exportShared)
            }
    }
}

/// `.p12` and `.pfx`, the files the desktop app and other Mumble clients export.
private let identityFileTypes: [UTType] = [.pkcs12] + [UTType(filenameExtension: "pfx")].compactMap { $0 }

/// The bytes of a picked identity file, or nil when it cannot be read or is far too large for an
/// identity.
private func readIdentityFile(_ url: URL) -> Data? {
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > maxIdentityFileBytes { return nil }
    guard let data = try? Data(contentsOf: url), !data.isEmpty, data.count <= maxIdentityFileBytes else { return nil }
    return data
}

/// The system's share sheet with the backup as a `.p12` file. The file lives in a folder of its
/// own in the app's temporary folder only while the sheet is open, protected while the phone is
/// locked, and is deleted when the sheet closes.
private struct BackupShareSheet: UIViewControllerRepresentable {
    let file: ExportedIdentity
    let onDone: () -> Void

    func makeCoordinator() -> BackupFile {
        BackupFile()
    }

    func makeUIViewController(context: Context) -> UIViewController {
        guard let url = context.coordinator.write(file) else {
            // Nothing to share; the sheet closes again.
            DispatchQueue.main.async { onDone() }
            return UIViewController()
        }
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        let coordinator = context.coordinator
        controller.completionWithItemsHandler = { _, _, _, _ in
            coordinator.remove()
            onDone()
        }
        return controller
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: BackupFile) {
        coordinator.remove()
    }
}

private final class BackupFile {
    private var folder: URL?

    func write(_ file: ExportedIdentity) -> URL? {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let url = folder.appendingPathComponent(file.fileName)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try file.bytes.write(to: url, options: [.completeFileProtection])
            self.folder = folder
            return url
        } catch {
            try? FileManager.default.removeItem(at: folder)
            // Only the kind of failure: the file name carries the identity's label.
            AppLog.write("writing the identity backup failed: \(type(of: error))")
            return nil
        }
    }

    func remove() {
        guard let folder else { return }
        self.folder = nil
        do {
            try FileManager.default.removeItem(at: folder)
        } catch {
            AppLog.write("removing the identity backup failed: \(type(of: error))")
        }
    }
}
