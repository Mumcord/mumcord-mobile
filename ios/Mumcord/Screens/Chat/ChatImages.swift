// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

private let inlineMaxWidth: CGFloat = 200
private let inlineMaxHeight: CGFloat = 240
/// A tile is cropped to a square, so it is decoded with room for the side that is cut off.
private let tileDecodeSize: CGFloat = 360

private enum ImageLoad {
    case loading
    case unavailable
    case ready(LoadedImage)

    var image: LoadedImage? {
        if case .ready(let image) = self { return image }
        return nil
    }
}

/// Loads a picture for a view and keeps it while the view lives.
private struct ImageLoading<Content: View>: View {
    let source: ChatImageSource
    let loader: ChatImageLoader
    /// The longer side of the box the picture is shown in, in points.
    let maxSize: CGFloat
    @ViewBuilder let content: (ImageLoad) -> Content
    @State private var load = ImageLoad.loading
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        content(load)
            .task(id: source) {
                load = .loading
                let image = await loader.load(source, maxPixelSize: Int((maxSize * displayScale).rounded(.up)))
                if Task.isCancelled { return }
                load = image.map(ImageLoad.ready) ?? .unavailable
            }
    }
}

/// A picture inside a message (`.msg__image`): rounded, at most 200 pt wide, in the picture's
/// own proportions. While it loads the frame shows the image icon; a picture from the web that
/// cannot or may not be loaded takes no room (its link is still in the text). Tapping opens it
/// large; `onLongPress` is set for a GIF that can be kept as a favourite.
struct ChatInlineImage: View {
    let source: ChatImageSource
    let loader: ChatImageLoader
    let onOpen: () -> Void
    var onLongPress: (() -> Void)?
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        ImageLoading(source: source, loader: loader, maxSize: max(inlineMaxWidth, inlineMaxHeight)) { load in
            if case .unavailable = load, case .remote = source {
                EmptyView()
            } else {
                picture(load)
            }
        }
    }

    @ViewBuilder
    private func picture(_ load: ImageLoad) -> some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        let fitted = fittedSize(load)
        let framed = ZStack {
            switch load {
            case .ready(let image):
                LoadedImageView(image: image, fill: false)
            case .loading:
                MumcordIcon(icon: MumcordIcons.image, label: nil, tint: colors.textMuted, size: MumcordSize.iconXl)
            case .unavailable:
                MumcordIcon(icon: MumcordIcons.alert, label: nil, tint: colors.textMuted, size: MumcordSize.iconLg)
            }
        }
        .frame(width: fitted.width, height: fitted.height)
        .background(colors.bgElevated)
        .clipShape(shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .contentShape(shape)
        if load.image == nil {
            framed
        } else if let onLongPress {
            // The tap is declared first, so holding the picture never stops the list from scrolling.
            framed
                .onTapGesture(perform: onOpen)
                .onLongPressGesture(perform: onLongPress)
        } else {
            framed.onTapGesture(perform: onOpen)
        }
    }

    private func fittedSize(_ load: ImageLoad) -> CGSize {
        if let image = load.image {
            return Mumcord.fittedSize(width: image.width, height: image.height, maxWidth: inlineMaxWidth, maxHeight: inlineMaxHeight)
        }
        if case .embedded(_, _, _, let width, let height) = source {
            return Mumcord.fittedSize(width: width, height: height, maxWidth: inlineMaxWidth, maxHeight: inlineMaxHeight)
        }
        return Mumcord.fittedSize(width: 0, height: 0, maxWidth: inlineMaxWidth, maxHeight: inlineMaxHeight)
    }
}

/// A picture that fills its frame, cropped at the centre: a tile of the GIF favourites.
struct ChatTileImage: View {
    let source: ChatImageSource
    let loader: ChatImageLoader
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        ImageLoading(source: source, loader: loader, maxSize: tileDecodeSize) { load in
            ZStack {
                if let image = load.image {
                    LoadedImageView(image: image, fill: true)
                } else {
                    MumcordIcon(icon: MumcordIcons.gif, label: nil, tint: theme.colors.textMuted, size: MumcordSize.iconLg)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// A picture as large as `maxSize` allows, for the viewer; the image icon while it is not there.
struct ChatFullImage: View {
    let source: ChatImageSource
    let loader: ChatImageLoader
    let maxSize: CGSize
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        ImageLoading(source: source, loader: loader, maxSize: max(maxSize.width, maxSize.height)) { load in
            if let image = load.image {
                let size = fittedSize(width: image.width, height: image.height, maxWidth: maxSize.width, maxHeight: maxSize.height)
                LoadedImageView(image: image, fill: false)
                    .frame(width: size.width, height: size.height)
            } else {
                MumcordIcon(icon: MumcordIcons.image, label: nil, tint: theme.colors.mediaText, size: MumcordSize.iconXl)
            }
        }
    }
}

/// Draws a decoded picture in the frame it is given. An animated one runs only while this is
/// on screen and the app is in front: leaving the list (scrolled away) or the screen stops it.
private struct LoadedImageView: View {
    let image: LoadedImage
    let fill: Bool
    @State private var onScreen = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        if image.animated {
            AnimatedImage(image: image, fill: fill, playing: onScreen && scenePhase == .active)
                .onAppear { onScreen = true }
                .onDisappear { onScreen = false }
        } else {
            Color.clear
                .overlay {
                    Image(uiImage: image.image)
                        .resizable()
                        .aspectRatio(contentMode: fill ? .fill : .fit)
                }
                .clipped()
        }
    }
}

/// The frames of an animated picture in a `UIImageView`: SwiftUI has no view that plays them.
private struct AnimatedImage: UIViewRepresentable {
    let image: LoadedImage
    let fill: Bool
    let playing: Bool

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.clipsToBounds = true
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        view.contentMode = fill ? .scaleAspectFill : .scaleAspectFit
        if view.animationImages?.first !== image.frames.first {
            view.stopAnimating()
            view.image = image.image
            view.animationImages = image.frames
            view.animationDuration = image.duration
        }
        if playing, !view.isAnimating {
            view.startAnimating()
        } else if !playing, view.isAnimating {
            view.stopAnimating()
        }
    }

    /// The frame is decided by SwiftUI, never by the size of the picture.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UIImageView, context: Context) -> CGSize? {
        proposal.replacingUnspecifiedDimensions()
    }

    static func dismantleUIView(_ view: UIImageView, coordinator: ()) {
        view.stopAnimating()
        view.animationImages = nil
    }
}
