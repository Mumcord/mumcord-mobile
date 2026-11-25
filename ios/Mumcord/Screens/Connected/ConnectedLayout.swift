// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import SwiftUI

/// How the connected screens are laid out in the space the app has.
enum ConnectedLayout: Equatable {
    case oneColumn
    case twoColumns
}

/// From this width the app uses two columns (specification section 5, "600 dp / regular width").
/// The same number as on Android, in points: a full-screen iPad is wider in both orientations, a
/// narrow Split View or Slide Over window is not, and no iPhone is in portrait.
let twoColumnsMinWidth: CGFloat = 600

/// Below this height the app stays in one column even when it is wide: an iPhone turned to
/// landscape is wide but low (a Pro Max is 956 x 440), and its landscape stream must keep the
/// whole screen.
let twoColumnsMinHeight: CGFloat = 480

/// The layout for the space the app has right now: its whole window, which on an iPad can be a
/// part of the screen (Split View, Slide Over, Stage Manager). It is decided again on every
/// rotation and resize; the keyboard does not count, it does not change the window.
func connectedLayout(width: CGFloat, height: CGFloat) -> ConnectedLayout {
    width >= twoColumnsMinWidth && height >= twoColumnsMinHeight ? .twoColumns : .oneColumn
}

/// The destinations of the navigation rail; the phone's Chat tab is part of Channels there.
let railTabs: [ConnectedTab] = [.channels, .messages, .more]

extension ConnectedTab {
    /// The tab as two columns show it. The Chat tab of the phone is the right column of
    /// Channels, so a Chat tab chosen on the phone is shown as Channels; the tab itself is kept,
    /// so making the window narrow again shows the chat as before.
    var inTwoColumns: ConnectedTab { self == .chat ? .channels : self }
}

/// The channel chat is on screen: its own tab, or beside the channels in two columns.
func showsChannelChat(tab: ConnectedTab, twoColumns: Bool) -> Bool {
    tab == .chat || twoColumns && tab == .channels
}

/// The opened stream as two columns show it: in the right column of Channels (`full`), or
/// floating as the mini window above Messages and More, where the right column has other work.
func streamInColumn(_ open: OpenStream?, tab: ConnectedTab) -> Bool {
    open?.view == .full && tab.inTwoColumns == .channels
}

/// The opened stream covers the whole window: always its whole screen (`whole`), and on a phone
/// layout also `full`, which two columns show in the right column instead.
func streamCoversWindow(_ open: OpenStream?, twoColumns: Bool) -> Bool {
    switch open?.view {
    case .whole: true
    case .full: !twoColumns
    case .mini, nil: false
    }
}

private struct TwoColumnsKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// The window is laid out in two columns (see `connectedLayout`). Sheets read it to keep
    /// their width of the tablet mockups; it is set at the app's root for every screen.
    var twoColumns: Bool {
        get { self[TwoColumnsKey.self] }
        set { self[TwoColumnsKey.self] = newValue }
    }
}

extension View {
    /// Measures the window this view fills, keyboard and safe areas included, and reports
    /// whether it is laid out in two columns, at once and after every rotation or resize.
    func onConnectedLayoutChange(_ action: @escaping (Bool) -> Void) -> some View {
        background {
            GeometryReader { window in
                let size = window.size
                Color.clear
                    .onChange(of: connectedLayout(width: size.width, height: size.height), initial: true) { _, layout in
                        action(layout == .twoColumns)
                    }
            }
            .ignoresSafeArea()
            .accessibilityHidden(true)
        }
    }
}
