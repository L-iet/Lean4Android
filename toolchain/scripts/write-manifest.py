#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import sys

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

files = []
for path in sorted(root.rglob("*")):
    if not path.is_file() or path == output:
        continue
    data = path.read_bytes()
    files.append({
        "path": path.relative_to(root).as_posix(),
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "kind": kind(path),
    })

manifest = {
    "schemaVersion": 1,
    "toolchainId": "lean-4.32.1-android1",
    "leanVersion": "4.32.1",
    "leanCommit": os.environ["LEAN_COMMIT_VALUE"],
    "ndkVersion": "28.2.13676358",
    "abi": "arm64-v8a",
    "minimumApi": 29,
    "files": files,
}
output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
