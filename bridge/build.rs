// SPDX-License-Identifier: AGPL-3.0-only

//! Keeps the engine pin in one piece: fails the build when the engine reference of the root
//! `Cargo.toml` differs from `engine.lock.toml`, and hands the pinned reference and the engine's
//! version to the crate (`engine_version`).

use std::path::Path;

fn main() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let read = |name: &str| {
        let path = root.join(name);
        println!("cargo:rerun-if-changed={}", path.display());
        std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("reading {} failed: {e}", path.display()))
    };

    let pin = read("engine.lock.toml");
    let reference = ["rev", "tag"]
        .iter()
        .find_map(|key| value_of(&pin, key).map(|value| (*key, value)))
        .expect("engine.lock.toml names neither `rev` nor `tag`");
    let expected = format!("{} = \"{}\"", reference.0, reference.1);
    let manifest = read("Cargo.toml");
    let engine_dependencies: Vec<&str> = manifest
        .lines()
        .filter(|line| line.starts_with("mumcord-") && line.contains("git ="))
        .collect();
    assert!(
        !engine_dependencies.is_empty(),
        "the root Cargo.toml has no engine git dependency"
    );
    for line in engine_dependencies {
        assert!(
            line.contains(&expected),
            "engine pin mismatch: engine.lock.toml says {expected}, the root Cargo.toml says: {line}"
        );
    }

    let lock = read("Cargo.lock");
    let version = lock
        .split("[[package]]")
        .find(|package| value_of(package, "name") == Some("mumcord-engine"))
        .and_then(|package| value_of(package, "version"))
        .expect("Cargo.lock has no mumcord-engine package");

    println!("cargo:rustc-env=MUMCORD_ENGINE_VERSION={version}");
    println!("cargo:rustc-env=MUMCORD_ENGINE_REF={}", reference.1);
}

/// The quoted value of the first uncommented `key = "value"` line.
fn value_of<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    text.lines().find_map(|line| {
        let rest = line.strip_prefix(key)?.trim_start().strip_prefix('=')?;
        rest.trim().strip_prefix('"')?.strip_suffix('"')
    })
}
