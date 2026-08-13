#!/usr/bin/env python3
"""Run a non-PTY Lean LSP lifecycle and diagnostics check through ADB."""

from __future__ import annotations

import argparse
import json
import os
import select
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path


PACKAGE = "org.lean4android.app"
APP_ROOT = f"/data/user/0/{PACKAGE}"
SYSROOT = f"{APP_ROOT}/no_backup/toolchains/lean-4.32.1-android1"
PROJECT = f"{APP_ROOT}/files/projects/lsp-conformance"


def adb(
    adb_path: Path,
    *arguments: str,
    input_bytes: bytes | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [str(adb_path), *arguments],
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
    )


def write_private_file(adb_path: Path, destination: str, contents: str) -> None:
    parent = destination.rsplit("/", 1)[0]
    temporary = "/data/local/tmp/lean4android-lsp-fixture"
    with tempfile.NamedTemporaryFile() as local:
        local.write(contents.encode())
        local.flush()
        adb(adb_path, "push", local.name, temporary)
        adb(adb_path, "shell", "run-as", PACKAGE, "mkdir", "-p", parent)
        adb(adb_path, "shell", "run-as", PACKAGE, "cp", temporary, destination)
        adb(adb_path, "shell", "rm", "-f", temporary)


def frame(message: dict[str, object]) -> bytes:
    payload = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii") + payload


def read_exact(stream, length: int, deadline: float) -> bytes:
    result = bytearray()
    while len(result) < length:
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([stream], [], [], remaining)[0]:
            raise TimeoutError(f"timed out after {len(result)} of {length} payload bytes")
        chunk = os.read(stream.fileno(), length - len(result))
        if not chunk:
            raise EOFError(f"server closed after {len(result)} of {length} payload bytes")
        result.extend(chunk)
    return bytes(result)


def read_message(stream, timeout: float = 30.0) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    header = bytearray()
    while not header.endswith(b"\r\n\r\n"):
        if len(header) >= 16 * 1024:
            raise ValueError("LSP header exceeded 16 KiB")
        header.extend(read_exact(stream, 1, deadline))
    lengths = []
    for line in header[:-4].decode("ascii").split("\r\n"):
        name, separator, value = line.partition(":")
        if not separator:
            raise ValueError(f"malformed LSP header: {line!r}")
        if name.lower() == "content-length":
            lengths.append(int(value.strip()))
    if len(lengths) != 1 or lengths[0] < 0:
        raise ValueError("LSP response requires one valid Content-Length")
    return json.loads(read_exact(stream, lengths[0], deadline).decode("utf-8"))


def wait_for(
    stream,
    predicate,
    description: str,
    timeout: float = 30.0,
    writer=None,
    verbose: bool = False,
) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while True:
        message = read_message(stream, max(0.1, deadline - time.monotonic()))
        if verbose:
            print(f"received while waiting for {description}: {json.dumps(message, ensure_ascii=False)}", file=sys.stderr)
        if predicate(message):
            return message
        if writer is not None and "id" in message and "method" in message:
            writer.write(frame({"jsonrpc": "2.0", "id": message["id"], "result": None}))
            writer.flush()
        if time.monotonic() >= deadline:
            raise TimeoutError(f"timed out waiting for {description}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, default=Path(".android-sdk/platform-tools/adb"))
    parser.add_argument("--force-after-initialize", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    device = adb(args.adb, "get-state").stdout.decode().strip()
    if device != "device":
        raise RuntimeError(f"ADB device is not ready: {device!r}")

    write_private_file(args.adb, f"{PROJECT}/lakefile.toml", """name = "lsp_conformance"\nversion = "0.1.0"\n""")
    write_private_file(args.adb, f"{PROJECT}/lean-toolchain", "leanprover/lean4:v4.32.1\n")
    source = "theorem broken : 1 + 1 = 3 := by\n  rfl\n"
    write_private_file(args.adb, f"{PROJECT}/Main.lean", source)

    lean_target = adb(
        args.adb,
        "shell",
        "run-as",
        PACKAGE,
        "readlink",
        f"{SYSROOT}/bin/lean",
    ).stdout.decode().strip()
    native_directory = lean_target.rsplit("/", 1)[0]
    environment = {
        "HOME": f"{APP_ROOT}/files",
        "TMPDIR": f"{APP_ROOT}/cache",
        "LEAN_SYSROOT": SYSROOT,
        "TZ": f":{SYSROOT}/share/lean4android/UTC",
        "LD_LIBRARY_PATH": native_directory,
        "PATH": "/system/bin",
        "LAKE_HOME": SYSROOT,
        "LAKE_OVERRIDE_LEAN": "true",
    }
    process = subprocess.Popen(
        [
            str(args.adb),
            "shell",
            "-T",
            "run-as",
            PACKAGE,
            "/system/bin/env",
            *(f"{key}={value}" for key, value in environment.items()),
            f"{SYSROOT}/.lake/build/bin/lake",
            "-d",
            PROJECT,
            "serve",
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdin and process.stdout and process.stderr
    stderr_chunks: list[bytes] = []
    stderr_thread = threading.Thread(target=lambda: stderr_chunks.append(process.stderr.read()), daemon=True)
    stderr_thread.start()

    root_uri = "file://" + PROJECT
    document_uri = root_uri + "/Main.lean"
    try:
        process.stdin.write(frame({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "processId": None,
                "rootUri": root_uri,
                "capabilities": {"lean": {"incrementalDiagnosticSupport": True, "silentDiagnosticSupport": True}},
                "initializationOptions": {"hasWidgets": True},
            },
        }))
        process.stdin.flush()
        initialize = wait_for(
            process.stdout, lambda message: message.get("id") == 1, "initialize response", verbose=args.verbose
        )
        if "result" not in initialize:
            raise AssertionError(f"initialize failed: {initialize}")

        process.stdin.write(frame({"jsonrpc": "2.0", "method": "initialized", "params": None}))
        process.stdin.flush()
        registration = wait_for(
            process.stdout,
            lambda message: message.get("method") == "client/registerCapability",
            "client/registerCapability",
            verbose=args.verbose,
        )
        process.stdin.write(frame({"jsonrpc": "2.0", "id": registration["id"], "result": None}))
        process.stdin.flush()
        if args.force_after_initialize:
            process.terminate()
            process.wait(timeout=5.0)
            time.sleep(1.0)
            remaining = adb(
                args.adb,
                "shell",
                "pidof",
                "lean",
                "lake",
                "liblean_exe.so",
                "liblake_exe.so",
                check=False,
            )
            if remaining.stdout.strip():
                raise AssertionError(f"orphaned Lean/Lake processes: {remaining.stdout.decode().strip()}")
            print(json.dumps({"initialize": "ok", "forcedTransportStop": "ok", "orphanProcesses": 0}, indent=2))
            return 0
        process.stdin.write(frame({
            "jsonrpc": "2.0",
            "method": "textDocument/didOpen",
            "params": {"textDocument": {"uri": document_uri, "languageId": "lean", "version": 1, "text": source}},
        }))
        process.stdin.flush()
        diagnostics = wait_for(
            process.stdout,
            lambda message: message.get("method") == "textDocument/publishDiagnostics"
            and message.get("params", {}).get("uri") == document_uri
            and bool(message.get("params", {}).get("diagnostics")),
            "nonempty publishDiagnostics",
            timeout=45.0,
            writer=process.stdin,
            verbose=args.verbose,
        )
        published = diagnostics["params"].get("diagnostics", [])
        if not published:
            raise AssertionError(f"invalid Lean source produced no diagnostics: {diagnostics}")

        process.stdin.write(frame({"jsonrpc": "2.0", "id": 2, "method": "shutdown", "params": None}))
        process.stdin.flush()
        shutdown = wait_for(
            process.stdout,
            lambda message: message.get("id") == 2,
            "shutdown response",
            writer=process.stdin,
            verbose=args.verbose,
        )
        if shutdown.get("result", object()) is not None:
            raise AssertionError(f"unexpected shutdown response: {shutdown}")
        process.stdin.write(frame({"jsonrpc": "2.0", "method": "exit", "params": None}))
        process.stdin.flush()
        process.stdin.close()
        exit_code = process.wait(timeout=15.0)
        if exit_code != 0:
            raise AssertionError(f"server exited {exit_code}")
        print(json.dumps({
            "initialize": "ok",
            "diagnosticCount": len(published),
            "firstDiagnostic": published[0].get("message", ""),
            "shutdown": "ok",
            "exitCode": exit_code,
        }, ensure_ascii=False, indent=2))
        return 0
    finally:
        if process.stdin and not process.stdin.closed:
            process.stdin.close()
        try:
            observed_exit = process.wait(timeout=1.0)
        except subprocess.TimeoutExpired:
            observed_exit = None
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3.0)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        stderr_thread.join(timeout=2.0)
        stderr_text = b"".join(stderr_chunks).decode("utf-8", errors="replace").strip()
        if stderr_text:
            print(f"server stderr:\n{stderr_text}", file=sys.stderr)
        if observed_exit is not None and (observed_exit != 0 or args.verbose):
            print(f"server observed exit code: {observed_exit}", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
