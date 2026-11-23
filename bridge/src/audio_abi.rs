// SPDX-License-Identifier: AGPL-3.0-only

//! The audio hot path: plain C functions the apps' real-time audio threads call directly
//! (`bridge/include/mumcord_audio.h`).
//!
//! UniFFI calls allocate (argument buffers, and a pull would return a new array per block), so
//! the microphone and speaker callbacks do not go through the generated bindings. They pass the
//! handles of [`MumcordEngine::audio_endpoints`](crate::MumcordEngine::audio_endpoints) to the
//! functions here, which never allocate, lock, log or block.
//!
//! # Lifetime
//!
//! A handle points to a small [`Endpoint`] that is never freed, so it stays valid for the rest
//! of the process. Releasing the engine object empties the endpoint; later calls find nothing
//! and are no-ops (a pull gives silence).

use std::cell::UnsafeCell;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, Ordering};

use mumcord_audio::io::{ExternalCapture, ExternalPlayback};

/// One direction's end of the engine's audio, shared between the engine object and the audio
/// thread that calls the C functions.
pub(crate) struct Endpoint<T> {
    /// Set while a call (or the closing engine) uses `slot`. The engine's endpoints take one
    /// caller at a time; a second caller finds it set and does nothing instead of waiting.
    busy: AtomicBool,
    /// `None` once the engine object is gone.
    slot: UnsafeCell<Option<T>>,
}

// SAFETY: `slot` is only touched while `busy` is held, which makes the access exclusive.
unsafe impl<T: Send> Sync for Endpoint<T> {}

/// Releases `busy` also when the call it guards panics.
struct Busy<'a>(&'a AtomicBool);

impl Drop for Busy<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

impl<T> Endpoint<T> {
    /// Deliberately leaked (a few bytes per engine): a handle the app still holds must never
    /// dangle, whatever the order in which the app tears its audio and the engine down.
    pub(crate) fn leak(value: T) -> &'static Self {
        Box::leak(Box::new(Self {
            busy: AtomicBool::new(false),
            slot: UnsafeCell::new(Some(value)),
        }))
    }

    pub(crate) fn handle(&'static self) -> u64 {
        std::ptr::from_ref(self) as usize as u64
    }

    /// Runs `call` on the endpoint. `None` when the engine is gone or another call is running.
    fn with<R>(&self, call: impl FnOnce(&mut T) -> R) -> Option<R> {
        if self.busy.swap(true, Ordering::Acquire) {
            return None;
        }
        let _busy = Busy(&self.busy);
        // SAFETY: `busy` is held, so nothing else reads or writes the slot.
        unsafe { &mut *self.slot.get() }.as_mut().map(call)
    }

    /// Empties the endpoint when the engine object goes away. Waits for a running audio
    /// callback to finish, which takes well under a millisecond; never called from one.
    pub(crate) fn close(&self) {
        while self.busy.swap(true, Ordering::Acquire) {
            std::thread::yield_now();
        }
        let closed = {
            let _busy = Busy(&self.busy);
            // SAFETY: `busy` is held, so nothing else reads or writes the slot.
            unsafe { &mut *self.slot.get() }.take()
        };
        // Freed after `busy` is released, so an audio callback never meets a long hold.
        drop(closed);
    }
}

/// The endpoint behind a handle of `audio_endpoints`; `None` for 0.
///
/// # Safety
///
/// `handle` is 0 or a value [`Endpoint::handle`] returned for the same `T`.
unsafe fn endpoint<T>(handle: u64) -> Option<&'static Endpoint<T>> {
    // SAFETY: per the contract the address is that of a leaked, never freed endpoint.
    unsafe { (handle as usize as *const Endpoint<T>).as_ref() }
}

/// # Safety
///
/// As [`endpoint`]; `samples` is null or valid for reading `sample_count` values.
unsafe fn push<S>(
    handle: u64,
    samples: *const S,
    sample_count: usize,
    call: impl FnOnce(&mut ExternalCapture, &[S]),
) {
    if samples.is_null() {
        return;
    }
    // SAFETY: the caller's contract.
    let (endpoint, samples) = unsafe {
        (
            endpoint::<ExternalCapture>(handle),
            std::slice::from_raw_parts(samples, sample_count),
        )
    };
    if let Some(endpoint) = endpoint {
        // A panic must not cross into C; the block is dropped instead.
        let _ = catch_unwind(AssertUnwindSafe(|| {
            endpoint.with(|capture| call(capture, samples))
        }));
    }
}

/// # Safety
///
/// As [`endpoint`]; `samples` is null or valid for writing `sample_count` values.
unsafe fn pull<S: Copy>(
    handle: u64,
    samples: *mut S,
    sample_count: usize,
    silence: S,
    call: impl FnOnce(&mut ExternalPlayback, &mut [S]),
) {
    if samples.is_null() {
        return;
    }
    // SAFETY: the caller's contract.
    let (endpoint, samples) = unsafe {
        (
            endpoint::<ExternalPlayback>(handle),
            std::slice::from_raw_parts_mut(samples, sample_count),
        )
    };
    let filled = endpoint.is_some_and(|endpoint| {
        // A panic must not cross into C; the block is played as silence instead.
        catch_unwind(AssertUnwindSafe(|| {
            endpoint.with(|playback| call(playback, samples)).is_some()
        }))
        .unwrap_or(false)
    });
    if !filled {
        samples.fill(silence);
    }
}

/// Hands captured interleaved 16-bit samples to the engine.
///
/// # Safety
///
/// `handle` is 0 or the `capture` handle of `audio_endpoints`; `samples` is null or valid for
/// reading `sample_count` values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mumcord_audio_capture_push_i16(
    handle: u64,
    samples: *const i16,
    sample_count: usize,
) {
    // SAFETY: the caller's contract.
    unsafe { push(handle, samples, sample_count, ExternalCapture::push_i16) }
}

/// Hands captured interleaved 32-bit float samples to the engine.
///
/// # Safety
///
/// `handle` is 0 or the `capture` handle of `audio_endpoints`; `samples` is null or valid for
/// reading `sample_count` values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mumcord_audio_capture_push_f32(
    handle: u64,
    samples: *const f32,
    sample_count: usize,
) {
    // SAFETY: the caller's contract.
    unsafe { push(handle, samples, sample_count, ExternalCapture::push_f32) }
}

/// Fills `samples` with the engine's playback as interleaved 16-bit samples.
///
/// # Safety
///
/// `handle` is 0 or the `playback` handle of `audio_endpoints`; `samples` is null or valid for
/// writing `sample_count` values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mumcord_audio_playback_pull_i16(
    handle: u64,
    samples: *mut i16,
    sample_count: usize,
) {
    // SAFETY: the caller's contract.
    unsafe { pull(handle, samples, sample_count, 0, ExternalPlayback::pull_i16) }
}

/// Fills `samples` with the engine's playback as interleaved 32-bit float samples.
///
/// # Safety
///
/// `handle` is 0 or the `playback` handle of `audio_endpoints`; `samples` is null or valid for
/// writing `sample_count` values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mumcord_audio_playback_pull_f32(
    handle: u64,
    samples: *mut f32,
    sample_count: usize,
) {
    // SAFETY: the caller's contract.
    unsafe {
        pull(
            handle,
            samples,
            sample_count,
            0.0,
            ExternalPlayback::pull_f32,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The C types of the header and the Rust types they stand for.
    const C_TYPES: [(&str, &str); 6] = [
        ("u64", "uint64_t"),
        ("usize", "size_t"),
        ("*const i16", "const int16_t *"),
        ("*const f32", "const float *"),
        ("*mut i16", "int16_t *"),
        ("*mut f32", "float *"),
    ];

    fn c_type(rust: &str) -> &'static str {
        C_TYPES
            .iter()
            .find(|(name, _)| *name == rust)
            .map_or_else(|| panic!("no C type for {rust}"), |(_, c)| c)
    }

    /// The C prototype for a function with these Rust parameter types.
    fn prototype(name: &str, samples: &str) -> String {
        format!(
            "void {name}({} handle, {}samples, {} sample_count);",
            c_type("u64"),
            c_type(samples),
            c_type("usize")
        )
    }

    #[test]
    fn the_header_declares_the_exported_functions_with_their_signatures() {
        // Each binding fails to compile when a Rust signature changes; the type named next to
        // it is what the header must then say.
        type Push<S> = unsafe extern "C" fn(u64, *const S, usize);
        type Pull<S> = unsafe extern "C" fn(u64, *mut S, usize);
        let _: Push<i16> = mumcord_audio_capture_push_i16;
        let _: Push<f32> = mumcord_audio_capture_push_f32;
        let _: Pull<i16> = mumcord_audio_playback_pull_i16;
        let _: Pull<f32> = mumcord_audio_playback_pull_f32;
        let expected = [
            prototype("mumcord_audio_capture_push_i16", "*const i16"),
            prototype("mumcord_audio_capture_push_f32", "*const f32"),
            prototype("mumcord_audio_playback_pull_i16", "*mut i16"),
            prototype("mumcord_audio_playback_pull_f32", "*mut f32"),
        ];

        let header = include_str!("../include/mumcord_audio.h");
        let code: String = header
            .lines()
            .filter(|line| !line.trim_start().starts_with(['/', '*']))
            .collect::<Vec<_>>()
            .join(" ");
        let code = code.split_whitespace().collect::<Vec<_>>().join(" ");
        for prototype in &expected {
            assert!(
                code.contains(prototype),
                "missing in the header: {prototype}"
            );
        }
        assert_eq!(
            code.matches("mumcord_audio_").count(),
            expected.len(),
            "the header declares a function the library does not export"
        );
    }

    #[test]
    fn calls_without_an_endpoint_do_nothing_and_play_silence() {
        let mut block = [7_i16; 8];
        // SAFETY: handle 0 and null pointers are allowed.
        unsafe {
            mumcord_audio_capture_push_i16(0, block.as_ptr(), block.len());
            mumcord_audio_capture_push_f32(0, std::ptr::null(), 4);
            mumcord_audio_playback_pull_i16(0, std::ptr::null_mut(), 4);
            mumcord_audio_playback_pull_i16(0, block.as_mut_ptr(), block.len());
        }
        assert_eq!(block, [0; 8]);
    }

    #[test]
    fn a_closed_endpoint_stays_callable() {
        let endpoint = Endpoint::leak(5_u32);
        assert_eq!(endpoint.with(|value| *value), Some(5));
        endpoint.close();
        assert_eq!(endpoint.with(|value| *value), None);
        endpoint.close();
    }

    #[test]
    fn a_second_caller_does_nothing_instead_of_waiting() {
        let endpoint = Endpoint::leak(5_u32);
        let nested = endpoint.with(|_| endpoint.with(|value| *value));
        assert_eq!(nested, Some(None));
        assert_eq!(endpoint.with(|value| *value), Some(5));
    }

    #[test]
    fn a_panicking_call_frees_the_endpoint_again() {
        let endpoint = Endpoint::leak(5_u32);
        let panicked = catch_unwind(AssertUnwindSafe(|| {
            endpoint.with(|_| panic!("in the callback"))
        }));
        assert!(panicked.is_err());
        assert_eq!(endpoint.with(|value| *value), Some(5));
    }
}
