// SPDX-License-Identifier: AGPL-3.0-only

//! Decoded stream pictures on their way to the app (`bridge/include/mumcord_video.h`).
//!
//! The engine decodes every watched stream on its own decode thread and hands each picture to
//! [`Router`]. The picture is copied once into a reused buffer of that stream's [`Slot`]; the
//! slot keeps only the newest picture, so a picture the app has not taken yet is replaced
//! (dropped) instead of queued. When a picture lands in an empty slot the bridge's video thread
//! tells the app through [`VideoSink::on_frame_ready`]. The app's render thread then takes the
//! picture with `mumcord_video_frame_acquire`, uploads the planes straight from the bridge's
//! memory and gives them back with `mumcord_video_frame_release`. No picture bytes cross the
//! generated bindings, which would copy them twice more and allocate per frame.
//!
//! # Lifetime
//!
//! [`VideoFrames`] is leaked per engine (a few bytes), so the handle the app holds never
//! dangles. An acquired picture is reference-counted: it stays valid until it is released, also
//! when the stream stops, its size changes or the engine object is released meanwhile.

use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::Thread;

use mumcord_engine::ServerId;
use mumcord_engine::screen_share::wire::{Codec, Frame};
use mumcord_engine::screen_share::{I420Frame, ScreenFrameSink};

use crate::events::lock;
use crate::streams::StreamKind;

/// Widest picture handed to the app: the largest a desktop share sends (1440p). A larger one
/// comes from a misbehaving sender and is dropped instead of taking phone memory.
pub(crate) const MAX_WIDTH: u32 = 2560;
/// Tallest picture handed to the app, see [`MAX_WIDTH`].
pub(crate) const MAX_HEIGHT: u32 = 1440;
/// Buffers per stream: one being written, the newest waiting, one the app holds. More are only
/// needed while the app holds several pictures of one stream; the decoder drops frames then.
const BUFFERS: usize = 3;
/// Pictures the app may hold at once across all streams before holding more allocates.
const HELD: usize = 16;

/// Receives the news that a stream has a new picture; implemented by the app (Kotlin, Swift).
/// Called on the bridge's video thread (`mumcord-bridge-video`), never on the engine's decode
/// thread or an audio thread. It should only schedule a redraw and return.
#[uniffi::export(with_foreign)]
pub trait VideoSink: Send + Sync {
    /// `video_id` ([`Stream::video_id`](crate::Stream::video_id)) has a new `width` × `height`
    /// picture to take with `mumcord_video_frame_acquire`. Not called again for this stream
    /// until that picture was taken; newer pictures replace it meanwhile.
    fn on_frame_ready(&self, video_id: u64, width: u32, height: u32);
}

/// The id of a stream's pictures: the kind in the upper half, the person's session below.
pub(crate) fn video_id(kind: StreamKind, session: u32) -> u64 {
    let kind = match kind {
        StreamKind::Screen => 1_u64,
        StreamKind::Camera => 2,
    };
    (kind << 32) | u64::from(session)
}

/// One decoded I420 picture: Y, then U, then V, without row padding.
pub(crate) struct Picture {
    width: u32,
    height: u32,
    data: Vec<u8>,
}

impl Picture {
    fn chroma_size(&self) -> (usize, usize) {
        (
            (self.width as usize).div_ceil(2),
            (self.height as usize).div_ceil(2),
        )
    }
}

/// The pictures of one watched stream.
struct Slot {
    inner: Mutex<SlotInner>,
    /// A picture arrived in the empty slot and the app was not told yet.
    ready: AtomicBool,
}

struct SlotInner {
    /// Every buffer of the stream. One only this vector holds is free to write.
    pool: Vec<Arc<Picture>>,
    /// The newest picture, until the app takes it.
    latest: Option<Arc<Picture>>,
}

impl Slot {
    fn new() -> Self {
        Self {
            inner: Mutex::new(SlotInner {
                pool: Vec::with_capacity(BUFFERS),
                latest: None,
            }),
            ready: AtomicBool::new(false),
        }
    }

    /// Copies `frame` into a free buffer and makes it the newest picture. `true` when the slot
    /// was empty, so the app must be told. Allocates only for the first pictures and when the
    /// size grows; drops the picture when the app holds every buffer.
    fn put(&self, frame: &I420Frame) -> bool {
        let free = {
            let mut inner = lock(&self.inner);
            match inner.pool.iter().position(|p| Arc::strong_count(p) == 1) {
                Some(index) => Some(inner.pool.swap_remove(index)),
                None if inner.pool.len() < BUFFERS => Some(Arc::new(Picture {
                    width: 0,
                    height: 0,
                    data: Vec::new(),
                })),
                None => None,
            }
        };
        let Some(mut picture) = free else {
            return false;
        };
        // Only this function holds a buffer taken out of the pool.
        let Some(target) = Arc::get_mut(&mut picture) else {
            return false;
        };
        target.width = frame.width();
        target.height = frame.height();
        target.data.clear();
        target.data.extend_from_slice(frame.data());
        let mut inner = lock(&self.inner);
        inner.pool.push(Arc::clone(&picture));
        let was_empty = inner.latest.is_none();
        inner.latest = Some(picture);
        was_empty
    }
}

/// The pictures of every stream the user watches, and the ones the app holds.
pub(crate) struct VideoFrames {
    slots: Mutex<HashMap<u64, Arc<Slot>>>,
    /// Acquired pictures by token, until the app releases them.
    held: Mutex<HashMap<u64, Arc<Picture>>>,
    next_token: AtomicU64,
    sink: Mutex<Option<Arc<dyn VideoSink>>>,
    /// The video thread, woken when a slot gets ready or the engine closes.
    notifier: OnceLock<Thread>,
    closed: AtomicBool,
}

impl VideoFrames {
    /// A new registry with its video thread. Leaked on purpose (see the module docs).
    pub(crate) fn start() -> std::io::Result<&'static Self> {
        let frames: &'static Self = Box::leak(Box::new(Self {
            slots: Mutex::new(HashMap::new()),
            held: Mutex::new(HashMap::with_capacity(HELD)),
            next_token: AtomicU64::new(1),
            sink: Mutex::new(None),
            notifier: OnceLock::new(),
            closed: AtomicBool::new(false),
        }));
        let thread = std::thread::Builder::new()
            .name("mumcord-bridge-video".into())
            .spawn(move || frames.notify_loop())?;
        // Set right after the spawn; until then no stream is open, so nothing needs waking.
        let _ = frames.notifier.set(thread.thread().clone());
        Ok(frames)
    }

    pub(crate) fn handle(&'static self) -> u64 {
        std::ptr::from_ref(self) as usize as u64
    }

    pub(crate) fn set_sink(&self, sink: Option<Arc<dyn VideoSink>>) {
        *lock(&self.sink) = sink;
    }

    /// Pictures of `id` are kept from now on. A slot that is already open stays as it is.
    pub(crate) fn open(&self, id: u64) {
        lock(&self.slots)
            .entry(id)
            .or_insert_with(|| Arc::new(Slot::new()));
    }

    /// Pictures of `id` are dropped from now on; pictures the app holds stay valid.
    pub(crate) fn close(&self, id: u64) {
        lock(&self.slots).remove(&id);
    }

    /// Closes every slot except those in `keep`.
    pub(crate) fn retain(&self, keep: impl Fn(u64) -> bool) {
        lock(&self.slots).retain(|id, _| keep(*id));
    }

    /// The engine object is gone: no more pictures, no more calls into the app.
    pub(crate) fn shut_down(&self) {
        lock(&self.slots).clear();
        self.set_sink(None);
        self.closed.store(true, Ordering::Release);
        self.wake();
    }

    fn wake(&self) {
        if let Some(thread) = self.notifier.get() {
            thread.unpark();
        }
    }

    /// A decoded picture of stream `id`, from the engine's decode thread.
    pub(crate) fn put(&self, id: u64, frame: &I420Frame) {
        if frame.width() > MAX_WIDTH || frame.height() > MAX_HEIGHT {
            return;
        }
        let Some(slot) = lock(&self.slots).get(&id).cloned() else {
            return;
        };
        if slot.put(frame) {
            slot.ready.store(true, Ordering::Release);
            self.wake();
        }
    }

    /// Takes the newest picture of `id` for the app: its token, or `None` without one.
    pub(crate) fn acquire(&self, id: u64) -> Option<(u64, Arc<Picture>)> {
        let slot = lock(&self.slots).get(&id).cloned()?;
        let picture = lock(&slot.inner).latest.take()?;
        let token = self.next_token.fetch_add(1, Ordering::Relaxed);
        lock(&self.held).insert(token, Arc::clone(&picture));
        Some((token, picture))
    }

    /// Gives an acquired picture back; an unknown or already released token does nothing.
    pub(crate) fn release(&self, token: u64) {
        let released = lock(&self.held).remove(&token);
        drop(released);
    }

    /// The video thread: tells the app about every slot that got ready.
    fn notify_loop(&self) {
        let mut ready = Vec::new();
        while !self.closed.load(Ordering::Acquire) {
            std::thread::park();
            ready.clear();
            for (id, slot) in lock(&self.slots).iter() {
                if slot.ready.swap(false, Ordering::AcqRel)
                    && let Some(latest) = &lock(&slot.inner).latest
                {
                    ready.push((*id, latest.width, latest.height));
                }
            }
            let Some(sink) = lock(&self.sink).clone() else {
                continue;
            };
            for &(id, width, height) in &ready {
                sink.on_frame_ready(id, width, height);
            }
        }
    }
}

/// Where the engine delivers the pictures of one kind of stream.
pub(crate) struct Router {
    pub(crate) frames: &'static VideoFrames,
    pub(crate) kind: StreamKind,
}

impl ScreenFrameSink for Router {
    /// Encoded frames come only until the bridge has asked the engine to decode the stream; the
    /// keyframe asked for after that starts the pictures.
    fn on_encoded(&self, _server_id: ServerId, _sharer: u32, _frame: &Frame) {}

    fn on_decoded(&self, _server_id: ServerId, sharer: u32, _codec: Codec, frame: &I420Frame) {
        self.frames.put(video_id(self.kind, sharer), frame);
    }
}

/// One acquired picture as the app reads it (`MumcordVideoFrame` in `mumcord_video.h`).
#[repr(C)]
#[derive(Debug)]
pub struct MumcordVideoFrame {
    /// Hand it to `mumcord_video_frame_release`.
    pub token: u64,
    pub width: u32,
    pub height: u32,
    pub y: *const u8,
    pub u: *const u8,
    pub v: *const u8,
    pub stride_y: u32,
    pub stride_u: u32,
    pub stride_v: u32,
}

/// The registry behind `handle`; `None` for 0.
///
/// # Safety
///
/// `handle` is 0 or a value of `MumcordEngine::video_handle`.
unsafe fn frames(handle: u64) -> Option<&'static VideoFrames> {
    // SAFETY: the caller's contract; registries are never freed.
    (handle != 0).then(|| unsafe { &*(handle as usize as *const VideoFrames) })
}

/// Takes the newest picture of stream `video_id` and fills `frame` with its planes. Returns
/// `false` and leaves `frame` untouched when there is no new picture since the last call. The
/// planes stay valid and unchanged until `mumcord_video_frame_release` with `frame.token`, also
/// if the stream stops or the engine object is released meanwhile.
///
/// # Safety
///
/// `handle` is 0 or the value of `MumcordEngine::video_handle`; `frame` is null or valid for
/// writing one `MumcordVideoFrame`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mumcord_video_frame_acquire(
    handle: u64,
    video_id: u64,
    frame: *mut MumcordVideoFrame,
) -> bool {
    if frame.is_null() {
        return false;
    }
    // A panic must not unwind into the app's render thread.
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the caller's contract.
        let Some(frames) = (unsafe { frames(handle) }) else {
            return false;
        };
        let Some((token, picture)) = frames.acquire(video_id) else {
            return false;
        };
        let (chroma_width, chroma_height) = picture.chroma_size();
        let luma = picture.width as usize * picture.height as usize;
        let chroma = chroma_width * chroma_height;
        let base = picture.data.as_ptr();
        // SAFETY: `frame` is valid for writing (the caller's contract); the offsets stay inside
        // `data`, which holds the luma plane and two chroma planes. The held `Arc` keeps the
        // buffer alive and unwritten until release.
        unsafe {
            frame.write(MumcordVideoFrame {
                token,
                width: picture.width,
                height: picture.height,
                y: base,
                u: base.add(luma),
                v: base.add(luma + chroma),
                stride_y: picture.width,
                stride_u: chroma_width as u32,
                stride_v: chroma_width as u32,
            });
        }
        true
    }))
    .unwrap_or(false)
}

/// Gives back the picture of `token`; its planes must not be read afterwards. A token that is
/// unknown, already released or 0 does nothing.
///
/// # Safety
///
/// `handle` is 0 or the value of `MumcordEngine::video_handle`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mumcord_video_frame_release(handle: u64, token: u64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the caller's contract.
        if let Some(frames) = unsafe { frames(handle) } {
            frames.release(token);
        }
    }));
}

#[cfg(test)]
mod tests {
    use std::sync::mpsc::{Receiver, channel};
    use std::time::Duration;

    use super::*;

    fn picture(width: u32, height: u32, value: u8) -> I420Frame {
        I420Frame::new(
            width,
            height,
            vec![value; I420Frame::buffer_len(width, height)],
        )
        .expect("frame")
    }

    struct Ready(Mutex<std::sync::mpsc::Sender<(u64, u32, u32)>>);

    impl VideoSink for Ready {
        fn on_frame_ready(&self, video_id: u64, width: u32, height: u32) {
            assert_eq!(std::thread::current().name(), Some("mumcord-bridge-video"));
            let _ = lock(&self.0).send((video_id, width, height));
        }
    }

    fn registry() -> (&'static VideoFrames, Receiver<(u64, u32, u32)>) {
        let frames = VideoFrames::start().expect("video thread");
        let (tx, rx) = channel();
        frames.set_sink(Some(Arc::new(Ready(Mutex::new(tx)))));
        (frames, rx)
    }

    fn acquire(frames: &'static VideoFrames, id: u64) -> Option<MumcordVideoFrame> {
        let mut out = std::mem::MaybeUninit::<MumcordVideoFrame>::uninit();
        // SAFETY: a live registry and a writable frame.
        let got = unsafe { mumcord_video_frame_acquire(frames.handle(), id, out.as_mut_ptr()) };
        // SAFETY: written when `got`.
        got.then(|| unsafe { out.assume_init() })
    }

    fn release(frames: &'static VideoFrames, token: u64) {
        // SAFETY: a live registry.
        unsafe { mumcord_video_frame_release(frames.handle(), token) }
    }

    /// The bytes of one plane of an acquired picture.
    fn plane(ptr: *const u8, len: usize) -> Vec<u8> {
        // SAFETY: the picture is held, so its planes are valid.
        unsafe { std::slice::from_raw_parts(ptr, len) }.to_vec()
    }

    #[test]
    fn ids_tell_kinds_and_people_apart() {
        assert_ne!(
            video_id(StreamKind::Screen, 7),
            video_id(StreamKind::Camera, 7)
        );
        assert_ne!(
            video_id(StreamKind::Screen, 7),
            video_id(StreamKind::Screen, 8)
        );
    }

    #[test]
    fn a_picture_reaches_the_app_with_its_planes() {
        let (frames, ready) = registry();
        let id = video_id(StreamKind::Screen, 3);
        frames.open(id);
        let mut data = vec![16_u8; 4 * 2];
        data.extend([128_u8; 2]);
        data.extend([200_u8; 2]);
        frames.put(id, &I420Frame::new(4, 2, data).expect("frame"));
        assert_eq!(
            ready.recv_timeout(Duration::from_secs(5)).expect("told"),
            (id, 4, 2)
        );
        let frame = acquire(frames, id).expect("a picture");
        assert_eq!((frame.width, frame.height), (4, 2));
        assert_eq!((frame.stride_y, frame.stride_u, frame.stride_v), (4, 2, 2));
        assert_eq!(plane(frame.y, 8), vec![16; 8]);
        assert_eq!(plane(frame.u, 2), vec![128; 2]);
        assert_eq!(plane(frame.v, 2), vec![200; 2]);
        release(frames, frame.token);
        assert!(acquire(frames, id).is_none(), "taken once");
    }

    #[test]
    fn an_untaken_picture_is_replaced_and_told_once() {
        let (frames, ready) = registry();
        let id = video_id(StreamKind::Camera, 4);
        frames.open(id);
        frames.put(id, &picture(2, 2, 1));
        ready.recv_timeout(Duration::from_secs(5)).expect("told");
        for value in 2..=10 {
            frames.put(id, &picture(2, 2, value));
        }
        assert!(
            ready.recv_timeout(Duration::from_millis(300)).is_err(),
            "no news while the app has not taken the waiting picture"
        );
        let frame = acquire(frames, id).expect("the newest");
        assert_eq!(plane(frame.y, 1), vec![10]);
        release(frames, frame.token);
        frames.put(id, &picture(2, 2, 11));
        ready
            .recv_timeout(Duration::from_secs(5))
            .expect("told again");
    }

    #[test]
    fn a_held_picture_outlives_its_stream_and_size_changes() {
        let (frames, _ready) = registry();
        let id = video_id(StreamKind::Screen, 5);
        frames.open(id);
        frames.put(id, &picture(4, 4, 42));
        let held = acquire(frames, id).expect("a picture");
        // Newer pictures of another size fill the other buffers, never the held one.
        for value in 0..10 {
            frames.put(id, &picture(16, 8, value));
            if let Some(other) = acquire(frames, id) {
                release(frames, other.token);
            }
        }
        frames.close(id);
        frames.shut_down();
        assert_eq!(plane(held.y, 16), vec![42; 16], "unchanged until release");
        release(frames, held.token);
        release(frames, held.token);
    }

    #[test]
    fn misuse_is_harmless() {
        let (frames, _ready) = registry();
        release(frames, 0);
        release(frames, 999);
        // SAFETY: handle 0 is allowed; a null frame is allowed.
        unsafe {
            mumcord_video_frame_release(0, 1);
            let mut out = std::mem::MaybeUninit::<MumcordVideoFrame>::uninit();
            assert!(!mumcord_video_frame_acquire(0, 1, out.as_mut_ptr()));
            assert!(!mumcord_video_frame_acquire(
                frames.handle(),
                1,
                std::ptr::null_mut()
            ));
        }
        assert!(acquire(frames, 77).is_none(), "no such stream");
        let id = video_id(StreamKind::Screen, 6);
        frames.put(id, &picture(2, 2, 1));
        assert!(acquire(frames, id).is_none(), "not watched, so not kept");
    }

    #[test]
    fn oversized_pictures_are_dropped() {
        let (frames, _ready) = registry();
        let id = video_id(StreamKind::Screen, 8);
        frames.open(id);
        frames.put(id, &picture(MAX_WIDTH + 2, 2, 1));
        assert!(acquire(frames, id).is_none());
    }

    #[test]
    fn the_app_holding_every_buffer_drops_new_pictures() {
        let (frames, _ready) = registry();
        let id = video_id(StreamKind::Screen, 9);
        frames.open(id);
        let mut held = Vec::new();
        for value in 0..BUFFERS as u8 {
            frames.put(id, &picture(2, 2, value));
            held.push(acquire(frames, id).expect("a picture"));
        }
        frames.put(id, &picture(2, 2, 99));
        assert!(acquire(frames, id).is_none(), "no free buffer");
        for frame in &held {
            release(frames, frame.token);
        }
        frames.put(id, &picture(2, 2, 100));
        let frame = acquire(frames, id).expect("a buffer is free again");
        assert_eq!(plane(frame.y, 1), vec![100]);
        release(frames, frame.token);
    }
}
