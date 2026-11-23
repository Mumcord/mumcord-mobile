# SPDX-License-Identifier: AGPL-3.0-only
"""Merges the SBOMs of an Android release into one CycloneDX 1.6 document.

Inputs: the Android libraries of the APK (CycloneDX Gradle plugin, task :app:cyclonedxDirectBom),
the Rust crates of the engine library (cargo-cyclonedx for the bridge and the phone target), and
the C libraries built into the engine library that no package manager describes. The app is the
document's main component; the bridge hangs below the Gradle module :engine, which packages it,
and the C libraries below the bridge. Run by scripts/generate-sbom.sh.
"""

import argparse
import json
import uuid
from datetime import datetime, timezone

OWN_LICENCE = [{"license": {"id": "AGPL-3.0-only"}}]


def load(path):
    with open(path, encoding="utf-8") as file:
        return json.load(file)


def rename_refs(bom, renames):
    """Replaces bom-refs in components and dependencies, which refer to each other by them."""

    def walk(components):
        for component in components:
            component["bom-ref"] = renames.get(component["bom-ref"], component["bom-ref"])
            walk(component.get("components", []))

    walk(bom.get("components", []))
    if "component" in bom.get("metadata", {}):
        walk([bom["metadata"]["component"]])
    for dependency in bom.get("dependencies", []):
        dependency["ref"] = renames.get(dependency["ref"], dependency["ref"])
        dependency["dependsOn"] = [renames.get(ref, ref) for ref in dependency.get("dependsOn", [])]


def android_part(bom, version):
    """The Gradle SBOM without the licence texts (they belong in the notices) and with the
    app's own modules carrying the app's version and licence instead of 'unspecified'."""
    renames = {}
    for component in bom["components"]:
        for licence in component.get("licenses", []):
            licence.get("license", {}).pop("text", None)
        if "project_path=" in component["bom-ref"]:
            component["version"] = version
            component["licenses"] = OWN_LICENCE
            renames[component["bom-ref"]] = component["bom-ref"].replace("@unspecified", f"@{version}")
            component.pop("purl", None)
    rename_refs(bom, renames)
    return bom


def rust_part(bom, repo_root):
    """The cargo SBOM with the bridge (its main component) as an ordinary component. Its refs
    contain the absolute path of the checkout, which says nothing about the app; they are made
    relative to the repository."""
    text = json.dumps(bom).replace(f"path+file://{repo_root}/", "path+file:mumcord-mobile/")
    text = text.replace(f"file://{repo_root}/", "file:mumcord-mobile/")
    bom = json.loads(text)
    bridge = bom["metadata"]["component"]
    bridge["type"] = "library"
    bridge["licenses"] = OWN_LICENCE
    return bom, bridge


def native_components(specs):
    components = []
    for spec in specs:
        name, version, licence, purl, description = spec.split("|")
        components.append(
            {
                "type": "library",
                "bom-ref": f"native:{name}@{version}",
                "name": name,
                "version": version,
                "description": description,
                "licenses": [{"license": {"id": licence}}],
                "purl": purl,
            }
        )
    return components


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--android", required=True, help="SBOM of the Gradle plugin")
    parser.add_argument("--rust", required=True, help="SBOM of cargo-cyclonedx for the bridge")
    parser.add_argument("--repo-root", required=True)
    parser.add_argument(
        "--native",
        action="append",
        default=[],
        help="name|version|SPDX licence|purl|description of a C library in the engine library",
    )
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    android = android_part(load(args.android), args.version)
    rust, bridge = rust_part(load(args.rust), args.repo_root)
    native = native_components(args.native)

    app = android["metadata"]["component"]
    app["version"] = args.version
    app["licenses"] = OWN_LICENCE
    app["description"] = "Mumcord for Android"

    components = {}
    for component in android["components"] + [bridge] + rust["components"] + native:
        components.setdefault(component["bom-ref"], component)

    dependencies = {}
    for dependency in android["dependencies"] + rust["dependencies"]:
        entry = dependencies.setdefault(dependency["ref"], {"ref": dependency["ref"], "dependsOn": []})
        for ref in dependency.get("dependsOn", []):
            if ref not in entry["dependsOn"]:
                entry["dependsOn"].append(ref)
    engine_module = next(ref for ref in dependencies if "project_path=%3Aengine" in ref)
    dependencies[engine_module]["dependsOn"].append(bridge["bom-ref"])
    bridge_entry = dependencies.setdefault(bridge["bom-ref"], {"ref": bridge["bom-ref"], "dependsOn": []})
    bridge_entry["dependsOn"].extend(component["bom-ref"] for component in native)
    for component in native:
        dependencies.setdefault(component["bom-ref"], {"ref": component["bom-ref"], "dependsOn": []})

    missing = {
        ref
        for entry in dependencies.values()
        for ref in [entry["ref"], *entry["dependsOn"]]
        if ref not in components and ref != app["bom-ref"]
    }
    if missing:
        raise SystemExit(f"sbom-merge: dependencies name unknown components: {sorted(missing)[:5]}")

    document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": f"urn:uuid:{uuid.uuid4()}",
        "version": 1,
        "metadata": {
            "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "tools": {
                "components": [
                    {"type": "application", "name": "cyclonedx-gradle-plugin"},
                    {"type": "application", "name": "cargo-cyclonedx"},
                ]
            },
            "component": app,
        },
        "components": list(components.values()),
        "dependencies": list(dependencies.values()),
    }
    with open(args.out, "w", encoding="utf-8") as file:
        json.dump(document, file, indent=2, ensure_ascii=False)
        file.write("\n")


if __name__ == "__main__":
    main()
