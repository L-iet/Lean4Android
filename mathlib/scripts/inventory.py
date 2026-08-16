#!/usr/bin/env python3
"""Create an unfiltered file/facet/license inventory for an M5 producer tree."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "toolchain" / "scripts"))
from script_progress import Progress, configure_logging


FACET_SUFFIXES = (
    ".olean.private",
    ".olean.server",
    ".olean",
    ".ilean",
    ".ir",
    ".c",
    ".o",
    ".a",
    ".lean",
)
LICENSE_NAMES = {"license", "license.md", "license.txt", "copying", "notice", "notice.txt"}


def read_pins(path: Path) -> dict[str, object]:
    pins: dict[str, object] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, raw = line.partition(" = ")
        if not separator:
            continue
        pins[key] = raw[1:-1] if raw.startswith('"') and raw.endswith('"') else int(raw)
    return pins


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def kind(relative: str) -> str:
    lower = relative.lower()
    for suffix in FACET_SUFFIXES:
        if lower.endswith(suffix):
            return suffix.removeprefix(".")
    if Path(lower).name in LICENSE_NAMES:
        return "license"
    return "other"


def main() -> None:
    configure_logging()
    parser = argparse.ArgumentParser()
    parser.add_argument("--producer-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.producer_root.resolve()
    repository = Path(__file__).resolve().parents[2]
    pins = read_pins(repository / "mathlib/versions.toml")
    records: list[dict[str, object]] = []
    counts: Counter[str] = Counter()
    sizes: Counter[str] = Counter()
    modules: set[str] = set()

    paths: list[tuple[Path, str, int, int]] = []
    for directory, names, filenames in os.walk(root, followlinks=False):
        names[:] = sorted(name for name in names if name != ".git")
        for filename in sorted(filenames):
            path = Path(directory) / filename
            relative = path.relative_to(root).as_posix()
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode):
                size = len(os.readlink(path).encode("utf-8"))
            elif stat.S_ISREG(mode):
                size = path.stat().st_size
            else:
                raise SystemExit(f"candidate contains unsupported non-regular path: {relative}")
            paths.append((path, relative, mode, size))

    progress = Progress("Mathlib inventory hashing", len(paths), sum(item[3] for item in paths))
    completed_bytes = 0
    for index, (path, relative, mode, size) in enumerate(paths, start=1):
        if stat.S_ISLNK(mode):
            target = os.readlink(path)
            encoded_target = target.encode("utf-8")
            records.append({
                "path": relative,
                "size": len(encoded_target),
                "sha256": hashlib.sha256(encoded_target).hexdigest(),
                "kind": "symlink",
                "linkTarget": target,
            })
            counts["symlink"] += 1
            sizes["symlink"] += len(encoded_target)
            completed_bytes += size
            progress.update(index, completed_bytes)
            continue
        file_kind = kind(relative)
        counts[file_kind] += 1
        sizes[file_kind] += size
        if file_kind == "lean":
            modules.add(relative)
        records.append({"path": relative, "size": size, "sha256": sha256(path), "kind": file_kind})
        completed_bytes += size
        progress.update(index, completed_bytes)
    progress.complete(len(paths), completed_bytes)

    output = {
        "schemaVersion": 1,
        "identity": {
            "mathlibVersion": pins["mathlib_version"],
            "mathlibCommit": pins["mathlib_commit"],
            "leanVersion": pins["lean_version"],
            "leanToolchainId": pins["lean_toolchain_id"],
            "packRevision": pins["pack_revision"],
        },
        "summary": {
            "fileCount": len(records),
            "expandedBytes": sum(int(record["size"]) for record in records),
            "leanModuleSourceCount": len(modules),
            "countsByKind": dict(sorted(counts.items())),
            "bytesByKind": dict(sorted(sizes.items())),
        },
        "largestFiles": sorted(records, key=lambda record: int(record["size"]), reverse=True)[:50],
        "licenses": [record for record in records if record["kind"] == "license"],
        "files": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output["summary"], indent=2))


if __name__ == "__main__":
    main()
