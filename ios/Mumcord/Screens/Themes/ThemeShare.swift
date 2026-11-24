// SPDX-License-Identifier: AGPL-3.0-only

import UIKit

/// Hands a theme's share message (`🎨 Mumcord theme "…" – mct1.…`) to the system's share sheet,
/// for a chat, a messenger or the clipboard. Shown over whatever the app shows on top.
@MainActor
func presentShareSheet(_ message: String) {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    guard var top = scene?.keyWindow?.rootViewController else {
        AppLog.write("share theme failed: no window to show the share sheet in")
        return
    }
    while let presented = top.presentedViewController {
        top = presented
    }
    let sheet = UIActivityViewController(activityItems: [message], applicationActivities: nil)
    // On an iPad the sheet is a popover and needs a place to point at.
    if let popover = sheet.popoverPresentationController {
        popover.sourceView = top.view
        popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
        popover.permittedArrowDirections = []
    }
    top.present(sheet, animated: true)
}
