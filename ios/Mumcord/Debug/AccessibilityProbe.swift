// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import SwiftUI
import UIKit

/// What VoiceOver can reach on the app's screen, for the UI tests: XCUITest lists every view,
/// also those VoiceOver skips, and cannot see traits such as "heading". Started with
/// `-mumcordAccessibilityProbe YES`, a window of one point of its own (so no sheet of the app
/// hides it) carries an element "accessibility-probe" whose value lists the elements of the
/// app's window in the order VoiceOver walks them, one per line:
///
///     <flags>|<label>|<value>|<x>,<y>,<width>,<height>
///
/// (the frame in screen points, rounded)
/// with the flags `H` heading, `B` button, `S` selected, `A` adjustable and `M` inside a modal
/// element. The walk follows the accessibility containers as VoiceOver does: hidden elements
/// and their children are left out, an element's children are not visited, and of the children
/// of a container only the last modal one counts when there is one.
@MainActor
final class AccessibilityProbe {
    private static var window: UIWindow?
    private static var timer: Timer?

    static var requested: Bool {
        UserDefaults.standard.bool(forKey: "mumcordAccessibilityProbe")
    }

    static func install() {
        guard window == nil,
              let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first
        else { return }
        let probe = UIWindow(windowScene: scene)
        probe.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        probe.windowLevel = .alert + 1
        probe.isUserInteractionEnabled = false
        let element = UIView()
        element.isAccessibilityElement = true
        element.accessibilityLabel = "Accessibility probe"
        element.accessibilityIdentifier = "accessibility-probe"
        let controller = UIViewController()
        controller.view = element
        probe.rootViewController = controller
        probe.isHidden = false
        window = probe
        timer = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { _ in
            MainActor.assumeIsolated {
                guard let app = scene.windows.first(where: { $0 !== probe && !$0.isHidden }) else { return }
                element.accessibilityValue = lines(of: app).joined(separator: "\n")
            }
        }
    }

    static func lines(of root: NSObject) -> [String] {
        var lines: [String] = []
        visit(root, modal: false, into: &lines)
        return lines
    }

    private static func visit(_ object: NSObject, modal: Bool, into lines: inout [String]) {
        if object.accessibilityElementsHidden { return }
        if let view = object as? UIView, view.isHidden || view.alpha == 0 { return }
        let inModal = modal || object.accessibilityViewIsModal
        if object.isAccessibilityElement {
            lines.append(line(object, modal: inModal))
            return
        }
        let children = children(of: object)
        let modalChild = children.last { $0.accessibilityViewIsModal }
        for child in modalChild.map({ [$0] }) ?? children {
            visit(child, modal: inModal, into: &lines)
        }
    }

    private static func children(of object: NSObject) -> [NSObject] {
        if let elements = object.accessibilityElements {
            return elements.compactMap { $0 as? NSObject }
        }
        let count = object.accessibilityElementCount()
        if count != NSNotFound, count > 0 {
            return (0..<count).compactMap { object.accessibilityElement(at: $0) as? NSObject }
        }
        return (object as? UIView)?.subviews ?? []
    }

    private static func line(_ object: NSObject, modal: Bool) -> String {
        let traits = object.accessibilityTraits
        var flags = ""
        if traits.contains(.header) { flags += "H" }
        if traits.contains(.button) { flags += "B" }
        if traits.contains(.selected) { flags += "S" }
        if traits.contains(.adjustable) { flags += "A" }
        if modal { flags += "M" }
        let label = (object.accessibilityLabel ?? "").replacingOccurrences(of: "\n", with: " ")
        let value = (object.accessibilityValue ?? "").replacingOccurrences(of: "\n", with: " ")
        let frame = object.accessibilityFrame
        let box = [frame.minX, frame.minY, frame.width, frame.height].map { $0.isFinite ? String(Int($0.rounded())) : "0" }.joined(separator: ",")
        return "\(flags)|\(label)|\(value)|\(box)"
    }
}
#endif
