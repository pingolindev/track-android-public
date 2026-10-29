#!/usr/bin/env python3
# Copyright (c) 2026 Pingolin LLC
#
# This file is part of Pingolin Track.
#
# Pingolin Track is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# Pingolin Track is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with Pingolin Track. If not, see <https://www.gnu.org/licenses/>.

"""Inventory the third-party Rust crates linked into libpingolin_mobile.so.

The Gradle licence plugin reads POM metadata, so it sees only the JVM/Android
dependencies. It is blind to the Rust crates statically linked into the prebuilt
native library this app ships at:

    library/src/main/jniLibs/<abi>/libpingolin_mobile.so

Those crates carry real licence obligations, so notices generated from Gradle
alone are materially incomplete. This script closes that gap. It writes

    app/licenses/native-rust-licenses.json

which :app:generateThirdPartyLicenses merges into THIRD_PARTY_LICENSES.md and
the in-app open_source_licenses.html asset as a "Native (Rust) components"
section. Both the JSON and the generated notices are committed.

USAGE

    tools/generate-native-licenses.py [--mobile-sdk-dir PATH] [--output PATH]

The mobile-sdk checkout is located, in order, from:

    1. --mobile-sdk-dir
    2. $MOBILE_SDK_DIR
    3. a sibling checkout next to this repository (../mobile-sdk), which is the
       layout mobile-sdk/build-android.sh already requires

and the script fails with a message naming all three if none of them resolve.
No path to anyone's particular checkout is baked in.

Requires cargo on PATH. It only reads metadata — nothing is compiled, and the
Android NDK is not needed.

WHAT COUNTS AS LINKED

The .so is built from crates/mobile-sdk, so the inventory is the *normal*
dependency closure of that crate: dev-dependencies are test-only, and
build-dependencies and proc-macro crates run at compile time and leave no code
in the binary. Traversal therefore stops at a proc-macro crate rather than
descending into it (syn, quote and friends are its dependencies, not ours).
First-party crates are excluded — they are Pingolin's own code, covered by
LICENSE.

Dependency sets differ per target, so all three shipped ABIs are resolved and
unioned; taking any single one under-reports the others.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

# The ABIs shipped in library/src/main/jniLibs/, as Rust target triples.
TARGETS = {
    "arm64-v8a": "aarch64-linux-android",
    "armeabi-v7a": "armv7-linux-androideabi",
    "x86_64": "x86_64-linux-android",
}

# Pingolin's own crates. Covered by LICENSE, not by a third-party notice.
FIRST_PARTY = {"mobile-sdk", "track-proto", "pingolin-crypto"}

# The crate that becomes the shared library.
SDK_CRATE = Path("crates") / "mobile-sdk"

REPO_ROOT = Path(__file__).resolve().parent.parent


def die(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def resolve_mobile_sdk(explicit: str | None) -> Path:
    """Locate the mobile-sdk checkout, or explain how to point at one."""
    candidates = [
        (explicit, "--mobile-sdk-dir"),
        (os.environ.get("MOBILE_SDK_DIR"), "$MOBILE_SDK_DIR"),
        (str(REPO_ROOT.parent / "mobile-sdk"), "sibling checkout ../mobile-sdk"),
    ]
    for value, origin in candidates:
        if not value:
            continue
        path = Path(value).expanduser().resolve()
        if (path / SDK_CRATE / "Cargo.toml").is_file():
            print(f"==> mobile-sdk: {path}  (from {origin})")
            return path
        # An explicitly requested location that is wrong is an error, not a
        # reason to silently fall through to the next candidate.
        if origin != "sibling checkout ../mobile-sdk":
            die(
                f"{origin} is {path}, which has no {SDK_CRATE / 'Cargo.toml'}.\n"
                "       That does not look like a mobile-sdk checkout."
            )
    die(
        "could not find the mobile-sdk checkout. Point at it with one of:\n"
        "         tools/generate-native-licenses.py --mobile-sdk-dir /path/to/mobile-sdk\n"
        "         MOBILE_SDK_DIR=/path/to/mobile-sdk tools/generate-native-licenses.py\n"
        f"       or check it out beside this repository as {REPO_ROOT.parent / 'mobile-sdk'}."
    )
    raise AssertionError("unreachable")


def cargo_metadata(manifest: Path, target: str) -> dict:
    """Resolve the dependency graph for one target triple."""
    try:
        proc = subprocess.run(
            [
                "cargo", "metadata",
                "--format-version", "1",
                "--filter-platform", target,
                "--manifest-path", str(manifest),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        die("cargo not found on PATH. Install Rust from https://rustup.rs")
    if proc.returncode != 0:
        die(
            f"cargo metadata failed for {target} (exit {proc.returncode}):\n"
            + (proc.stderr.strip() or "<no stderr>")
        )
    return json.loads(proc.stdout)


def linked_crates(meta: dict) -> dict[str, dict]:
    """Third-party crates whose code ends up in the shared library.

    Walks normal dependency edges only, and does not descend through
    proc-macro crates: those run in the compiler, so neither they nor their
    dependencies contribute code to the binary.
    """
    packages = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    root = meta["resolve"]["root"]
    if root is None:
        die("cargo metadata returned no root package; is the manifest path right?")

    found: dict[str, dict] = {}
    seen: set[str] = set()
    stack = [root]
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        for dep in nodes[current]["deps"]:
            # dep_kinds: kind null = normal, "dev" = test-only, "build" = build
            # script. Only normal edges put code in the library.
            if not any(k["kind"] is None for k in dep["dep_kinds"]):
                continue
            package = packages[dep["pkg"]]
            if any("proc-macro" in t["kind"] for t in package["targets"]):
                continue
            if package["name"] not in FIRST_PARTY:
                found[dep["pkg"]] = package
            stack.append(dep["pkg"])
    return found


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Inventory the Rust crates linked into libpingolin_mobile.so.",
    )
    parser.add_argument(
        "--mobile-sdk-dir",
        help="path to the mobile-sdk checkout (default: $MOBILE_SDK_DIR, then ../mobile-sdk)",
    )
    parser.add_argument(
        "--output",
        default=str(REPO_ROOT / "app" / "licenses" / "native-rust-licenses.json"),
        help="where to write the inventory",
    )
    args = parser.parse_args()

    if not shutil.which("cargo"):
        die("cargo not found on PATH. Install Rust from https://rustup.rs")

    sdk_dir = resolve_mobile_sdk(args.mobile_sdk_dir)
    manifest = sdk_dir / SDK_CRATE / "Cargo.toml"

    # abi -> crate key, so the notice can say which ABIs each crate is in.
    per_abi: dict[str, set[str]] = {}
    collected: dict[str, dict] = {}
    for abi, triple in TARGETS.items():
        print(f"==> resolving {abi} ({triple})")
        crates = linked_crates(cargo_metadata(manifest, triple))
        per_abi[abi] = set()
        for package in crates.values():
            key = f"{package['name']} {package['version']}"
            per_abi[abi].add(key)
            collected.setdefault(key, package)
        print(f"    {len(crates)} third-party crates")

    problems = []
    components = []
    for key in sorted(collected, key=str.lower):
        package = collected[key]
        licence = (package.get("license") or "").strip()
        if not licence:
            # license-file is the fallback a handful of crates use instead of
            # an SPDX expression.
            licence_file = package.get("license_file")
            if licence_file:
                licence = f"see {licence_file} in the crate source"
            else:
                problems.append(f"{key} declares no licence in its Cargo.toml")
                continue
        components.append({
            "name": package["name"],
            "version": package["version"],
            "license": licence,
            "repository": package.get("repository") or "",
            "description": (package.get("description") or "").strip(),
            "abis": sorted(abi for abi, keys in per_abi.items() if key in keys),
        })

    # Loud, not silent — the same rule the Gradle side follows. A crate with no
    # licence metadata must be resolved upstream rather than quietly dropped
    # from the notices.
    if problems:
        die(
            "crates with unusable licence metadata:\n"
            + "".join(f"         - {p}\n" for p in problems)
            + "       Fix the crate's Cargo.toml upstream, or record the licence by hand."
        )

    payload = {
        "_comment": [
            "GENERATED — do not edit by hand.",
            "Regenerate with tools/generate-native-licenses.py, then run",
            "./gradlew :app:generateThirdPartyLicenses and commit both results.",
            "",
            "Third-party Rust crates statically linked into the prebuilt",
            "library/src/main/jniLibs/<abi>/libpingolin_mobile.so. The Gradle",
            "licence plugin reads POM metadata and cannot see any of these.",
        ],
        "library": "libpingolin_mobile.so",
        "source_crate": str(SDK_CRATE).replace(os.sep, "/"),
        "abis": {abi: triple for abi, triple in TARGETS.items()},
        "inclusion_rule": (
            "Normal dependency closure of crates/mobile-sdk, unioned across the "
            "ABIs above. Dev-dependencies, build-dependencies and proc-macro "
            "crates are excluded: they run at build time and leave no code in "
            "the shared library. Pingolin's own crates are excluded; they are "
            "covered by LICENSE."
        ),
        "components": components,
    }

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n")
    print(f"==> wrote {out} ({len(components)} crates)")
    print("    now run: ./gradlew :app:generateThirdPartyLicenses")


if __name__ == "__main__":
    main()
