// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The opened stream full screen (FR-5.71), dark in both themes like the mockup: title, LIVE and
/// viewers at the top, the picture in the middle, the sound volume and the buttons at the
/// bottom. The collapse buttons, and the escape gesture of VoiceOver, shrink it to the mini
/// window.
///
/// `mute` is the user's own microphone for its button; nil hides the button. `onLeave` is set
/// where the whole screen was opened from the right column of two columns: the collapse arrow
/// and the escape gesture return there, and the rotate button becomes "Leave full screen",
/// because an iPad turns by itself.
struct StreamFullScreen: View {
    let stream: StreamUi
    let landscape: Bool
    let mute: MuteControl?
    let onToggleMute: () -> Void
    let onMicrophone: () -> Void
    let actions: StreamActions
    var onLeave: (() -> Void)?
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let back = onLeave ?? actions.collapse
        GeometryReader { area in
            let portrait = onLeave == nil && area.size.height > area.size.width
            ZStack {
                colors.stageBg.ignoresSafeArea()
                if stream.playing {
                    StreamPicture(videoId: stream.videoId).ignoresSafeArea()
                }
                if let status = stream.openStatus {
                    StageStatus(text: status, retry: retry)
                }
                if portrait {
                    MumcordStageHint(icon: MumcordIcons.maximize, text: Strings.streamRotateHint)
                        // Below a 16:9 picture across the full width.
                        .offset(y: area.size.width * 9 / 32 + 48)
                        .accessibilityHidden(true)
                }
                VStack(spacing: 0) {
                    StageTop(stream: stream, onCollapse: back)
                    Spacer(minLength: 0)
                    StageBottom(
                        stream: stream,
                        landscape: landscape,
                        mute: mute,
                        onToggleMute: onToggleMute,
                        onMicrophone: onMicrophone,
                        actions: actions,
                        onLeave: onLeave
                    )
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("stream-full-screen")
        .accessibilityAction(.escape, back)
        .streamOrientation(landscape: landscape)
    }

    private var retry: (() -> Void)? {
        guard case .unreachable = stream.tile else { return nil }
        let key = stream.key
        return { actions.retry(key) }
    }
}

private struct StageTop: View {
    let stream: StreamUi
    let onCollapse: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: MumcordSpace.s3) {
            MumcordStageButton(icon: MumcordIcons.chevronDown, label: Strings.streamCollapse, action: onCollapse)
                .accessibilityIdentifier("stream-collapse")
            VStack(alignment: .leading, spacing: 3) {
                LineText(stream.title, style: .title, color: colors.mediaText)
                    .accessibilityAddTraits(.isHeader)
                HStack(spacing: MumcordSpace.s2) {
                    MumcordLiveBadge(liveLabel: Strings.streamLive)
                    if let viewers = stream.viewers {
                        MumcordIcon(icon: MumcordIcons.user, label: nil, tint: colors.stageTextSecondary, size: MumcordSize.iconSm)
                        LineText(Strings.streamWatching(viewers), style: .caption, color: colors.stageTextSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(EdgeInsets(top: MumcordSpace.s2, leading: MumcordSpace.s3, bottom: 28, trailing: MumcordSpace.s3))
        .background(
            LinearGradient(colors: [colors.stageBg, .clear], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top)
        )
    }
}

private struct StageBottom: View {
    let stream: StreamUi
    let landscape: Bool
    let mute: MuteControl?
    let onToggleMute: () -> Void
    let onMicrophone: () -> Void
    let actions: StreamActions
    let onLeave: (() -> Void)?
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        VStack(spacing: MumcordSpace.s3) {
            // Only a share that sends sound has a volume (FR-5.72).
            if stream.hasSound {
                HStack(spacing: MumcordSpace.s2) {
                    MumcordIcon(icon: MumcordIcons.volume, label: nil, tint: colors.mediaText)
                    MumcordSlider(
                        value: Double(stream.volume) / Double(maxStreamVolume),
                        label: Strings.streamVolume,
                        valueText: Strings.personVolumeValue(stream.volume),
                        onMedia: true,
                        onValueChange: { fraction in
                            actions.changeVolume(stream.key, Int((fraction * Double(maxStreamVolume)).rounded()))
                        }
                    )
                    .accessibilityIdentifier("stream-volume")
                }
            }
            HStack {
                muteButton
                if let onLeave {
                    MumcordStageButton(icon: MumcordIcons.maximize, label: Strings.streamLeaveFullScreen, action: onLeave)
                        .accessibilityIdentifier("stream-leave-full-screen")
                } else if OrientationLock.canTurn {
                    // An iPad ignores the app's wish to turn, so the button would do nothing there.
                    MumcordStageButton(
                        icon: MumcordIcons.maximize,
                        label: landscape ? Strings.streamRotatePortrait : Strings.streamRotateLandscape,
                        action: actions.toggleLandscape
                    )
                    .accessibilityIdentifier("stream-rotate")
                }
                Spacer(minLength: 0)
                MumcordStageButton(icon: MumcordIcons.monitor, label: Strings.streamCollapse, action: actions.collapse)
                Spacer(minLength: 0)
                MumcordStageButton(icon: MumcordIcons.x, label: Strings.streamStop, danger: true, action: actions.close)
                    .accessibilityIdentifier("stream-stop")
            }
        }
        .padding(EdgeInsets(top: 28, leading: MumcordSpace.s3, bottom: MumcordSpace.s3, trailing: MumcordSpace.s3))
        .background(
            LinearGradient(colors: [.clear, colors.stageBg], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    @ViewBuilder private var muteButton: some View {
        switch mute {
        case .unmuted, .muted:
            MumcordStageButton(
                icon: mute == .muted ? MumcordIcons.micOff : MumcordIcons.mic,
                label: mute == .muted ? Strings.voiceUnmuteMicrophone : Strings.voiceMuteMicrophone,
                action: onToggleMute
            )
            Spacer(minLength: 0)
        case .noMicrophone:
            MumcordStageButton(icon: MumcordIcons.micOff, label: Strings.voiceUnmuteMicrophone, action: onMicrophone)
            Spacer(minLength: 0)
        // The user cannot change the server's mute, so there is no button for it here.
        case .serverMuted, nil:
            EmptyView()
        }
    }
}

/// What an opened stream shows where it has no picture, with "Try again" where it could not be
/// reached. `small` is the mini window's: two lines at most and no button.
struct StageStatus: View {
    let text: String
    let retry: (() -> Void)?
    var small = false
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        VStack(spacing: MumcordSpace.s3) {
            Group {
                if small {
                    Text(text)
                        .font(Font(MumcordFonts.font(.captionStrong, scale: scale)))
                        .lineLimit(2)
                } else {
                    ParagraphText(text, style: .body, color: colors.mediaText)
                }
            }
            .foregroundStyle(colors.mediaText)
            .multilineTextAlignment(.center)
            // Says what happens without the user having to look for it, as a live region does.
            .onChange(of: text) { _, now in
                UIAccessibility.post(notification: .announcement, argument: now)
            }
            if let retry, !small {
                // The stage is dark in both themes, so the button takes the dark look too.
                MumcordButton(text: Strings.streamTryAgain, style: .secondary, small: true, action: retry)
                    .environment(\.mumcordTheme, .dark)
                    .accessibilityIdentifier("stream-retry")
            }
        }
        .padding(MumcordSpace.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
