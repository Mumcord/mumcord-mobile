// SPDX-License-Identifier: AGPL-3.0-only

//! Generates the Kotlin and Swift bindings from the built bridge library:
//! `cargo run -p mumcord-mobile-bridge --features bindgen --bin uniffi-bindgen -- generate
//! --library <path to libmumcord_mobile> --language kotlin --out-dir <dir>`.

fn main() {
    uniffi::uniffi_bindgen_main()
}
