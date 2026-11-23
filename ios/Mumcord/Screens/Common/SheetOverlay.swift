// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// A bottom sheet over the screen: the dimmed backdrop and the sheet frame with `title` and
/// `content`, which scrolls when it is taller than the screen. Tapping the backdrop calls
/// `onDismiss`; a sheet with a decision that must be made on purpose passes nil and cannot be
/// dismissed. A sheet without a visible `title` names itself for VoiceOver with
/// `accessibilityTitle`.
struct SheetOverlay<Content: View>: View {
    private let title: String?
    private let accessibilityTitle: String
    private let onDismiss: (() -> Void)?
    private let content: Content
    @Environment(\.mumcordTheme) private var theme

    init(
        title: String?,
        accessibilityTitle: String? = nil,
        onDismiss: (() -> Void)?,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.accessibilityTitle = accessibilityTitle ?? title ?? ""
        self.onDismiss = onDismiss
        self.content = content()
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            theme.colors.backdrop
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { onDismiss?() }
                .accessibilityHidden(true)
            MumcordSheetFrame(title: title) {
                // As tall as its content, scrolling only when that does not fit. It is the same
                // scroll view in both cases: a sheet that changed its views when the keyboard
                // takes away space would take the focus from the field being typed in.
                FittedHeight {
                    // The focus ring of a text field lies outside the field, where a scroll
                    // view cuts off.
                    ScrollView { column.padding(.horizontal, Self.ringInset) }
                        .scrollBounceBehavior(.basedOnSize)
                        .padding(.horizontal, -Self.ringInset)
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(accessibilityTitle)
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { onDismiss?() }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private static var ringInset: CGFloat { 2 }

    private var column: some View {
        VStack(alignment: .leading, spacing: MumcordSpace.s3) { content }
            .padding(.bottom, MumcordSpace.s2)
    }
}

/// Gives its one view the height that view asks for, or the height there is when that is less.
private struct FittedHeight: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let view = subviews.first else { return .zero }
        let wanted = view.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil))
        return CGSize(width: wanted.width, height: min(wanted.height, proposal.height ?? wanted.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
    }
}

/// Running text that wraps, in the body style. Server-supplied text is drawn as plain characters.
struct Paragraph: View {
    private let text: String
    private let color: Color?
    @Environment(\.mumcordTheme) private var theme

    init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    var body: some View {
        ParagraphText(text, style: .body, color: color ?? theme.colors.textSecondary)
    }
}
