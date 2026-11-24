// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

private let gridColumns = 2
private let tileRadius: CGFloat = 16
private let sampleRadius: CGFloat = 10
private let tileSampleHeight: CGFloat = 64
private let dotSize: CGFloat = 20
/// System, Dark and Light in the order of the pill track.
private let modes: [ThemeMode] = [.system, .dark, .light]

/// The accessibility identifier of a theme's tile, by the theme's id.
func themeTileIdentifier(_ themeId: String) -> String { "themes-tile-" + themeId }

/// The accessibility identifier of an appearance choice.
func themeModeIdentifier(_ mode: ThemeMode) -> String {
    switch mode {
    case .system: "themes-mode-system"
    case .dark: "themes-mode-dark"
    case .light: "themes-mode-light"
    }
}

func themeModeText(_ mode: ThemeMode) -> String {
    switch mode {
    case .system: Strings.themesModeSystem
    case .dark: Strings.themesModeDark
    case .light: Strings.themesModeLight
    }
}

/// Themes (mockup `19-themes`, FR-5.81): the appearance (System, Dark, Light), the built-in
/// themes and the user's own ones as tiles, and "Paste theme code". A tap applies a theme; a long
/// press (or VoiceOver's actions) offers sharing it and, for the user's own, deleting it. The
/// mockup's "Use server theme when offered" is left out: there is no theme a server offers; a
/// server's own theme is picked in its settings.
struct ThemesScreen: View {
    let state: ThemesUiState
    let actions: ThemesController
    let onBack: () -> Void
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        // The tiles show the version of each theme that the app shows now.
        let variant = themeVariant(state.mode, systemDark: systemScheme == .dark)
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.themesTitle, onBack: onBack, backLabel: Strings.actionBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    MumcordSectionHeader(title: Strings.themesAppearance)
                    ModeChoice(mode: state.mode, onSelect: actions.setMode)
                    MumcordSectionHeader(title: Strings.themesTheme)
                    grid(state.builtIn, variant: variant)
                    if !state.custom.isEmpty {
                        MumcordSectionHeader(title: Strings.themesYours)
                        grid(state.custom, variant: variant)
                    }
                    if let serverTheme = state.serverTheme {
                        // Both names are plain characters: the theme's from whoever made it.
                        ParagraphText(
                            Strings.themesServerNote(state.serverName, serverTheme.name),
                            style: .small,
                            color: theme.colors.textMuted
                        )
                        .padding(.horizontal, MumcordSpace.s4)
                        .padding(.top, MumcordSpace.s3)
                        .accessibilityIdentifier("themes-server-note")
                    }
                    MumcordList {
                        MumcordListRow(
                            title: Strings.themesPaste,
                            icon: MumcordIcons.link,
                            action: actions.openPaste,
                            trailing: {
                                MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: theme.colors.textMuted)
                            }
                        )
                        .accessibilityIdentifier("themes-paste")
                    }
                    .padding(.top, MumcordSpace.s4)
                }
                .padding(.bottom, MumcordSpace.s4)
            }
        }
        .onChange(of: actions.local.shareText, initial: true) { _, text in
            // A theme to share goes to the share sheet once.
            guard let text else { return }
            presentShareSheet(text)
            actions.shared()
        }
    }

    private func grid(_ themes: [Theme], variant: ThemeVariant) -> some View {
        VStack(spacing: MumcordSpace.s3) {
            ForEach(Array(stride(from: 0, to: themes.count, by: gridColumns)), id: \.self) { start in
                HStack(alignment: .top, spacing: MumcordSpace.s3) {
                    ForEach(themes[start..<min(start + gridColumns, themes.count)], id: \.id) { item in
                        ThemeTile(
                            theme: item,
                            selected: item.id == state.appThemeId,
                            variant: variant,
                            actions: actions
                        )
                    }
                    // A last row with one tile keeps the tile at half the width.
                    if themes.count - start < gridColumns {
                        Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                    }
                }
            }
        }
        .padding(.horizontal, MumcordSpace.s4)
    }
}

/// System, Dark and Light as one pill track (`.seg` of the mockup).
private struct ModeChoice: View {
    let mode: ThemeMode
    let onSelect: (ThemeMode) -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: 0) {
            ForEach(modes, id: \.self) { option in
                let selected = option == mode
                Button { onSelect(option) } label: {
                    LineText(themeModeText(option), style: .smallStrong, color: selected ? colors.onAccent : colors.textSecondary)
                        .frame(maxWidth: .infinity, minHeight: MumcordSize.touchVisual)
                        .background(selected ? colors.accent : .clear, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier(themeModeIdentifier(option))
            }
        }
        .padding(MumcordSpace.s1)
        .background(colors.bgElevated, in: Capsule())
        .overlay { Capsule().strokeBorder(colors.border, lineWidth: 1) }
        .padding(.horizontal, MumcordSpace.s4)
    }
}

/// One theme (`.theme`): a sample of its colours and its name, ringed when it is in use. A
/// button cannot tell a tap from a long press, so the tile listens to the two gestures itself.
private struct ThemeTile: View {
    let theme: Theme
    let selected: Bool
    let variant: ThemeVariant
    let actions: ThemesController
    @Environment(\.mumcordTheme) private var appTheme

    var body: some View {
        let colors = appTheme.colors
        let shape = RoundedRectangle(cornerRadius: tileRadius)
        VStack(alignment: .leading, spacing: 0) {
            ThemeSample(sample: variant == .dark ? theme.dark : theme.light)
                .frame(height: tileSampleHeight)
            HStack(spacing: MumcordSpace.s1) {
                // The name comes from whoever made the theme: plain characters only.
                LineText(theme.name, style: .smallStrong, color: colors.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if selected {
                    MumcordIcon(icon: MumcordIcons.check, label: nil, tint: colors.accentText, size: MumcordSize.iconSm)
                }
            }
            .padding(EdgeInsets(top: MumcordSpace.s2, leading: MumcordSpace.s1, bottom: 2, trailing: MumcordSpace.s1))
        }
        .padding(MumcordSpace.s2)
        .background(selected ? colors.bgSelected : colors.bgElevated, in: shape)
        .overlay { shape.strokeBorder(selected ? colors.accent : colors.border, lineWidth: selected ? 2 : 1) }
        .frame(maxWidth: .infinity)
        .contentShape(shape)
        .onTapGesture { actions.apply(theme.id) }
        .onLongPressGesture { actions.openActions(theme.id) }
        // Gestures are invisible to VoiceOver: it gets one element with the tile's actions.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(selected ? Strings.themesItemSelected(theme.name) : Strings.themesItem(theme.name))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction(.default) { actions.apply(theme.id) }
        .accessibilityAction(named: Text(verbatim: Strings.themesShare)) { actions.share(theme.id) }
        .accessibilityActions {
            if !theme.builtIn {
                Button(Strings.themesDelete) { actions.requestDelete(theme.id) }
            }
        }
        .accessibilityIdentifier(themeTileIdentifier(theme.id))
    }
}

/// A theme's colours in small (`.sw`): its background with dots of its accent, text and talking
/// colours. Also the preview of a pasted theme code.
struct ThemeSample: View {
    let sample: ThemeColors

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            ForEach(Array([sample.accent, sample.text, sample.talking].enumerated()), id: \.offset) { _, dot in
                Circle().fill(sampleColor(dot)).frame(width: dotSize, height: dotSize)
            }
            Spacer(minLength: 0)
        }
        .padding(MumcordSpace.s2)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        .background(sampleColor(sample.background), in: RoundedRectangle(cornerRadius: sampleRadius))
        .accessibilityHidden(true)
    }

    /// A key colour of a theme. The engine checks every theme, imported ones included, and only
    /// keeps `#rrggbb` values; anything else is drawn transparent.
    private func sampleColor(_ value: String) -> Color {
        hexColor(value) ?? .clear
    }
}
