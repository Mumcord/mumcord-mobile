// SPDX-License-Identifier: AGPL-3.0-only

//! Sharing the phone's own camera (Spec 5 FR-5.74, FR-5.75; `bridge/include/mumcord_camera.h`).
//!
//! The engine encodes and sends the camera as on the desktop; only the capture is the app's.
//! The bridge gives the engine a camera backend ([`PhoneCamera`]) whose capture is the app's
//! [`CameraCapture`] (CameraX, AVFoundation). When the engine opens the camera (the preview
//! sheet), the backend parks the engine's frame sink in [`CameraFeed`] and asks the app to start
//! its camera; the app's camera thread then pushes every picture with
//! `mumcord_camera_push_frame`. The push turns the picture upright, cuts the middle 16:9 out of
//! it, scales it down to at most [`SEND_HEIGHT`] and writes it as NV12 into a pooled buffer,
//! which goes to the engine's encoder. Nothing is sent before the user starts sharing: until
//! then the engine keeps its encoder idle.
//!
//! # Real-time path
//!
//! The push runs on the app's camera thread: it never waits (the slot is only tried, a busy
//! slot or a full pool drops the picture), never allocates (the pool's buffers are made when
//! the camera opens) and never logs. Picture bytes never pass through the generated bindings.
//!
//! # Encoder
//!
//! The engine encodes with libvpx (VP9) in software; it has no way yet to take a picture
//! encoded by the phone's hardware (MediaCodec, VideoToolbox). Software encoding sends at a
//! reduced size ([`SEND_HEIGHT`], spec section 6); the mobile cap of 540p30 is for a hardware
//! encoder.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, TryLockError};
use std::time::Instant;

use mumcord_camera::{
    CameraBackend, CameraDevice, CameraError, CameraFrame, CaptureRequest, CaptureSession,
    DeviceId, FramePool, FrameSink, POOL_BUFFERS, nv12_len,
};
use mumcord_engine::camera::{CameraFailure, CameraState, CameraView};

use crate::engine::{MumcordEngine, connected_server};
use crate::error::BridgeError;
use crate::events::lock;

/// Tallest picture sent. The software VP9 encoder runs at a reduced size (spec section 6); a
/// hardware encoder would allow the mobile cap of 540.
pub(crate) const SEND_HEIGHT: u32 = 360;
/// Widest picture sent: 16:9 of [`SEND_HEIGHT`].
pub(crate) const SEND_WIDTH: u32 = SEND_HEIGHT * 16 / 9;
/// Largest picture the app may push (each side); larger ones come from no phone camera.
const MAX_SIDE: u32 = 4096;

/// The app's camera, which the engine starts and stops. Implemented by the app (Kotlin,
/// Swift). Called on an engine thread that may hold engine locks: only schedule the work and
/// return, never call the engine from here.
#[uniffi::export(with_foreign)]
pub trait CameraCapture: Send + Sync {
    /// Start the camera (the user opened the preview) and push its pictures with
    /// `mumcord_camera_push_frame` until [`CameraCapture::stop`].
    fn start(&self);
    /// Stop the camera; pushes after this are refused anyway.
    fn stop(&self);
}

/// The own camera as the preview sheet and the sharing indicator show it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OwnCamera {
    pub state: OwnCameraState,
    /// Why the last start failed or why the camera stopped on its own; `None` after the user's
    /// own actions.
    pub problem: Option<CameraProblem>,
    /// How many people get the camera now.
    pub viewers: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OwnCameraState {
    Off,
    /// The preview runs on the phone; nothing is sent.
    Preview,
    /// "Start sharing" was tapped; the paths are being opened.
    Starting,
    /// The camera is shared with the channel.
    Sharing,
}

/// Why the camera could not be shared, or why sharing ended on its own.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CameraProblem {
    NotConnected,
    /// Another camera runs already, or the step did not fit (start without the preview).
    Busy,
    /// The system refused the camera.
    PermissionDenied,
    /// The camera could not be opened, stopped by itself or the encoder failed.
    CameraFailed,
    /// Direct connections between clients are turned off and there is no other path.
    DirectConnectionsOff,
    /// Camera sharing needs a client certificate.
    NoCertificate,
    /// The channel or the relay has no room for another camera.
    Full,
    /// The relay carrying the camera went away or ended it.
    RelayLost,
    /// The server's relay refused the camera.
    Refused,
}

/// How the app's camera ended on its own ([`MumcordEngine::camera_capture_failed`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CaptureFailure {
    /// The camera permission was taken away.
    PermissionDenied,
    /// The camera could not be opened or stopped working.
    CameraFailed,
}

pub(crate) fn own_camera(view: &CameraView) -> OwnCamera {
    OwnCamera {
        state: match view.state {
            CameraState::Off => OwnCameraState::Off,
            CameraState::Preview => OwnCameraState::Preview,
            CameraState::Requesting => OwnCameraState::Starting,
            CameraState::OnAir => OwnCameraState::Sharing,
        },
        problem: view.error.as_ref().map(camera_problem),
        viewers: u32::try_from(view.viewers.len()).unwrap_or(u32::MAX),
    }
}

fn camera_problem(failure: &CameraFailure) -> CameraProblem {
    match failure {
        CameraFailure::NotConnected => CameraProblem::NotConnected,
        CameraFailure::Busy => CameraProblem::Busy,
        CameraFailure::Camera(CameraError::PermissionDenied) => CameraProblem::PermissionDenied,
        CameraFailure::Camera(_) => CameraProblem::CameraFailed,
        CameraFailure::Mode => CameraProblem::DirectConnectionsOff,
        CameraFailure::NoCertificate => CameraProblem::NoCertificate,
        CameraFailure::RoomFull | CameraFailure::RelayFull => CameraProblem::Full,
        CameraFailure::RelayDown | CameraFailure::RelayEnded => CameraProblem::RelayLost,
        CameraFailure::UseNotAllowed
        | CameraFailure::Refused
        | CameraFailure::RateLimited
        | CameraFailure::NotRegistered
        | CameraFailure::GroupNotAllowed
        | CameraFailure::NotSameChannel
        | CameraFailure::NotInChannel
        | CameraFailure::NoHello => CameraProblem::Refused,
    }
}

/// The engine's end of a running capture.
struct Feeder {
    sink: Box<dyn FrameSink>,
    pool: FramePool,
    started: Instant,
}

/// Where the app's pictures go; leaked per engine like the video registry, so the handle the
/// app holds never dangles.
pub(crate) struct CameraFeed {
    /// The running capture; `None` while the camera is closed. Locked only to open and close it
    /// and tried (never waited for) by the push.
    slot: Mutex<Option<Feeder>>,
    capture: Mutex<Option<Arc<dyn CameraCapture>>>,
}

/// What a push did; the values of `MumcordCameraPush` in `mumcord_camera.h`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum PushResult {
    /// The picture went to the encoder.
    Sent = 0,
    /// The encoder is behind or the camera is just opening or closing; the picture was dropped.
    Dropped = 1,
    /// The camera is not open in the engine; stop the camera.
    NotOpen = 2,
    /// The description of the picture is not usable.
    Invalid = 3,
}

impl CameraFeed {
    pub(crate) fn start() -> &'static Self {
        Box::leak(Box::new(Self {
            slot: Mutex::new(None),
            capture: Mutex::new(None),
        }))
    }

    pub(crate) fn handle(&'static self) -> u64 {
        std::ptr::from_ref(self) as usize as u64
    }

    pub(crate) fn set_capture(&self, capture: Option<Arc<dyn CameraCapture>>) {
        *lock(&self.capture) = capture;
    }

    fn open(&self, sink: Box<dyn FrameSink>) -> Result<(), CameraError> {
        let capture = lock(&self.capture).clone().ok_or(CameraError::NoCamera)?;
        *lock(&self.slot) = Some(Feeder {
            sink,
            pool: FramePool::new(POOL_BUFFERS, nv12_len(SEND_WIDTH, SEND_HEIGHT)),
            started: Instant::now(),
        });
        capture.start();
        Ok(())
    }

    fn close(&self) {
        // Waits for a push in progress, so no picture reaches the sink after this returns.
        lock(&self.slot).take();
        if let Some(capture) = lock(&self.capture).clone() {
            capture.stop();
        }
    }

    fn fail(&self, failure: CaptureFailure) {
        if let Some(feeder) = lock(&self.slot).as_mut() {
            feeder.sink.ended(match failure {
                CaptureFailure::PermissionDenied => CameraError::PermissionDenied,
                CaptureFailure::CameraFailed => {
                    CameraError::Backend("the phone's camera stopped".to_owned())
                }
            });
        }
    }

    /// The real-time push (module docs).
    pub(crate) fn push(&self, image: &Image<'_>) -> PushResult {
        let mut slot = match self.slot.try_lock() {
            Ok(slot) => slot,
            Err(TryLockError::WouldBlock) => return PushResult::Dropped,
            Err(TryLockError::Poisoned(poisoned)) => poisoned.into_inner(),
        };
        let Some(feeder) = slot.as_mut() else {
            return PushResult::NotOpen;
        };
        let Some(size) = send_size(image.width, image.height, image.rotation) else {
            return PushResult::Invalid;
        };
        let Some(mut data) = feeder.pool.acquire() else {
            return PushResult::Dropped;
        };
        convert(image, size, &mut data);
        feeder.sink.frame(CameraFrame {
            width: size.0,
            height: size.1,
            timestamp: feeder.started.elapsed(),
            data,
        });
        PushResult::Sent
    }
}

/// The engine's camera backend on a phone: one camera, the app's. Front or back is the app's
/// choice while it runs; the picture size may change with it.
pub(crate) struct PhoneCamera(pub(crate) &'static CameraFeed);

impl CameraBackend for PhoneCamera {
    fn devices(&self) -> Result<Vec<CameraDevice>, CameraError> {
        Ok(vec![CameraDevice {
            id: DeviceId::from_os_key("phone", "camera"),
            name: "Camera".to_owned(),
            is_default: true,
        }])
    }

    fn open(
        &self,
        _device: &DeviceId,
        _request: CaptureRequest,
        sink: Box<dyn FrameSink>,
    ) -> Result<Box<dyn CaptureSession>, CameraError> {
        self.0.open(sink)?;
        Ok(Box::new(PhoneCapture(self.0)))
    }
}

/// A running capture; dropping it stops the app's camera.
struct PhoneCapture(&'static CameraFeed);

impl CaptureSession for PhoneCapture {}

impl Drop for PhoneCapture {
    fn drop(&mut self) {
        self.0.close();
    }
}

/// One picture of the app's camera in YUV 4:2:0, in the camera's orientation.
pub(crate) struct Image<'a> {
    pub(crate) width: u32,
    pub(crate) height: u32,
    /// Clockwise degrees that turn the picture upright: 0, 90, 180 or 270.
    pub(crate) rotation: u32,
    pub(crate) y: &'a [u8],
    pub(crate) y_stride: usize,
    pub(crate) u: &'a [u8],
    pub(crate) v: &'a [u8],
    pub(crate) uv_stride: usize,
    /// 1 for planar chroma (I420), 2 for interleaved chroma (NV12, NV21).
    pub(crate) uv_pixel_stride: usize,
}

impl Image<'_> {
    /// Whether every pixel the conversion may read lies inside the planes.
    fn fits(&self) -> bool {
        let (w, h) = (self.width as usize, self.height as usize);
        if !(2..=MAX_SIDE as usize).contains(&w)
            || !(2..=MAX_SIDE as usize).contains(&h)
            || !matches!(self.rotation, 0 | 90 | 180 | 270)
            || !matches!(self.uv_pixel_stride, 1 | 2)
            || self.y_stride < w
        {
            return false;
        }
        let (cw, ch) = (w.div_ceil(2), h.div_ceil(2));
        let chroma = (ch - 1) * self.uv_stride + (cw - 1) * self.uv_pixel_stride + 1;
        self.uv_stride > (cw - 1) * self.uv_pixel_stride
            && self.y.len() >= (h - 1) * self.y_stride + w
            && self.u.len() >= chroma
            && self.v.len() >= chroma
    }
}

/// The upright size of a `width` × `height` picture turned by `rotation`.
fn upright(width: u32, height: u32, rotation: u32) -> (u32, u32) {
    if rotation.is_multiple_of(180) {
        (width, height)
    } else {
        (height, width)
    }
}

/// The middle 16:9 of an upright picture: offset and size.
fn crop(upright: (u32, u32)) -> (u32, u32, u32, u32) {
    let (w, h) = upright;
    let (cw, ch) = if u64::from(w) * 9 >= u64::from(h) * 16 {
        (h * 16 / 9, h)
    } else {
        (w, w * 9 / 16)
    };
    ((w - cw) / 2, (h - ch) / 2, cw, ch)
}

/// The size sent for a picture: the 16:9 crop, at most [`SEND_HEIGHT`] high, with even height
/// and a width in eights as the encoders take them. `None` when that leaves no picture.
pub(crate) fn send_size(width: u32, height: u32, rotation: u32) -> Option<(u32, u32)> {
    let (_, _, cw, ch) = crop(upright(width, height, rotation));
    let h = ch.min(SEND_HEIGHT) & !1;
    let w = (h * 16 / 9).min(cw) & !7;
    (w >= 8 && h >= 2).then_some((w, h))
}

/// Writes `image` upright, cropped to 16:9 and scaled to `size` into `dst` as NV12 without row
/// padding. Nearest-neighbour sampling: cheap enough for the camera thread, and the encoder
/// softens the picture anyway. No allocation.
fn convert(image: &Image<'_>, size: (u32, u32), dst: &mut [u8]) {
    let (w, h) = (image.width as usize, image.height as usize);
    let (x0, y0, cw, ch) = crop(upright(image.width, image.height, image.rotation));
    let (x0, y0, cw, ch) = (x0 as usize, y0 as usize, cw as usize, ch as usize);
    let (ow, oh) = (size.0 as usize, size.1 as usize);
    let rotation = image.rotation;
    // Upright crop coordinates of output pixel (ox, oy), at the pixel's centre, then the
    // camera's own coordinates.
    let source = |ox: usize, oy: usize| {
        let ux = x0 + (2 * ox + 1) * cw / (2 * ow);
        let uy = y0 + (2 * oy + 1) * ch / (2 * oh);
        match rotation {
            90 => (uy, h - 1 - ux),
            180 => (w - 1 - ux, h - 1 - uy),
            270 => (w - 1 - uy, ux),
            _ => (ux, uy),
        }
    };
    let (luma, chroma) = dst.split_at_mut(ow * oh);
    for (oy, row) in luma.chunks_exact_mut(ow).enumerate() {
        for (ox, out) in row.iter_mut().enumerate() {
            let (sx, sy) = source(ox, oy);
            *out = image.y[sy * image.y_stride + sx];
        }
    }
    for (cy, row) in chroma.chunks_exact_mut(ow).take(oh / 2).enumerate() {
        for (cx, [u, v]) in row.as_chunks_mut::<2>().0.iter_mut().enumerate() {
            let (sx, sy) = source(2 * cx, 2 * cy);
            let at = sy / 2 * image.uv_stride + sx / 2 * image.uv_pixel_stride;
            *u = image.u[at];
            *v = image.v[at];
        }
    }
}

/// One picture as the app describes it (`MumcordCameraImage` in `mumcord_camera.h`).
#[repr(C)]
#[derive(Debug)]
pub struct MumcordCameraImage {
    pub width: u32,
    pub height: u32,
    pub rotation: u32,
    pub y: *const u8,
    pub y_len: usize,
    pub y_stride: u32,
    pub u: *const u8,
    pub u_len: usize,
    pub v: *const u8,
    pub v_len: usize,
    pub uv_stride: u32,
    pub uv_pixel_stride: u32,
}

/// The feed behind `handle`; `None` for 0.
///
/// # Safety
///
/// `handle` is 0 or a value of `MumcordEngine::camera_handle`.
unsafe fn feed(handle: u64) -> Option<&'static CameraFeed> {
    // SAFETY: the caller's contract; feeds are never freed.
    (handle != 0).then(|| unsafe { &*(handle as usize as *const CameraFeed) })
}

/// Hands one camera picture to the engine (module docs). Returns a `PushResult` value.
///
/// # Safety
///
/// `handle` is 0 or the value of `MumcordEngine::camera_handle`; `image` is null or points to a
/// `MumcordCameraImage` whose planes are readable for their lengths during the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mumcord_camera_push_frame(
    handle: u64,
    image: *const MumcordCameraImage,
) -> i32 {
    // A panic must not unwind into the app's camera thread.
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the caller's contract.
        let (Some(feed), Some(raw)) = (unsafe { feed(handle) }, unsafe { image.as_ref() }) else {
            return PushResult::NotOpen;
        };
        if raw.y.is_null() || raw.u.is_null() || raw.v.is_null() {
            return PushResult::Invalid;
        }
        // SAFETY: the caller's contract: each plane is readable for its length.
        let image = unsafe {
            Image {
                width: raw.width,
                height: raw.height,
                rotation: raw.rotation,
                y: std::slice::from_raw_parts(raw.y, raw.y_len),
                y_stride: raw.y_stride as usize,
                u: std::slice::from_raw_parts(raw.u, raw.u_len),
                v: std::slice::from_raw_parts(raw.v, raw.v_len),
                uv_stride: raw.uv_stride as usize,
                uv_pixel_stride: raw.uv_pixel_stride as usize,
            }
        };
        if !image.fits() {
            return PushResult::Invalid;
        }
        feed.push(&image)
    }))
    .unwrap_or(PushResult::Dropped) as i32
}

#[uniffi::export]
impl MumcordEngine {
    /// The app's camera, which the engine starts for the preview and stops when sharing ends;
    /// `None` takes it away (the camera can then not be opened).
    pub fn set_camera_capture(&self, capture: Option<Arc<dyn CameraCapture>>) {
        self.camera.set_capture(capture);
    }

    /// The handle for `mumcord_camera_push_frame` (`bridge/include/mumcord_camera.h`). Valid
    /// until the process ends.
    pub fn camera_handle(&self) -> u64 {
        self.camera.handle()
    }

    /// Opens the preview (the sheet "Share your camera"): the camera starts on the phone and
    /// nothing is sent. The state arrives as [`BridgeEvent::OwnCameraChanged`](crate::BridgeEvent).
    pub async fn open_camera_preview(&self) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            engine
                .camera_preview_start(server)
                .map_err(|failure| camera_error(&failure))
        })
        .await
    }

    /// "Start sharing" in the preview: returns once the camera is shared, or with the reason
    /// it is not (the preview stays open and its state names the problem).
    pub async fn start_camera_sharing(&self) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            engine
                .camera_turn_on(server)
                .await
                .map_err(|failure| camera_error(&failure))
        })
        .await
    }

    /// Cancel in the preview or Stop while sharing: the camera turns off and stops sending.
    pub async fn stop_camera(&self) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            engine.camera_turn_off(connected_server(&connection)?);
            Ok(())
        })
        .await
    }

    /// The app's camera ended on its own: the engine turns the camera off and names the
    /// problem.
    pub fn camera_capture_failed(&self, failure: CaptureFailure) {
        self.camera.fail(failure);
    }
}

fn camera_error(failure: &CameraFailure) -> BridgeError {
    match failure {
        CameraFailure::NotConnected => BridgeError::not_connected(),
        CameraFailure::Camera(CameraError::PermissionDenied) => BridgeError::PermissionDenied {
            detail: "camera access was denied".to_owned(),
        },
        other => BridgeError::internal(format!("camera not shared: {}", other.code())),
    }
}

#[cfg(test)]
mod tests {
    use std::cell::Cell;
    use std::sync::mpsc;

    use super::*;

    /// Upright test pattern: luma from the position, so a wrong turn or crop shows.
    fn pattern(x: usize, y: usize) -> u8 {
        ((x * 3 + y * 7) % 251) as u8
    }

    /// A `w` × `h` camera picture that shows [`pattern`] once turned by `rotation`, with padded
    /// rows and interleaved chroma (U = 100 + x/8, V = 200 - y/8 of the upright picture).
    struct Camera {
        width: usize,
        height: usize,
        rotation: u32,
        y: Vec<u8>,
        uv: Vec<u8>,
        stride: usize,
    }

    impl Camera {
        fn new(width: usize, height: usize, rotation: u32) -> Self {
            let stride = width + 16;
            let mut y = vec![0; stride * height];
            let mut uv = vec![0; stride * height.div_ceil(2)];
            let (uw, uh) = upright(width as u32, height as u32, rotation);
            for uy in 0..uh as usize {
                for ux in 0..uw as usize {
                    let (sx, sy) = match rotation {
                        90 => (uy, height - 1 - ux),
                        180 => (width - 1 - ux, height - 1 - uy),
                        270 => (width - 1 - uy, ux),
                        _ => (ux, uy),
                    };
                    y[sy * stride + sx] = pattern(ux, uy);
                    let at = sy / 2 * stride + sx / 2 * 2;
                    uv[at] = (100 + ux / 8) as u8;
                    uv[at + 1] = (200 - uy / 8) as u8;
                }
            }
            Self {
                width,
                height,
                rotation,
                y,
                uv,
                stride,
            }
        }

        fn image(&self) -> Image<'_> {
            Image {
                width: self.width as u32,
                height: self.height as u32,
                rotation: self.rotation,
                y: &self.y,
                y_stride: self.stride,
                u: &self.uv,
                v: &self.uv[1..],
                uv_stride: self.stride,
                uv_pixel_stride: 2,
            }
        }
    }

    type Sent = (u32, u32, Vec<u8>);

    struct Sink(mpsc::Sender<Sent>, Option<Vec<CameraFrame>>);

    impl FrameSink for Sink {
        fn frame(&mut self, frame: CameraFrame) {
            let len = nv12_len(frame.width, frame.height);
            let _ = self
                .0
                .send((frame.width, frame.height, frame.data[..len].to_vec()));
            // A held list keeps the buffers, like an encoder that is behind.
            if let Some(held) = self.1.as_mut() {
                held.push(frame);
            }
        }

        fn ended(&mut self, _reason: CameraError) {}
    }

    struct Capture(mpsc::Sender<&'static str>);

    impl CameraCapture for Capture {
        fn start(&self) {
            let _ = self.0.send("start");
        }

        fn stop(&self) {
            let _ = self.0.send("stop");
        }
    }

    fn opened(
        hold: bool,
    ) -> (
        &'static CameraFeed,
        Box<dyn CaptureSession>,
        mpsc::Receiver<Sent>,
    ) {
        let feed = CameraFeed::start();
        let (calls, _) = mpsc::channel();
        feed.set_capture(Some(Arc::new(Capture(calls))));
        let (tx, rx) = mpsc::channel();
        let sink = Box::new(Sink(tx, hold.then(Vec::new)));
        let session = PhoneCamera(feed)
            .open(
                &DeviceId::from_os_key("phone", "camera"),
                CaptureRequest::default(),
                sink,
            )
            .expect("open");
        (feed, session, rx)
    }

    #[test]
    fn sizes_are_the_middle_16_9_capped_at_the_send_height() {
        assert_eq!(send_size(1280, 720, 0), Some((640, 360)));
        assert_eq!(send_size(640, 480, 0), Some((640, 360)));
        // A portrait phone: the upright picture is tall, its middle strip is sent.
        assert_eq!(send_size(1280, 720, 90), Some((640, 360)));
        assert_eq!(send_size(640, 480, 270), Some((480, 270)));
        assert_eq!(send_size(320, 240, 0), Some((320, 180)));
        assert_eq!(send_size(4, 4, 0), None);
    }

    #[test]
    fn pictures_arrive_upright_cropped_and_scaled_in_every_rotation() {
        for rotation in [0_u32, 90, 180, 270] {
            let (feed, _session, rx) = opened(false);
            let camera = if rotation.is_multiple_of(180) {
                Camera::new(640, 360, rotation)
            } else {
                Camera::new(360, 640, rotation)
            };
            assert_eq!(feed.push(&camera.image()), PushResult::Sent);
            let (w, h, nv12) = rx.try_recv().expect("a frame");
            let (uw, uh) = upright(camera.width as u32, camera.height as u32, rotation);
            assert_eq!(
                (w, h),
                send_size(camera.width as u32, camera.height as u32, rotation).unwrap()
            );
            let (x0, y0, cw, ch) = crop((uw, uh));
            let (w, h) = (w as usize, h as usize);
            for (ox, oy) in [
                (0, 0),
                (w - 1, 0),
                (w / 2, h / 2),
                (0, h - 1),
                (w - 1, h - 1),
            ] {
                let ux = x0 as usize + (2 * ox + 1) * cw as usize / (2 * w);
                let uy = y0 as usize + (2 * oy + 1) * ch as usize / (2 * h);
                assert_eq!(
                    nv12[oy * w + ox],
                    pattern(ux, uy),
                    "luma, rotation {rotation}"
                );
            }
            let at = w * h + (h / 4) * w + (w / 4) * 2;
            let ux = x0 as usize + (2 * (2 * (w / 4)) + 1) * cw as usize / (2 * w);
            assert!(
                nv12[at].abs_diff((100 + ux / 8) as u8) <= 1,
                "U, rotation {rotation}"
            );
        }
    }

    #[test]
    fn nothing_is_accepted_before_open_and_after_close() {
        let feed = CameraFeed::start();
        let camera = Camera::new(640, 360, 0);
        assert_eq!(feed.push(&camera.image()), PushResult::NotOpen);
        let (feed, session, _rx) = opened(false);
        drop(session);
        assert_eq!(feed.push(&camera.image()), PushResult::NotOpen);
    }

    #[test]
    fn opening_starts_and_dropping_stops_the_app_camera() {
        let feed = CameraFeed::start();
        let (calls, rx) = mpsc::channel();
        feed.set_capture(Some(Arc::new(Capture(calls))));
        let (tx, _frames) = mpsc::channel();
        let session = PhoneCamera(feed)
            .open(
                &DeviceId::from_os_key("phone", "camera"),
                CaptureRequest::default(),
                Box::new(Sink(tx, None)),
            )
            .expect("open");
        assert_eq!(rx.try_recv(), Ok("start"));
        drop(session);
        assert_eq!(rx.try_recv(), Ok("stop"));
    }

    #[test]
    fn without_the_app_camera_there_is_no_camera() {
        let feed = CameraFeed::start();
        let (tx, _frames) = mpsc::channel();
        let opened = PhoneCamera(feed).open(
            &DeviceId::from_os_key("phone", "camera"),
            CaptureRequest::default(),
            Box::new(Sink(tx, None)),
        );
        assert_eq!(opened.err(), Some(CameraError::NoCamera));
    }

    #[test]
    fn a_full_pool_drops_pictures_instead_of_waiting() {
        let (feed, _session, _rx) = opened(true);
        let camera = Camera::new(1280, 720, 0);
        for _ in 0..POOL_BUFFERS {
            assert_eq!(feed.push(&camera.image()), PushResult::Sent);
        }
        assert_eq!(feed.push(&camera.image()), PushResult::Dropped);
    }

    #[test]
    fn a_busy_slot_drops_pictures_instead_of_waiting() {
        let (feed, _session, _rx) = opened(false);
        let camera = Camera::new(640, 360, 0);
        let held = lock(&feed.slot);
        let pushed = std::thread::scope(|scope| {
            scope
                .spawn(|| feed.push(&camera.image()))
                .join()
                .expect("push")
        });
        drop(held);
        assert_eq!(pushed, PushResult::Dropped);
    }

    #[test]
    fn short_or_strange_planes_are_refused() {
        let camera = Camera::new(640, 360, 0);
        let mut image = camera.image();
        image.y = &camera.y[..camera.y.len() - 20];
        assert!(!image.fits());
        let mut image = camera.image();
        image.rotation = 45;
        assert!(!image.fits());
        let mut image = camera.image();
        image.uv_pixel_stride = 3;
        assert!(!image.fits());
        assert!(camera.image().fits());
        // SAFETY: a null image is allowed.
        let null =
            unsafe { mumcord_camera_push_frame(CameraFeed::start().handle(), std::ptr::null()) };
        assert_eq!(null, PushResult::NotOpen as i32);
    }

    #[test]
    fn the_c_function_pushes_a_described_picture() {
        let (feed, _session, rx) = opened(false);
        let camera = Camera::new(1280, 720, 0);
        let raw = MumcordCameraImage {
            width: 1280,
            height: 720,
            rotation: 0,
            y: camera.y.as_ptr(),
            y_len: camera.y.len(),
            y_stride: camera.stride as u32,
            u: camera.uv.as_ptr(),
            u_len: camera.uv.len(),
            v: camera.uv[1..].as_ptr(),
            v_len: camera.uv.len() - 1,
            uv_stride: camera.stride as u32,
            uv_pixel_stride: 2,
        };
        // SAFETY: the handle is a feed's, the planes live for the call.
        let result = unsafe { mumcord_camera_push_frame(feed.handle(), &raw) };
        assert_eq!(result, PushResult::Sent as i32);
        assert_eq!(rx.try_recv().map(|(w, h, _)| (w, h)), Ok((640, 360)));
    }

    thread_local! {
        static ALLOCATIONS: Cell<usize> = const { Cell::new(0) };
    }

    /// Counts the allocations of the current thread, for the real-time test below.
    struct Counting;

    // SAFETY: forwards to the system allocator.
    unsafe impl std::alloc::GlobalAlloc for Counting {
        unsafe fn alloc(&self, layout: std::alloc::Layout) -> *mut u8 {
            let _ = ALLOCATIONS.try_with(|n| n.set(n.get() + 1));
            // SAFETY: the caller's contract.
            unsafe { std::alloc::System.alloc(layout) }
        }

        unsafe fn dealloc(&self, ptr: *mut u8, layout: std::alloc::Layout) {
            // SAFETY: the caller's contract.
            unsafe { std::alloc::System.dealloc(ptr, layout) }
        }
    }

    #[global_allocator]
    static COUNTING: Counting = Counting;

    /// A sink that drops each frame at once (the buffer goes back to the pool), like the
    /// engine's lock-free queue.
    struct Discard;

    impl FrameSink for Discard {
        fn frame(&mut self, _frame: CameraFrame) {}
        fn ended(&mut self, _reason: CameraError) {}
    }

    #[test]
    fn a_push_allocates_nothing() {
        let feed = CameraFeed::start();
        let (calls, _) = mpsc::channel();
        feed.set_capture(Some(Arc::new(Capture(calls))));
        let _session = PhoneCamera(feed)
            .open(
                &DeviceId::from_os_key("phone", "camera"),
                CaptureRequest::default(),
                Box::new(Discard),
            )
            .expect("open");
        let camera = Camera::new(1280, 720, 90);
        let image = camera.image();
        let before = ALLOCATIONS.with(Cell::get);
        for _ in 0..10 {
            assert_eq!(feed.push(&image), PushResult::Sent);
        }
        assert_eq!(ALLOCATIONS.with(Cell::get), before);
    }

    #[test]
    fn engine_failures_name_the_problem() {
        assert_eq!(
            camera_problem(&CameraFailure::Camera(CameraError::PermissionDenied)),
            CameraProblem::PermissionDenied
        );
        assert_eq!(
            camera_problem(&CameraFailure::Mode),
            CameraProblem::DirectConnectionsOff
        );
        assert_eq!(
            camera_problem(&CameraFailure::RoomFull),
            CameraProblem::Full
        );
        let mut view = CameraView::off(1);
        view.state = CameraState::Requesting;
        assert_eq!(own_camera(&view).state, OwnCameraState::Starting);
        assert_eq!(own_camera(&view).viewers, 0);
    }
}
