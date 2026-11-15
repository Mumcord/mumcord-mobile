// SPDX-License-Identifier: AGPL-3.0-only

//! A global allocator that counts the heap calls of the current thread, to prove that the audio
//! functions never allocate or free (the pattern of the engine's `external_no_alloc` test).

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

pub struct Counting;

thread_local! {
    static HEAP_CALLS: Cell<usize> = const { Cell::new(0) };
}

fn count() {
    HEAP_CALLS.with(|count| count.set(count.get() + 1));
}

// SAFETY: forwards every call unchanged to the system allocator; it only counts the calls.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        count();
        // SAFETY: same contract as the caller's.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        count();
        // SAFETY: same contract as the caller's.
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        count();
        // SAFETY: same contract as the caller's.
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

/// Heap calls of this thread so far.
pub fn heap_calls() -> usize {
    HEAP_CALLS.with(Cell::get)
}
