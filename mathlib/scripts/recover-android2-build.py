#!/usr/bin/env python3
"""Conservatively repair crash-damaged generated Mathlib module families."""

from __future__ import annotations

import argparse
import os
import stat
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "toolchain" / "scripts"))
from script_progress import Progress, configure_logging


OLEAN_VERSION = b"4.32.1"
LEAN_COMMIT = b"f054605aea4b840552cca2e725580bffd1e1b704"
LIB_SUFFIXES = (
    ".olean.private.hash",
    ".olean.server.hash",
    ".olean.private",
    ".olean.server",
    ".olean.hash",
    ".ilean.hash",
    ".ir.hash",
    ".olean",
    ".ilean",
    ".trace",
    ".ir",
)
IR_SUFFIXES = (".setup.json", ".c.hash", ".o.export", ".o", ".c")
TEMP_SUFFIXES = (".tmp", ".part", ".partial")


def all_zero(path: Path) -> bool:
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            if any(chunk):
                return False
    return True


def module_for(path: Path, build_root: Path) -> str | None:
    relative = path.relative_to(build_root)
    text = relative.as_posix()
    if text.startswith("lib/lean/"):
        module_file = text.removeprefix("lib/lean/")
        for suffix in LIB_SUFFIXES:
            if module_file.endswith(suffix):
                return module_file[: -len(suffix)]
    if text.startswith("ir/"):
        module_file = text.removeprefix("ir/")
        for suffix in IR_SUFFIXES:
            if module_file.endswith(suffix):
                return module_file[: -len(suffix)]
    return None


def invalid_reason(path: Path) -> str | None:
    size = path.stat().st_size
    name = path.name
    if size == 0:
        return "empty"
    if name.endswith(".hash"):
        if size != 16:
            return f"hash-size-{size}"
        if all_zero(path):
            return "all-zero-hash"
    if name.endswith(".olean"):
        with path.open("rb") as stream:
            header = stream.read(96)
        if not header.startswith(b"olean"):
            return "bad-olean-magic"
        if OLEAN_VERSION not in header or LEAN_COMMIT not in header:
            return "wrong-olean-identity"
    if name.endswith((".olean", ".ilean", ".olean.private", ".olean.server", ".ir", ".c", ".o", ".o.export")):
        if all_zero(path):
            return "all-zero"
    return None


def remove_module(build_root: Path, module: str) -> list[Path]:
    removed: list[Path] = []
    for suffix in LIB_SUFFIXES:
        candidate = build_root / "lib/lean" / f"{module}{suffix}"
        if candidate.is_file() and not candidate.is_symlink():
            candidate.unlink()
            removed.append(candidate)
    for suffix in IR_SUFFIXES:
        candidate = build_root / "ir" / f"{module}{suffix}"
        if candidate.is_file() and not candidate.is_symlink():
            candidate.unlink()
            removed.append(candidate)
    return removed


def main() -> None:
    configure_logging()
    parser = argparse.ArgumentParser()
    parser.add_argument("--mathlib-root", type=Path, required=True)
    args = parser.parse_args()
    root = args.mathlib_root.resolve()
    if not (root / ".git").is_dir() or not (root / "lake-manifest.json").is_file():
        raise SystemExit(f"refusing unexpected Mathlib root: {root}")

    build_roots: set[Path] = set()
    for lake in (root, *(root / ".lake/packages").iterdir()):
        build = lake / ".lake/build"
        if build.is_dir() and not build.is_symlink():
            build_roots.add(build.resolve())

    files: list[tuple[Path, Path]] = []
    for build in sorted(build_roots):
        for directory, names, filenames in os.walk(build, followlinks=False):
            names[:] = [name for name in names if not (Path(directory) / name).is_symlink()]
            for filename in filenames:
                path = Path(directory) / filename
                mode = path.lstat().st_mode
                if stat.S_ISREG(mode):
                    files.append((build, path))

    progress = Progress("Mathlib crash-recovery scan", len(files))
    temporary: list[Path] = []
    damaged: dict[tuple[Path, str], list[str]] = {}
    for index, (build, path) in enumerate(files, start=1):
        if path.name.endswith(TEMP_SUFFIXES):
            temporary.append(path)
        else:
            module = module_for(path, build)
            if module is not None:
                reason = invalid_reason(path)
                if reason:
                    damaged.setdefault((build, module), []).append(f"{path.name}:{reason}")
        progress.update(index, 0)
    progress.complete(len(files), 0)

    for path in temporary:
        path.unlink()
        print(f"Removed orphaned temporary file: {path}")
    for (build, module), reasons in sorted(damaged.items(), key=lambda item: (str(item[0][0]), item[0][1])):
        removed = remove_module(build, module)
        print(f"Invalidated damaged module {module}: {', '.join(reasons)}; removed {len(removed)} generated facets")

    print(
        f"Recovery audit complete: {len(files)} generated files scanned, "
        f"{len(temporary)} orphaned temporaries removed, {len(damaged)} damaged module families invalidated"
    )


if __name__ == "__main__":
    main()
