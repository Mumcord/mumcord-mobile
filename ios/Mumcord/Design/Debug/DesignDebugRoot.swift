// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import SwiftUI

enum DesignDebugScreen: String {
    case gallery
    case channels
}

/// What a debug build shows instead of the app when it is started with
/// `-mumcordDesignScreen gallery|channels` and optionally `-mumcordDesignTheme dark|light|system`.
/// Release builds do not contain this, and nothing in the app leads here.
struct DesignDebugLaunch {
    let screen: DesignDebugScreen
    let theme: MumcordThemeMode

    /// Launch arguments of the form `-key value` are readable as user defaults.
    static func fromLaunchArguments(_ defaults: UserDefaults = .standard) -> DesignDebugLaunch? {
        guard
            let name = defaults.string(forKey: "mumcordDesignScreen"),
            let screen = DesignDebugScreen(rawValue: name)
        else {
            return nil
        }
        let theme = defaults.string(forKey: "mumcordDesignTheme").flatMap(MumcordThemeMode.init(rawValue:)) ?? .system
        return DesignDebugLaunch(screen: screen, theme: theme)
    }
}

/// The debug-only design screens: the component gallery and the channels reference screen.
struct DesignDebugRoot: View {
    @State private var screen: DesignDebugScreen
    @State private var theme: MumcordThemeMode

    init(launch: DesignDebugLaunch) {
        _screen = State(initialValue: launch.screen)
        _theme = State(initialValue: launch.theme)
    }

    var body: some View {
        Group {
            switch screen {
            case .gallery:
                GalleryScreen(theme: theme, onThemeChange: { theme = $0 }, onOpenChannels: { screen = .channels })
            case .channels:
                ChannelsReferenceScreen(onOpenGallery: { screen = .gallery })
            }
        }
        .mumcordTheme(theme)
    }
}

/// The component gallery: a theme switch, a way to the channels reference screen and every base
/// component of the design system.
struct GalleryScreen: View {
    let theme: MumcordThemeMode
    let onThemeChange: (MumcordThemeMode) -> Void
    let onOpenChannels: () -> Void

    var body: some View {
        let modes = MumcordThemeMode.allCases
        MumcordScreen {
            MumcordAppBar(title: "Components", large: true)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    MumcordSegmentedControl(
                        options: modes.map { $0.rawValue.capitalized },
                        selectedIndex: modes.firstIndex(of: theme) ?? 0,
                        onSelect: { onThemeChange(modes[$0]) }
                    )
                    .padding(.horizontal, MumcordSpace.s4)
                    .padding(.vertical, MumcordSpace.s2)
                    MumcordButton(text: "Channels reference screen", style: .secondary, fullWidth: true, action: onOpenChannels)
                        .padding(.horizontal, MumcordSpace.s4)
                    ComponentGallery()
                }
            }
        }
    }
}
#endif
