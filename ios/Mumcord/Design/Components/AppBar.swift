// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Top app bar (`.appbar`). `large` is the big-title variant of top-level screens; pass
/// `onBack` for the variant with a back arrow. `leading` holds a tile or avatar before the
/// title, `subtitle` a line of small muted content under it, `actions` the icon buttons at the
/// end. The bar sits below the status bar, inside the safe area.
struct MumcordAppBar<Leading: View, Subtitle: View, Actions: View>: View {
    private let title: String
    private let large: Bool
    private let border: Bool
    private let onBack: (() -> Void)?
    private let backLabel: String
    private let leading: Leading
    private let subtitle: Subtitle
    private let actions: Actions
    @Environment(\.mumcordTheme) private var theme

    init(
        title: String,
        large: Bool = false,
        border: Bool = false,
        onBack: (() -> Void)? = nil,
        backLabel: String = "",
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder subtitle: () -> Subtitle,
        @ViewBuilder actions: () -> Actions
    ) {
        self.title = title
        self.large = large
        self.border = border
        self.onBack = onBack
        self.backLabel = backLabel
        self.leading = leading()
        self.subtitle = subtitle()
        self.actions = actions()
    }

    var body: some View {
        let colors = theme.colors
        HStack(spacing: MumcordSpace.s2) {
            if let onBack {
                MumcordIconButton(icon: MumcordIcons.chevronLeft, label: backLabel, action: onBack)
            }
            leading
            VStack(alignment: .leading, spacing: 0) {
                LineText(title, style: large ? .displayLarge : .title, color: colors.text)
                    .accessibilityAddTraits(.isHeader)
                HStack(spacing: 6) { subtitle }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            actions
        }
        // The back button sits 10 pt further out so that its arrow lines up with the content.
        .padding(EdgeInsets(top: large ? 10 : 6, leading: onBack != nil ? 6 : 16, bottom: 6, trailing: 8))
        .frame(minHeight: large ? 64 : 56)
        .overlay(alignment: .bottom) {
            if border {
                Rectangle().fill(colors.border).frame(height: 1)
            }
        }
    }
}

extension MumcordAppBar where Leading == EmptyView, Subtitle == EmptyView {
    init(
        title: String,
        large: Bool = false,
        border: Bool = false,
        onBack: (() -> Void)? = nil,
        backLabel: String = "",
        @ViewBuilder actions: () -> Actions
    ) {
        self.init(
            title: title,
            large: large,
            border: border,
            onBack: onBack,
            backLabel: backLabel,
            leading: { EmptyView() },
            subtitle: { EmptyView() },
            actions: actions
        )
    }
}

extension MumcordAppBar where Leading == EmptyView, Subtitle == EmptyView, Actions == EmptyView {
    init(
        title: String,
        large: Bool = false,
        border: Bool = false,
        onBack: (() -> Void)? = nil,
        backLabel: String = ""
    ) {
        self.init(title: title, large: large, border: border, onBack: onBack, backLabel: backLabel, actions: { EmptyView() })
    }
}

/// Text of an app bar subtitle in the right style.
struct MumcordAppBarSubtitle: View {
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        LineText(text, style: .small, color: theme.colors.textMuted)
    }
}
