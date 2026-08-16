#!/usr/bin/env python3
"""Run a non-PTY Lean LSP lifecycle and diagnostics check through ADB."""

from __future__ import annotations

import argparse
import json
import os
import re
import select
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from script_progress import configure_logging


DEFAULT_PACKAGE = "org.lean4android.app"
DEFAULT_TOOLCHAIN_ID = "lean-4.32.1-android1"


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


def write_private_file(adb_path: Path, package: str, destination: str, contents: str) -> None:
    parent = destination.rsplit("/", 1)[0]
    temporary = "/data/local/tmp/lean4android-lsp-fixture"
    with tempfile.NamedTemporaryFile() as local:
        local.write(contents.encode())
        local.flush()
        adb(adb_path, "push", local.name, temporary)
        adb(adb_path, "shell", "run-as", package, "mkdir", "-p", parent)
        adb(adb_path, "shell", "run-as", package, "cp", temporary, destination)
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


def package_uid(adb_path: Path, package: str) -> int:
    result = adb(adb_path, "shell", "pm", "list", "packages", "--user", "0", "-U", package)
    match = re.search(rb"\buid:(\d+)\b", result.stdout)
    if not match:
        raise RuntimeError(f"cannot resolve owner-user UID for {package}")
    return int(match.group(1))


def lean_lake_pids(adb_path: Path, uid: int) -> list[str]:
    result = adb(adb_path, "shell", "ps", "-A", "-o", "UID,PID,NAME", check=False)
    pids = []
    for line in result.stdout.decode(errors="replace").splitlines()[1:]:
        fields = line.split(None, 2)
        if len(fields) != 3:
            continue
        observed_uid, pid, name = fields
        if observed_uid == str(uid) and name.rsplit("/", 1)[-1] in {
            "lean", "lake", "liblean_exe.so", "liblake_exe.so",
        }:
            pids.append(pid)
    return pids


def lean_lake_memory_kib(adb_path: Path, uid: int) -> tuple[int, int]:
    result = adb(adb_path, "shell", "ps", "-A", "-o", "UID,PID,RSS,NAME", check=False)
    rss_total = 0
    pss_total = 0
    for line in result.stdout.decode(errors="replace").splitlines()[1:]:
        fields = line.split(None, 3)
        if len(fields) != 4:
            continue
        observed_uid, pid, rss, name = fields
        if observed_uid == str(uid) and name.rsplit("/", 1)[-1] in {
            "lean", "lake", "liblean_exe.so", "liblake_exe.so",
        }:
            try:
                rss_total += int(rss)
            except ValueError:
                pass
            meminfo = adb(adb_path, "shell", "dumpsys", "meminfo", pid, check=False)
            match = re.search(rb"TOTAL PSS:\s+(\d+)", meminfo.stdout)
            if match:
                pss_total += int(match.group(1))
    return rss_total, pss_total


def main() -> int:
    configure_logging()
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, default=Path(".android-sdk/platform-tools/adb"))
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--toolchain-id", default=DEFAULT_TOOLCHAIN_ID)
    parser.add_argument("--force-after-initialize", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._-]+", args.package):
        raise ValueError(f"invalid package: {args.package!r}")
    if not re.fullmatch(r"[A-Za-z0-9._-]+", args.toolchain_id):
        raise ValueError(f"invalid toolchain ID: {args.toolchain_id!r}")
    package = args.package
    uid = package_uid(args.adb, package)
    app_root = f"/data/user/0/{package}"
    sysroot = f"{app_root}/no_backup/toolchains/{args.toolchain_id}"
    project = f"{app_root}/files/projects/lsp-conformance"

    print("Progress device LSP conformance: 0% (checking device)")
    device = adb(args.adb, "get-state").stdout.decode().strip()
    if device != "device":
        raise RuntimeError(f"ADB device is not ready: {device!r}")

    print("Progress device LSP conformance: 10% (installing fixture)")
    write_private_file(args.adb, package, f"{project}/lakefile.toml", """name = "lsp_conformance"\nversion = "0.1.0"\n""")
    write_private_file(args.adb, package, f"{project}/lean-toolchain", "leanprover/lean4:v4.32.1\n")
    source = "theorem broken : 1 + 1 = 3 := by\n  rfl\n"
    write_private_file(args.adb, package, f"{project}/Main.lean", source)

    lean_target = adb(
        args.adb,
        "shell",
        "run-as",
        package,
        "readlink",
        f"{sysroot}/bin/lean",
    ).stdout.decode().strip()
    native_directory = lean_target.rsplit("/", 1)[0]
    environment = {
        "HOME": f"{app_root}/files",
        "TMPDIR": f"{app_root}/cache",
        "LEAN_SYSROOT": sysroot,
        "TZ": f":{sysroot}/share/lean4android/UTC",
        "LD_LIBRARY_PATH": native_directory,
        "PATH": "/system/bin",
        "LAKE_HOME": sysroot,
        "LAKE_OVERRIDE_LEAN": "true",
    }
    launch_started = time.monotonic()
    print("Progress device LSP conformance: 25% (launching server)")
    process = subprocess.Popen(
        [
            str(args.adb),
            "shell",
            "-T",
            "run-as",
            package,
            "/system/bin/env",
            *(f"{key}={value}" for key, value in environment.items()),
            f"{sysroot}/.lake/build/bin/lake",
            "-d",
            project,
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
    sampling = threading.Event()
    peak_rss_kib = [0]
    peak_pss_kib = [0]

    def sample_memory() -> None:
        while not sampling.is_set():
            rss_kib, pss_kib = lean_lake_memory_kib(args.adb, uid)
            peak_rss_kib[0] = max(peak_rss_kib[0], rss_kib)
            peak_pss_kib[0] = max(peak_pss_kib[0], pss_kib)
            sampling.wait(0.1)

    rss_thread = threading.Thread(target=sample_memory, daemon=True)
    rss_thread.start()

    def stop_sampling() -> None:
        sampling.set()
        rss_thread.join(timeout=2.0)

    root_uri = "file://" + project
    document_uri = root_uri + "/Main.lean"
    try:
        print("Progress device LSP conformance: 40% (initializing server)")
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
        initialize_ms = round((time.monotonic() - launch_started) * 1000)

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
            print("Progress device LSP conformance: 80% (forcing transport stop and checking cleanup)")
            process.terminate()
            process.wait(timeout=5.0)
            time.sleep(1.0)
            remaining = lean_lake_pids(args.adb, uid)
            if remaining:
                raise AssertionError(f"orphaned Lean/Lake processes for UID {uid}: {' '.join(remaining)}")
            stop_sampling()
            print("Progress device LSP conformance: 100% (complete)")
            print(json.dumps({
                "initialize": "ok",
                "initializeMs": initialize_ms,
                "peakLeanLakeRssKiB": peak_rss_kib[0],
                "peakLeanLakePssKiB": peak_pss_kib[0],
                "forcedTransportStop": "ok",
                "orphanProcesses": 0,
            }, indent=2))
            return 0
        print("Progress device LSP conformance: 65% (opening document)")
        did_open_started = time.monotonic()
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
        first_diagnostic_ms = round((time.monotonic() - did_open_started) * 1000)
        launch_to_diagnostic_ms = round((time.monotonic() - launch_started) * 1000)
        stop_sampling()

        print("Progress device LSP conformance: 85% (graceful shutdown)")
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
        time.sleep(1.0)
        remaining = lean_lake_pids(args.adb, uid)
        if remaining:
            raise AssertionError(f"orphaned Lean/Lake processes for UID {uid}: {' '.join(remaining)}")
        print("Progress device LSP conformance: 100% (complete)")
        print(json.dumps({
            "initialize": "ok",
            "initializeMs": initialize_ms,
            "diagnosticCount": len(published),
            "firstDiagnosticMs": first_diagnostic_ms,
            "launchToDiagnosticMs": launch_to_diagnostic_ms,
            "peakLeanLakeRssKiB": peak_rss_kib[0],
            "peakLeanLakePssKiB": peak_pss_kib[0],
            "firstDiagnostic": published[0].get("message", ""),
            "shutdown": "ok",
            "exitCode": exit_code,
        }, ensure_ascii=False, indent=2))
        return 0
    finally:
        stop_sampling()
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
