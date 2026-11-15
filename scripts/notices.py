# SPDX-License-Identifier: AGPL-3.0-only
"""Writes the third-party notices of an Android release.

The file has three parts: THIRD_PARTY_NOTICES of the repository (fonts, icons and the C
libraries built by scripts/build-codecs.sh, written by hand), the Rust crates of the engine
library (cargo-about), and the Android libraries of the APK (the SBOM of the CycloneDX Gradle
plugin, with the licence texts of their POM files). Each licence text is printed once, after
the list of the components it covers. Run by scripts/generate-notices.sh.

Licence policy: a Rust crate must have a licence accepted in about.toml, or cargo-about fails.
An Android library must have only accepted licences too, or this script fails, unless it is
listed in CHOICES below with the licence Mumcord Mobile uses it under.
"""

import argparse
import base64
import hashlib
import json
import re
import tomllib

# Android libraries whose POM does not state the licence Mumcord Mobile uses them under.
CHOICES = {
    # Dual-licensed under LGPL-2.1-or-later or Apache-2.0; used under Apache-2.0.
    "net.java.dev.jna:jna": ["Apache-2.0"],
    # The POM names no licence; the project (rustls-platform-verifier) is MIT or Apache-2.0.
    "org.rustls:rustls-platform-verifier": ["MIT", "Apache-2.0"],
}

RULE = "=" * 80
LINE = "-" * 80


def load(path):
    with open(path, encoding="utf-8") as file:
        return json.load(file)


def normalise(text):
    """Line endings and trailing space differ between copies of the same licence."""
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").strip().split("\n"))


def rust_groups(about):
    """(licence id, text, sorted component lines) per distinct licence text, without Mumcord's
    own crates, which are AGPL-3.0-only like the app."""
    groups = []
    for licence in about["licenses"]:
        if licence["id"] == "AGPL-3.0-only":
            continue
        users = sorted(
            {
                f"{use['crate']['name']} {use['crate']['version']}"
                + (f" ({use['crate']['repository']})" if use["crate"].get("repository") else "")
                for use in licence["used_by"]
            }
        )
        if users:
            groups.append((licence["id"], licence["name"], normalise(licence["text"]), users))
    return groups


def android_groups(bom, accepted, fallback_texts):
    texts = {}
    names = {}
    users = {}
    errors = []
    for component in bom["components"]:
        if "project_path=" in component["bom-ref"]:
            continue
        key = f"{component.get('group', '')}:{component['name']}"
        line = f"{key} {component['version']}"
        found = {}
        for entry in component.get("licenses", []):
            licence = entry.get("license", {})
            spdx = licence.get("id")
            if spdx is None:
                errors.append(f"{line}: licence without SPDX id: {licence.get('name') or entry}")
                continue
            text = licence.get("text", {})
            content = text.get("content")
            if content and text.get("encoding") == "base64":
                content = base64.b64decode(content).decode("utf-8")
            found[spdx] = content
        chosen = CHOICES.get(key, list(found))
        if not chosen:
            errors.append(f"{line}: no licence in its POM; add it to CHOICES after checking it")
        for spdx in chosen:
            if spdx not in accepted:
                errors.append(f"{line}: licence {spdx} is not accepted in about.toml")
                continue
            text = found.get(spdx) or fallback_texts.get(spdx)
            if not text:
                errors.append(f"{line}: no text for licence {spdx}")
                continue
            text = normalise(text)
            digest = hashlib.sha256(text.encode()).hexdigest()
            texts[digest] = text
            names[digest] = spdx
            users.setdefault(digest, set()).add(line)
    if errors:
        raise SystemExit("notices: " + "\n  ".join(["Android libraries fail the licence policy:", *errors]))
    return [(names[d], names[d], texts[d], sorted(users[d])) for d in sorted(texts, key=lambda d: (names[d], d))]


def section(title, intro, groups):
    out = [RULE, title, RULE, "", intro, ""]
    for spdx, name, text, users in groups:
        out += [LINE, f"{name} ({spdx})" if name != spdx else spdx, LINE, "", "Used by:"]
        out += [f"  {user}" for user in users]
        out += ["", text, ""]
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--static", required=True, help="THIRD_PARTY_NOTICES of the repository")
    parser.add_argument("--about-config", required=True, help="about.toml")
    parser.add_argument("--rust", required=True, help="cargo about generate --format json")
    parser.add_argument("--android", required=True, help="SBOM of the CycloneDX Gradle plugin")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    with open(args.about_config, "rb") as file:
        accepted = set(tomllib.load(file)["accepted"])
    about = load(args.rust)
    rust = rust_groups(about)
    fallback = {}
    for licence in about["licenses"]:
        fallback.setdefault(licence["id"], licence["text"])
    android = android_groups(load(args.android), accepted, fallback)
    with open(args.static, encoding="utf-8") as file:
        static = file.read().strip()

    out = [
        f"Mumcord Mobile {args.version} for Android - Third-party notices",
        RULE,
        "",
        "Mumcord Mobile itself, the Mumcord engine and the bridge are licensed under the",
        "GNU AGPL v3 only (see LICENSE). The app includes the third-party material below.",
        "This file is generated for each release by scripts/generate-notices.sh.",
        "",
        RULE,
        "FONTS, ICONS AND C LIBRARIES",
        RULE,
        "",
        static,
        "",
    ]
    out += section(
        "RUST CRATES (compiled into the engine library, libmumcord_mobile.so)",
        "libopus is compiled in by the opusic-sys crate and SQLite by libsqlite3-sys.",
        rust,
    )
    out += section(
        "ANDROID LIBRARIES",
        "The Java and Kotlin libraries packaged in the APK, with the licence texts of their releases.",
        android,
    )
    text = re.sub(r"[^\S\n]+\n", "\n", "\n".join(out).rstrip() + "\n")
    with open(args.out, "w", encoding="utf-8") as file:
        file.write(text)


if __name__ == "__main__":
    main()
