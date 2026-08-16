#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import sys

from script_progress import Progress, configure_logging

configure_logging()

root = pathlib.Path(sys.argv[1]).resolve()
output = pathlib.Path(sys.argv[2]).resolve()

def kind(path: pathlib.Path) -> str:
    relative = path.relative_to(root).as_posix()
    if relative.startswith("native/"):
        return "executable" if relative.endswith(("liblean_exe.so", "liblake_exe.so")) else "shared-library"
    if relative.endswith("LICENSE"):
        return "license"
    if relative.startswith("sysroot/src/"):
        return "source"
    return "lean-data"

paths = [path for path in sorted(root.rglob("*")) if path.is_file() and path != output]
total_bytes = sum(path.stat().st_size for path in paths)
progress = Progress("distribution hashing", len(paths), total_bytes)
files = []
completed_bytes = 0
for index, path in enumerate(paths, start=1):
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
            size += len(chunk)
    files.append({
        "path": path.relative_to(root).as_posix(),
        "size": size,
        "sha256": digest.hexdigest(),
        "kind": kind(path),
    })
    completed_bytes += size
    progress.update(index, completed_bytes)
progress.complete(len(paths), completed_bytes)

manifest = {
    "schemaVersion": 1,
    "toolchainId": os.environ.get("TOOLCHAIN_ID_VALUE", "lean-4.32.1-android1"),
    "leanVersion": "4.32.1",
    "leanCommit": os.environ["LEAN_COMMIT_VALUE"],
    "ndkVersion": "28.2.13676358",
    "abi": "arm64-v8a",
    "minimumApi": 29,
    "files": files,
}
output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
