#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path

from script_progress import Progress, configure_logging

configure_logging()

parser = argparse.ArgumentParser()
parser.add_argument("--source-manifest", type=Path, required=True)
parser.add_argument("--staged-root", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()

source = json.loads(args.source_manifest.read_text(encoding="utf-8"))
entries = {entry["path"]: entry for entry in source["files"]}
lines = [f"schema\t1", f"toolchain\t{source['toolchainId']}"]
paths = [path for path in sorted(args.staged_root.rglob("*")) if path.is_file() and path != args.output]
total_bytes = sum(path.stat().st_size for path in paths)
progress = Progress("filtered manifest verification", len(paths), total_bytes)
completed_bytes = 0
for index, path in enumerate(paths, start=1):
    relative = path.relative_to(args.staged_root).as_posix()
    source_path = f"sysroot/{relative}"
    entry = entries.get(source_path)
    if entry is None:
        raise SystemExit(f"staged file is absent from audited manifest: {relative}")
    if path.stat().st_size != entry["size"]:
        raise SystemExit(f"staged file size differs from audited manifest: {relative}")
    lines.append(f"file\t{relative}\t{entry['size']}\t{entry['sha256']}")
    completed_bytes += int(entry["size"])
    progress.update(index, completed_bytes)
progress.complete(len(paths), completed_bytes)

args.output.parent.mkdir(parents=True, exist_ok=True)
contents = "\n".join(lines) + "\n"
args.output.write_text(contents, encoding="utf-8")
print(f"wrote {len(lines) - 2} entries; sha256={hashlib.sha256(contents.encode()).hexdigest()}")
