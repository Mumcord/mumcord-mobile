// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import PhotosUI
import SwiftUI
import UIKit

private let maxZoom: CGFloat = 5

/// The sheet over the chat and the full-screen picture, whichever is open, and the system's
/// photo library and camera when the user adds a picture.
struct ChatOverlays: View {
    let state: ChatUiState
    let actions: ChatController
    let loader: ChatImageLoader
    @State private var pickingPhoto = false
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var takingPhoto = false

    var body: some View {
        ZStack {
            // Keeps this layer as large as the screen without taking any touch.
            Color.clear.allowsHitTesting(false)
            switch state.sheet {
            case .gifs(let favourites):
                GifFavouritesSheet(favourites: favourites, actions: actions, loader: loader)
            case .imageSource:
                ImageSourceSheet(onLibrary: pickPhoto, onCamera: takePhoto, onDismiss: actions.dismissSheet)
            case .linkWarning(let link):
                LinkWarningSheet(link: link, actions: actions)
            case nil:
                EmptyView()
            }
            if let viewer = state.viewer {
                ImageViewer(source: viewer, loader: loader, onClose: actions.closeViewer)
            }
        }
        .onChange(of: state.urlToOpen, initial: true) { _, address in
            guard let address else { return }
            // The address was checked by the engine (http or https with a host); it goes to
            // the system, which opens the user's browser. Nothing is loaded inside the app.
            if let url = browserURL(address) { UIApplication.shared.open(url) }
            actions.urlOpened()
        }
        // The system's photo picker runs outside the app and hands over only the picture the
        // user chose, so it needs no permission.
        .photosPicker(isPresented: $pickingPhoto, selection: $pickedPhoto, matching: .images)
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            pickedPhoto = nil
            Task {
                let bytes = try? await item.loadTransferable(type: Data.self)
                let image = await Task.detached { bytes.flatMap(readPickedImage) }.value
                deliver(image)
            }
        }
        .fullScreenCover(isPresented: $takingPhoto) {
            CameraPicker { photo in
                takingPhoto = false
                guard let photo else { return }
                Task {
                    let image = await Task.detached { readCameraPhoto(photo) }.value
                    deliver(image)
                }
            }
            .ignoresSafeArea()
        }
    }

    private func pickPhoto() {
        actions.dismissSheet()
        pickingPhoto = true
    }

    private func takePhoto() {
        actions.dismissSheet()
        if CameraPicker.available {
            takingPhoto = true
        } else {
            // A device without a camera.
            actions.imageUnreadable()
        }
    }

    private func deliver(_ image: PickedImage?) {
        if let image {
            actions.sendImage(bytes: image.bytes, mime: image.mime)
        } else {
            actions.imageUnreadable()
        }
    }
}

/// GIF favourites (mockup `08-gif-favourites`): a grid of the saved GIFs. Tapping one sends it,
/// the star takes it out of the favourites. There is no search.
private struct GifFavouritesSheet: View {
    let favourites: [GifFavourite]
    let actions: ChatController
    let loader: ChatImageLoader
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: Strings.gifTitle, onDismiss: actions.dismissSheet) {
            ForEach(Array(stride(from: 0, to: favourites.count, by: 2)), id: \.self) { start in
                HStack(spacing: MumcordSpace.s2) {
                    tile(start)
                    if start + 1 < favourites.count {
                        tile(start + 1)
                    } else {
                        Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                    }
                }
            }
            ParagraphText(Strings.gifHint, style: .caption, color: theme.colors.textMuted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, MumcordSpace.s1)
        }
        .accessibilityIdentifier("gif-sheet")
    }

    private func tile(_ index: Int) -> some View {
        let url = favourites[index].url
        return MumcordGifTile(
            label: Strings.gifItem(index + 1),
            tag: Strings.chatGifWord,
            removeLabel: Strings.gifRemove,
            action: { actions.sendGif(url) },
            onRemove: { actions.removeGif(url) }
        ) {
            ChatTileImage(source: .remote(url: url, gif: true), loader: loader)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("gif-tile")
    }
}

/// Where a picture to send comes from: the photo library or the camera.
private struct ImageSourceSheet: View {
    let onLibrary: () -> Void
    let onCamera: () -> Void
    let onDismiss: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: Strings.imageSourceTitle, onDismiss: onDismiss) {
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                MumcordListRow(title: Strings.imageSourceLibrary, icon: MumcordIcons.image, action: onLibrary)
                    .accessibilityIdentifier("image-source-library")
                MumcordListRow(title: Strings.imageSourceCamera, icon: MumcordIcons.camera, divider: true, action: onCamera)
                    .accessibilityIdentifier("image-source-camera")
            }
        }
    }
}

/// Before a link is opened whose text names another address than the one it goes to: the real
/// address, in full, and the choice to open it or not.
private struct LinkWarningSheet: View {
    let link: SafeLink
    let actions: ChatController

    var body: some View {
        SheetOverlay(title: Strings.linkWarningTitle, onDismiss: actions.dismissSheet) {
            Paragraph(Strings.linkWarningText)
            MumcordBanner(
                // The address as the engine checked it, shown as plain characters.
                text: link.url,
                title: link.host,
                icon: MumcordIcons.alert,
                tone: .warning,
                horizontalMargin: 0
            )
            .accessibilityIdentifier("link-warning")
            MumcordButton(text: Strings.actionCancel, fullWidth: true, action: actions.dismissSheet)
            MumcordButton(text: Strings.linkWarningOpen, style: .secondary, fullWidth: true, action: actions.confirmLink)
                .accessibilityIdentifier("link-warning-open")
        }
    }
}

/// A picture of the chat on the whole screen. Pinch to zoom, double tap to reset.
private struct ImageViewer: View {
    let source: ChatImageSource
    let loader: ChatImageLoader
    let onClose: () -> Void
    @Environment(\.mumcordTheme) private var theme
    @State private var zoom: CGFloat = 1
    @State private var pan = CGSize.zero
    @GestureState private var pinch: CGFloat = 1
    @GestureState private var drag = CGSize.zero

    var body: some View {
        let scale = min(max(zoom * pinch, 1), maxZoom)
        GeometryReader { proxy in
            ChatFullImage(source: source, loader: loader, maxSize: proxy.size)
                .scaleEffect(scale)
                .offset(x: pan.width + drag.width, y: pan.height + drag.height)
                .frame(width: proxy.size.width, height: proxy.size.height)
                .accessibilityElement()
                .accessibilityLabel(Strings.viewerTitle)
                .accessibilityAddTraits(.isImage)
        }
        .background(theme.colors.mediaBg.ignoresSafeArea())
        .contentShape(Rectangle())
        .onTapGesture(count: 2) {
            zoom = 1
            pan = .zero
        }
        .gesture(
            MagnifyGesture()
                .updating($pinch) { value, state, _ in state = value.magnification }
                .onEnded { value in
                    zoom = min(max(zoom * value.magnification, 1), maxZoom)
                    if zoom == 1 { pan = .zero }
                }
        )
        .simultaneousGesture(
            DragGesture()
                .updating($drag) { value, state, _ in
                    if zoom > 1 { state = value.translation }
                }
                .onEnded { value in
                    guard zoom > 1 else { return }
                    pan = CGSize(width: pan.width + value.translation.width, height: pan.height + value.translation.height)
                }
        )
        .overlay(alignment: .topTrailing) {
            MumcordIconButton(icon: MumcordIcons.x, label: Strings.viewerClose, style: .tonal, action: onClose)
                .padding(MumcordSpace.s2)
                .accessibilityIdentifier("image-viewer-close")
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Strings.viewerTitle)
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape, onClose)
        .accessibilityIdentifier("image-viewer")
    }
}
