// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// First start: explains both talk modes and lets the user pick one. Nothing is picked for the
/// user, so "Continue" works only after a choice.
struct TalkModeChoiceScreen: View {
    let selected: TalkMode?
    let saving: Bool
    let error: ConnectionError?
    let onSelect: (TalkMode) -> Void
    let onContinue: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordScreen {
            VStack(spacing: MumcordSpace.s4) {
                ScrollView {
                    VStack(alignment: .leading, spacing: MumcordSpace.s4) {
                        intro
                        cards
                        if let error {
                            MumcordBanner(
                                text: error.message,
                                icon: MumcordIcons.alert,
                                tone: .danger,
                                horizontalMargin: 0
                            )
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                VStack(spacing: MumcordSpace.s3) {
                    MumcordButton(
                        text: Strings.talkModeContinue,
                        fullWidth: true,
                        minHeight: 56,
                        action: onContinue
                    )
                    .disabled(selected == nil || saving)
                    .accessibilityIdentifier("talk-mode-continue")
                    ParagraphText(Strings.talkModeChangeLater, style: .small, color: theme.colors.textMuted)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
            }
            .padding(EdgeInsets(top: MumcordSpace.s5, leading: MumcordSpace.s4, bottom: MumcordSpace.s3, trailing: MumcordSpace.s4))
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: MumcordSpace.s2) {
            // The mockup's intro title: the display face of the title style at 30 pt.
            ParagraphText(
                Strings.talkModeTitle,
                style: TextToken.title.with(size: 30, lineHeight: 1.15),
                color: theme.colors.text
            )
            .accessibilityAddTraits(.isHeader)
            Paragraph(Strings.talkModeSubtitle)
        }
        .padding(.top, MumcordSpace.s4)
    }

    private var cards: some View {
        VStack(spacing: MumcordSpace.s3) {
            TalkModeCard(
                icon: MumcordIcons.activity,
                title: Strings.talkModeVoiceActivity,
                text: Strings.talkModeVoiceActivityText,
                perks: [Strings.talkModePerkNoiseSuppression, Strings.talkModePerkHandsFree],
                selected: selected == .voiceActivity,
                action: { onSelect(.voiceActivity) }
            )
            .accessibilityIdentifier("talk-mode-voice-activity")
            TalkModeCard(
                icon: MumcordIcons.mic,
                title: Strings.talkModePushToTalk,
                text: Strings.talkModePushToTalkText,
                perks: [Strings.talkModePerkHold],
                selected: selected == .pushToTalk,
                action: { onSelect(.pushToTalk) }
            )
            .accessibilityIdentifier("talk-mode-push-to-talk")
        }
        .padding(.top, MumcordSpace.s3)
    }
}

private struct TalkModeCard: View {
    let icon: MumcordIconData
    let title: String
    let text: String
    let perks: [String]
    let selected: Bool
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: 20)
        Button(action: action) {
            VStack(alignment: .leading, spacing: MumcordSpace.s3) {
                HStack(spacing: MumcordSpace.s3) {
                    MumcordIcon(
                        icon: icon,
                        label: nil,
                        tint: selected ? colors.onAccent : colors.text,
                        size: MumcordSize.iconLg
                    )
                    .frame(width: 52, height: 52)
                    .background(selected ? colors.accent : colors.bgHover, in: RoundedRectangle(cornerRadius: 16))
                    ParagraphText(title, style: .talkButton, color: colors.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    RadioMark(selected: selected)
                }
                Paragraph(text)
                FlowLayout(spacing: MumcordSpace.s2) {
                    ForEach(perks, id: \.self) { perk in
                        Perk(text: perk)
                    }
                }
            }
            // The 2 pt border takes its own room, as the CSS border of the mockup's card.
            .padding(MumcordSpace.s4 + 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(selected ? colors.bgSelected : colors.bgElevated, in: shape)
            .overlay { shape.strokeBorder(selected ? colors.accent : colors.border, lineWidth: 2) }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct RadioMark: View {
    let selected: Bool
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        ZStack {
            Circle().fill(selected ? colors.accent : Color.clear)
            Circle().strokeBorder(selected ? colors.accent : colors.borderStrong, lineWidth: 2)
            if selected {
                MumcordIcon(icon: MumcordIcons.check, label: nil, tint: colors.onAccent, size: MumcordSize.iconSm)
            }
        }
        .frame(width: 26, height: 26)
        .accessibilityHidden(true)
    }
}

private struct Perk: View {
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: 6) {
            MumcordIcon(icon: MumcordIcons.check, label: nil, tint: colors.textSecondary, size: MumcordSize.iconSm)
            ParagraphText(text, style: .smallStrong, color: colors.textSecondary)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 32)
        .background(colors.bgHover, in: Capsule())
    }
}

/// Lays its children out in a row and starts a new row when the next one does not fit, like a
/// CSS flex container with wrapping.
private struct FlowLayout: Layout {
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(width: proposal.width ?? .infinity, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let origins = arrange(width: bounds.width, subviews: subviews).origins
        for (index, origin) in origins.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y),
                anchor: .topLeading,
                proposal: ProposedViewSize(width: bounds.width, height: nil)
            )
        }
    }

    /// The top leading corner of every child and the size all of them take together.
    private func arrange(width: CGFloat, subviews: Subviews) -> (origins: [CGPoint], size: CGSize) {
        var origins: [CGPoint] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for subview in subviews {
            // A child wider than the row wraps its own text instead of running out of it.
            let size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil))
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            origins.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return (origins, CGSize(width: widest, height: y + rowHeight))
    }
}
