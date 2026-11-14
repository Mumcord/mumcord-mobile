// SPDX-License-Identifier: AGPL-3.0-only

import ImageIO
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A picture read from the phone, in an encoding the chat can send.
struct PickedImage: Equatable {
    let bytes: Data
    let mime: String
}

/// The largest picture the engine takes; it scales a large one down to the server's limit itself.
let maxImageBytes = 25 * 1024 * 1024

private let jpegQuality = 0.9
private let transcodeMaxEdge = 4096
/// The encodings the chat sends as they are, by the name the system gives them.
private let sendable: [String: String] = [
    UTType.png.identifier: "image/png",
    UTType.jpeg.identifier: "image/jpeg",
    UTType.gif.identifier: "image/gif",
    UTType.webP.identifier: "image/webp",
]

/// Reads a chosen picture. Encodings the chat cannot send (such as HEIC from the photo library)
/// are turned into JPEG. Nil when it cannot be read or is larger than the engine takes.
func readPickedImage(_ bytes: Data) -> PickedImage? {
    guard let source = CGImageSourceCreateWithData(bytes as CFData, nil),
          CGImageSourceGetCount(source) > 0,
          let type = CGImageSourceGetType(source) as String?
    else { return nil }
    if let mime = sendable[type] {
        return bytes.count <= maxImageBytes ? PickedImage(bytes: bytes, mime: mime) : nil
    }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        // Turns the photo the way its camera held it; JPEG bytes carry no such note here.
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: transcodeMaxEdge,
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
    return jpeg(UIImage(cgImage: image))
}

/// A photo the camera just took, as JPEG no larger than the chat sends.
func readCameraPhoto(_ photo: UIImage) -> PickedImage? {
    let longest = max(photo.size.width, photo.size.height) * photo.scale
    guard longest > 0 else { return nil }
    let scale = min(1, CGFloat(transcodeMaxEdge) / longest)
    let size = CGSize(width: photo.size.width * photo.scale * scale, height: photo.size.height * photo.scale * scale)
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    // Drawing also turns the photo upright, whichever way the phone was held.
    let upright = UIGraphicsImageRenderer(size: size, format: format).image { _ in
        photo.draw(in: CGRect(origin: .zero, size: size))
    }
    return jpeg(upright)
}

private func jpeg(_ image: UIImage) -> PickedImage? {
    guard let data = image.jpegData(compressionQuality: jpegQuality), data.count <= maxImageBytes else { return nil }
    return PickedImage(bytes: data, mime: "image/jpeg")
}

/// The system's camera, which hands back one photo. iOS asks for the camera itself the first
/// time it opens, with the sentence from the app's Info.plist.
struct CameraPicker: UIViewControllerRepresentable {
    /// The photo, or nil when the user gave up.
    let onDone: (UIImage?) -> Void

    static var available: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onDone: onDone)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let onDone: (UIImage?) -> Void

        init(onDone: @escaping (UIImage?) -> Void) {
            self.onDone = onDone
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            onDone(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onDone(nil)
        }
    }
}
