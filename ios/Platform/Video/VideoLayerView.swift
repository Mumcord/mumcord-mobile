// SPDX-License-Identifier: AGPL-3.0-only

import Metal
import QuartzCore
import UIKit

/// The shader that draws one I420 picture from three single-channel textures (Y, U, V) as RGB.
/// Compiled when the first picture is drawn instead of with the app: building `.metal` files
/// needs Xcode's separate Metal toolchain, which neither CI nor a fresh Mac has.
private let i420Shader = """
#include <metal_stdlib>
using namespace metal;

struct Corner {
    float4 position [[position]];
    float2 texture;
};

// The whole viewport as a strip of two triangles; no vertex buffer is needed.
vertex Corner i420_vertex(uint id [[vertex_id]]) {
    float2 point = float2(id & 1, id >> 1);
    Corner corner;
    corner.position = float4(point.x * 2.0 - 1.0, 1.0 - point.y * 2.0, 0.0, 1.0);
    corner.texture = point;
    return corner;
}

// BT.601 limited range, as the engine's decoders deliver it.
fragment half4 i420_fragment(
    Corner in [[stage_in]],
    texture2d<half> luma [[texture(0)]],
    texture2d<half> blue [[texture(1)]],
    texture2d<half> red [[texture(2)]],
    sampler sample [[sampler(0)]]
) {
    half y = (luma.sample(sample, in.texture).r - 16.0h / 255.0h) * 1.164h;
    half u = blue.sample(sample, in.texture).r - 0.5h;
    half v = red.sample(sample, in.texture).r - 0.5h;
    half3 rgb = half3(y + 1.596h * v, y - 0.392h * u - 0.813h * v, y + 2.017h * u);
    return half4(clamp(rgb, 0.0h, 1.0h), 1.0h);
}
"""

/// What every stream picture is drawn with: one Metal device, the I420 shader and one serial
/// render queue. Nil where the device has no Metal (no supported iPhone lacks it) or the shader
/// cannot be compiled; the failure is logged and streams then show no picture.
final class VideoRenderer: @unchecked Sendable {
    static let shared: VideoRenderer? = VideoRenderer()

    let device: MTLDevice
    let commands: MTLCommandQueue
    let pipeline: MTLRenderPipelineState
    let sampler: MTLSamplerState
    /// Uploading and drawing happen here, never on the main thread.
    let queue = DispatchQueue(label: "app.mumcord.mobile.video", qos: .userInteractive)
    static let pixelFormat = MTLPixelFormat.bgra8Unorm

    private init?() {
        guard let device = MTLCreateSystemDefaultDevice(), let commands = device.makeCommandQueue() else {
            AppLog.write("stream pictures: no Metal device")
            return nil
        }
        let library: MTLLibrary
        do {
            library = try device.makeLibrary(source: i420Shader, options: nil)
        } catch {
            AppLog.write("stream pictures: the shader did not compile: \(error)")
            return nil
        }
        let description = MTLRenderPipelineDescriptor()
        description.vertexFunction = library.makeFunction(name: "i420_vertex")
        description.fragmentFunction = library.makeFunction(name: "i420_fragment")
        description.colorAttachments[0].pixelFormat = Self.pixelFormat
        let samplerDescription = MTLSamplerDescriptor()
        samplerDescription.minFilter = .linear
        samplerDescription.magFilter = .linear
        samplerDescription.sAddressMode = .clampToEdge
        samplerDescription.tAddressMode = .clampToEdge
        guard let pipeline = try? device.makeRenderPipelineState(descriptor: description),
              let sampler = device.makeSamplerState(descriptor: samplerDescription)
        else {
            AppLog.write("stream pictures: no render pipeline")
            return nil
        }
        self.device = device
        self.commands = commands
        self.pipeline = pipeline
        self.sampler = sampler
    }
}

/// Draws the live picture of one stream into a `CAMetalLayer`.
///
/// The engine's news of a picture (`VideoRouter`, on the bridge's video thread) only schedules a
/// redraw on the render queue; a redraw that is pending takes the newest picture, so nothing is
/// queued. The render queue takes the picture, copies its three planes straight from the
/// engine's memory into three single-channel textures, gives the picture back at once and lets
/// the BT.601 shader turn it into RGB, letterboxed on black. The textures are made again only
/// when the picture's size changes; nothing else is allocated per picture. They belong to the
/// stream, not the view (`VideoPictures`), so the next view of a watched stream starts with its
/// last picture, and a view switched to another stream never shows the one before.
///
/// Nothing is drawn while the view is not in a window or the app is in the background: the view
/// stops listening then, and takes the waiting picture itself when it is shown again.
final class VideoLayerView: UIView {
    override class var layerClass: AnyClass { CAMetalLayer.self }

    private let painter: VideoPainter?
    private var shown: (videoId: UInt64, frames: VideoFrames, router: VideoRouter)?
    private var listening: (videoId: UInt64, router: VideoRouter)?
    private var inBackground = UIApplication.shared.applicationState == .background
    private var observers: [NSObjectProtocol] = []

    override init(frame: CGRect) {
        painter = VideoRenderer.shared.map { VideoPainter(renderer: $0) }
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        if let layer = layer as? CAMetalLayer, let renderer = VideoRenderer.shared {
            layer.device = renderer.device
            layer.pixelFormat = VideoRenderer.pixelFormat
            layer.framebufferOnly = true
            layer.isOpaque = false
            painter?.layer = layer
        }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.setBackground(true) }
            },
            center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.setBackground(false) }
            },
        ]
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("not used from a storyboard")
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        if let listening { listening.router.stop(videoId: listening.videoId, owner: self) }
    }

    /// Shows the pictures of `videoId` from now on.
    func show(videoId: UInt64, frames: VideoFrames, router: VideoRouter) {
        if let shown, shown.videoId == videoId, shown.frames === frames, shown.router === router { return }
        shown = (videoId, frames, router)
        updateListening()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateListening()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = window?.screen.scale ?? traitCollection.displayScale
        let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        guard let painter, size.width >= 1, size.height >= 1 else { return }
        (layer as? CAMetalLayer)?.drawableSize = size
        // The last picture again in the new size, without waiting for the next one.
        painter.schedule(take: false)
    }

    private func setBackground(_ background: Bool) {
        inBackground = background
        updateListening()
    }

    private func updateListening() {
        guard let painter else { return }
        let wanted = shown.flatMap { shown in window != nil && !inBackground ? shown : nil }
        if let listening, listening.videoId != wanted?.videoId || listening.router !== wanted?.router {
            listening.router.stop(videoId: listening.videoId, owner: self)
            self.listening = nil
        }
        painter.setSource(wanted.map { VideoSource(videoId: $0.videoId, frames: $0.frames, pictures: $0.router.pictures) })
        guard let wanted, listening == nil else { return }
        listening = (wanted.videoId, wanted.router)
        wanted.router.listen(videoId: wanted.videoId, owner: self) { [weak painter] in
            painter?.schedule(take: true)
        }
        // The engine tells of a picture only once it was taken; one may be waiting already.
        painter.schedule(take: true)
    }
}

/// What a view draws: the pictures of `videoId` from `frames`, kept in `pictures`.
private struct VideoSource {
    let videoId: UInt64
    let frames: VideoFrames
    let pictures: VideoPictures

    func isSame(as other: VideoSource?) -> Bool {
        other?.videoId == videoId && other?.frames === frames && other?.pictures === pictures
    }
}

/// The part of `VideoLayerView` that lives on the render queue. Its fields are read and written
/// there only, except the few the lock guards.
private final class VideoPainter: @unchecked Sendable {
    private let renderer: VideoRenderer
    weak var layer: CAMetalLayer?

    private let lock = NSLock()
    /// The picture source; nil while nothing may be drawn.
    private var source: VideoSource?
    /// A redraw is on its way to the render queue.
    private var pending = false
    /// The pending redraw takes a new picture first.
    private var take = false

    // Render queue only.
    /// The picture drawn last and the source it belongs to.
    private var held: (source: VideoSource, picture: VideoPicture)?
    /// The layer shows a picture, which an empty one must clear.
    private var showsPicture = false

    init(renderer: VideoRenderer) {
        self.renderer = renderer
    }

    func setSource(_ source: VideoSource?) {
        lock.withLock { self.source = source }
    }

    /// Called on any thread; returns at once.
    func schedule(take: Bool) {
        let start: Bool = lock.withLock {
            self.take = self.take || take
            if pending { return false }
            pending = true
            return true
        }
        if start { renderer.queue.async { self.draw() } }
    }

    private func draw() {
        let (source, take): (VideoSource?, Bool) = lock.withLock {
            pending = false
            defer { self.take = false }
            return (self.source, self.take)
        }
        guard let source, let layer else { return }
        let own = held.flatMap { source.isSame(as: $0.source) ? $0.picture : nil }
        let picture = source.pictures.picture(for: source.videoId, held: own)
        held = (source, picture)
        if take, let frame = source.frames.acquire(videoId: source.videoId) {
            upload(frame, into: picture)
            source.frames.release(token: frame.token)
        }
        render(picture, into: layer)
    }

    private func upload(_ frame: VideoFrame, into picture: VideoPicture) {
        let size = CGSize(width: frame.width, height: frame.height)
        if size != picture.size || picture.planes.count != 3 {
            picture.planes = [
                makePlane(width: frame.width, height: frame.height),
                makePlane(width: frame.chromaWidth, height: frame.chromaHeight),
                makePlane(width: frame.chromaWidth, height: frame.chromaHeight),
            ].compactMap { $0 }
            picture.size = size
            guard picture.planes.count == 3 else { return }
        }
        let sources = [(frame.y, frame.strideY), (frame.u, frame.strideU), (frame.v, frame.strideV)]
        for (plane, (bytes, stride)) in zip(picture.planes, sources) {
            plane.replace(
                region: MTLRegionMake2D(0, 0, plane.width, plane.height),
                mipmapLevel: 0,
                withBytes: bytes,
                bytesPerRow: stride
            )
        }
    }

    private func makePlane(width: Int, height: Int) -> MTLTexture? {
        let description = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .r8Unorm,
            width: max(width, 1),
            height: max(height, 1),
            mipmapped: false
        )
        description.usage = .shaderRead
        description.storageMode = .shared
        return renderer.device.makeTexture(descriptor: description)
    }

    /// Draws the picture letterboxed on black; a picture without planes yet clears what the
    /// layer showed before (another stream's picture), so the stage shows through.
    private func render(_ picture: VideoPicture, into layer: CAMetalLayer) {
        let empty = picture.planes.count != 3
        if empty && !showsPicture { return }
        let area = layer.drawableSize
        guard area.width >= 1, area.height >= 1,
              let drawable = layer.nextDrawable(),
              let buffer = renderer.commands.makeCommandBuffer()
        else { return }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = drawable.texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: empty ? 0 : 1)
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = buffer.makeRenderCommandEncoder(descriptor: pass) else { return }
        if !empty {
            let fit = fitPicture(area: area, picture: picture.size)
            encoder.setViewport(MTLViewport(
                originX: fit.minX,
                originY: fit.minY,
                width: fit.width,
                height: fit.height,
                znear: 0,
                zfar: 1
            ))
            encoder.setRenderPipelineState(renderer.pipeline)
            for (index, plane) in picture.planes.enumerated() {
                encoder.setFragmentTexture(plane, index: index)
            }
            encoder.setFragmentSamplerState(renderer.sampler, index: 0)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        }
        encoder.endEncoding()
        buffer.present(drawable)
        buffer.commit()
        showsPicture = !empty
        // The next picture is copied into the same textures; it must not change this one
        // while the GPU still reads it.
        buffer.waitUntilCompleted()
    }
}
