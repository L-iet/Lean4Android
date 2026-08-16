#!/usr/bin/env python3
"""Fetch the pinned Mathlib source and its exact Lake dependency closure."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "toolchain" / "scripts"))
from script_progress import Progress, configure_logging


MATHLIB_URL = "https://github.com/leanprover-community/mathlib4.git"


def read_pins(path: Path) -> dict[str, object]:
    pins: dict[str, object] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, raw = line.partition(" = ")
        if not separator:
            continue
        pins[key] = raw[1:-1] if raw.startswith('"') and raw.endswith('"') else int(raw)
    return pins


def run(*args: str, cwd: Path | None = None) -> str:
    print(f"Running: {' '.join(args)}")
    result = subprocess.run(args, cwd=cwd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.stdout:
        print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    return result.stdout.strip()


def fetch_checkout(url: str, revision: str, expected: str, destination: Path) -> None:
    if not (destination / ".git").is_dir():
        destination.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--filter=blob:none", "--no-checkout", url, str(destination))
    run("git", "fetch", "--depth", "1", "origin", revision, cwd=destination)
    resolved = run("git", "rev-parse", "FETCH_HEAD", cwd=destination)
    if resolved != expected:
        raise SystemExit(f"{url} revision {revision} resolved to {resolved}, expected {expected}")
    run("git", "checkout", "--detach", "--force", resolved, cwd=destination)
    run("git", "reset", "--hard", resolved, cwd=destination)
    run("git", "clean", "-ffd", cwd=destination)
    if run("git", "status", "--short", cwd=destination):
        raise SystemExit(f"checkout is not clean: {destination}")


def main() -> None:
    configure_logging()
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-dir", type=Path, default=Path("toolchain/work/mathlib-producer"))
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    pins = read_pins(repository / "mathlib/versions.toml")
    source = args.work_dir.resolve() / "mathlib"
    fetch_checkout(MATHLIB_URL, pins["mathlib_tag"], pins["mathlib_commit"], source)

    toolchain = (source / "lean-toolchain").read_text(encoding="utf-8").strip()
    if toolchain != pins["lean_toolchain"]:
        raise SystemExit(f"Mathlib toolchain is {toolchain}, expected {pins['lean_toolchain']}")

    manifest = json.loads((source / "lake-manifest.json").read_text(encoding="utf-8"))
    packages = source / ".lake/packages"
    dependencies = manifest["packages"]
    progress = Progress("pinned Mathlib dependency fetch", len(dependencies) + 1)
    progress.update(1, 0, force=True)
    for index, package in enumerate(dependencies, start=2):
        if package["type"] != "git" or not package.get("url") or not package.get("rev"):
            raise SystemExit(f"unsupported dependency entry: {package}")
        fetch_checkout(package["url"], package["rev"], package["rev"], packages / package["name"])
        progress.update(index, 0, force=True)
    progress.complete(len(dependencies) + 1, 0)
    print(f"Pinned Mathlib source and {len(manifest['packages'])} dependencies are ready under {args.work_dir}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(f"command failed with exit {error.returncode}: {' '.join(error.cmd)}", file=sys.stderr)
        raise SystemExit(error.returncode) from error
