# Standard input support

- Status: immediate-EOF, interactive, and contained project-file stdin implemented and accepted on API 33; separate stdout/stderr export remains
- Scope: user-triggered Lean program runs
- Depends on: the accepted child-process boundary in [ADR 0001](../adr/0001-android-lean-process-and-runtime-boundary.md)

## Purpose

Lean4Android currently launches a program with a pipe connected to the child's standard input, but the one-shot job supervisor has no input policy or public input operations. In practice, a run can therefore wait forever for bytes that the UI cannot send. M5.1 replaces that accidental behavior with three explicit modes:

1. **Immediate EOF** closes the pipe as soon as the program starts. This is the default and preserves predictable non-interactive runs.
2. **Interactive console** keeps the pipe open and lets the user send bounded UTF-8 text or signal EOF.
3. **Project file** streams a validated, saved project file to the child and closes the pipe when the exact selected bytes have been sent.

This feature is a controlled program-stream capability, not a terminal. It does not add a shell, a pseudo-terminal, terminal emulation, arbitrary filesystem access, or automatic prompt detection.

## Important process facts

Standard input is an unstructured byte stream. A program may:

- read one byte, one line, or all input;
- buffer output until it receives input or exits;
- print a prompt to stdout, stderr, both, or neither;
- read before or after printing anything;
- stop reading while the pipe still has buffered data;
- close its input early; or
- spawn another supported process that inherits the stream.

Consequently, neither output text nor a child blocked in a read is a reliable `waiting for input` event. The UI may truthfully say that interactive input is **open**, but it must not claim that the child requested input unless a future structured application protocol supplies that fact. Prompt-looking output remains ordinary output.

EOF is also distinct from sending an empty string, a newline, Ctrl-D text, cancellation, and closing the Output pane. EOF means closing the parent-owned write end of the pipe exactly once. A run may continue after EOF.

## Existing boundary and required changes

`JvmProcessLauncher` already creates separate pipes and exposes `RunningProcess.standardInput`, `standardOutput`, and `standardError`. `ProcessJobSupervisor` concurrently drains stdout and stderr, bounds their retained bytes, and owns termination. The new design should extend those boundaries rather than expose `OutputStream` to UI code.

The current supervisor needs these changes:

- accept a typed `StdinPlan` when the run starts;
- give one internal writer exclusive ownership of `standardInput`;
- publish immutable, reconnectable stream snapshots while a job is active;
- provide serialized `send`, `sendLine`, and `closeInput` operations for interactive jobs;
- close input on immediate-EOF startup, after project-file transfer, on cancellation, on failure, and during service destruction;
- keep draining stdout and stderr concurrently while input writes are pending; and
- report truncation, input closure, and input-write failures explicitly.

Build jobs should continue using immediate EOF. Only the program phase of build-then-run receives the user-selected stdin plan. LSP continues to own its JSON-RPC stdin separately and must not use this console API.

The program phase launches the pinned Lean executable as `lean --run <contained-source>` after a successful Lake build, with the app-managed project build library prepended to the deterministic `LEAN_PATH`. Physical API-33 evidence showed that plain `lake lean`/file elaboration executes `#eval` with EOF and is not the supported interactive-program boundary. Lake remains responsible for building the project; the direct `--run` child is what receives the supervised stdin pipe and uses the project root as its working directory.

## Typed model

The exact Kotlin names may change during implementation, but the process layer should preserve the following separation:

```kotlin
sealed interface StdinPlan {
    data object ImmediateEof : StdinPlan
    data object Interactive : StdinPlan
    data class Bytes(val source: InputByteSource) : StdinPlan
}

data class ProcessJobSnapshot(
    val id: Long,
    val phase: JobPhase,
    val stdin: StdinSnapshot,
    val stdout: StreamSnapshot,
    val stderr: StreamSnapshot,
    val combined: List<OutputChunk>,
    val result: ProcessResult?,
)
```

`InputByteSource` belongs below Compose and must be openable only once by the supervisor. For project-file mode it should describe an already validated app-private file plus the selected run revision, expected byte count, and, if cheaply available, a content digest. It must not contain a SAF URI or an unvalidated user path.

`StdinSnapshot` should distinguish at least:

- `Closed(reason)` where the reason is immediate EOF, transfer complete, user EOF, child closed, cancelled, or failed;
- `Open(acceptedBytes, pendingBytes)` for interactive mode;
- `Streaming(sentBytes, totalBytes)` for project-file mode; and
- a terminal input error that does not disguise whether the child is still running.

The job phase remains authoritative for starting, running, cancelling, exited, failed, and cancelled states. `stdin = Open` does not imply the child is blocked. Likewise, a closed stdin does not imply the process has exited.

## Interactive writes and backpressure

Compose must never write directly to the pipe. `send` and `sendLine` enqueue immutable UTF-8 bytes onto a single per-job writer. The writer preserves accepted order and is the only code allowed to call `write`, `flush`, or `close` on the child input stream.

The queue must have a small fixed byte limit. A proposed initial limit is 64 KiB pending, measured in encoded bytes rather than Kotlin characters. A send is accepted atomically or rejected before any of its bytes enter the queue. The UI disables Send while the queue lacks capacity and reports `Input is still being delivered`; it must not block the main thread or silently discard input.

Additional rules:

- `sendLine(text)` sends UTF-8 bytes followed by one LF byte. It does not apply platform line-ending conversion.
- `send(text)` sends exactly the UTF-8 encoding of the supplied text and is useful when no newline is desired.
- NUL is valid stdin data even though it is forbidden in process arguments. The first UI may offer text only; a later byte-oriented source need not change the process contract.
- Each accepted submission has a monotonically increasing sequence number. Snapshots distinguish accepted, pending, and failed submissions without echoing secret input into output.
- EOF is ordered after all previously accepted submissions. Once requested, no later submission is accepted.
- A broken pipe or early child close marks stdin closed, drops no already-unreported queue state, and surfaces a concise input error. It does not automatically cancel a child that can still finish normally.
- Cancellation stops accepting input, closes the pipe to unblock writers/readers, terminates the owned child using the existing graceful-then-forced policy, joins writer and drain workers, and only then publishes the terminal snapshot.

Never hold the supervisor state lock during a blocking pipe write. State publication and cancellation must remain possible while the child applies backpressure. Closing the stream from cancellation must unblock the writer; the implementation must test this rather than assume it on every supported API.

## Project-file mode

The selection UI lists only regular files contained by the active app-managed project. The repository resolves the selection through its canonical containment and no-follow rules. SAF is an import/export boundary only; provider streams and provider paths are never handed to Lean or Lake.

The input is a saved byte revision, not a live editor buffer. Before Run:

- a clean file may be opened from its contained app-private path;
- a dirty selected file must offer **Save and Run**, **Run saved version**, or **Cancel**;
- **Save and Run** must complete the existing atomic save before the input source is captured;
- **Run saved version** must identify that choice in the run summary; and
- rename/delete/project-switch races must either preserve an already opened immutable descriptor/revision or fail before launching the child. They must never silently switch to another file.

The transfer worker streams fixed-size chunks; it does not load an unbounded file into memory. General-project file size policy supplies the maximum accepted input size. The worker checks the expected length, publishes progress, closes stdin on success, and reports short read, concurrent replacement, I/O failure, or early child close truthfully. A failed transfer cancels the run by default because the child did not receive the selected input contract.

The child working directory is the canonical app-managed project root. This supports project-relative file reads independently of stdin; it does not create an OS sandbox within the app UID.

## Output streams

Stdout and stderr remain separate at capture time and for export. Each drain worker records byte chunks with a stream tag and a supervisor-assigned monotonically increasing observation sequence. The combined Output view uses that observed sequence, while making clear that cross-pipe ordering is best effort: the operating system does not provide a single total order between stdout and stderr.

Retention must be byte-bounded independently for stdout, stderr, and combined-display metadata. UTF-8 decoding is incremental so a retained chunk boundary cannot create a replacement character merely by splitting a multi-byte code point. Invalid UTF-8 is displayed with explicit replacement but the raw retained/exported byte policy must be decided before implementation; text reconstructed from a lossy display is not a byte-faithful export.

When a bound is reached, continue draining and discard according to the documented retention policy so the child cannot deadlock on a full output pipe. Snapshots and exports state that truncation occurred and how many bytes were omitted when known. Input text is not automatically echoed into stdout or the combined view; only bytes emitted by the child are program output.

Independent stdout/stderr export uses SAF `CreateDocument`, writes a stable captured revision, and leaves the retained streams available if the picker is cancelled or the provider write fails. Export failure never changes job or stdin state.

## Service ownership and recreation

The retained `ProjectJobService` should become the sole owner of user-triggered build/run supervisors. Activity-owned `activeJob` is insufficient for interactive input because recreation can otherwise lose the only input handle or cancel inconsistently.

The service API should expose job IDs and typed operations:

```text
start(runRequest) -> snapshot
snapshots() -> snapshots
send(jobId, bytes, appendLf) -> accepted/rejected
closeInput(jobId) -> changed/alreadyClosed/rejected
cancel(jobId) -> changed/alreadyTerminal
observe(listener)
```

On Activity recreation, the new UI binds, obtains the latest snapshots, and re-enables controls solely from service state. It does not resend unacknowledged editor text. A submission is cleared from the input composer only after the service accepts it. Back/Escape dismisses transient UI but does not send EOF or cancel unless the user explicitly chose that action.

An ordinary bound-service lifetime can preserve jobs across configuration changes, but not arbitrary app-process death. The product must choose and visibly implement one honest boundary:

- keep the service alive for the run using an Android-compliant started/foreground-service policy where required; or
- treat loss of the app process as cancellation and rely on OS pipe/process cleanup, then report the interrupted run from a small durable tombstone on next launch.

It must not claim reconnection to a child after app-process death unless there is a proven process identity, ownership, stream reattachment, and cleanup mechanism. No such mechanism exists in the current design. Regardless of policy, physical validation must show that app-process death leaves no Lean/Lake child.

Completed-job retention is bounded by count, total bytes, and age. Removing a terminal snapshot never affects an active child. Project deletion or switching does not implicitly detach a running job; the UI identifies the owning project and requires explicit cancellation when an operation would invalidate its contract.

## UI behavior

Run configuration presents a required **Input** choice:

- **None (EOF)** — default;
- **Interactive**; or
- **Project file…** followed by the contained relative path.

During an interactive run, Output includes a distinct input composer with Send line, Send, EOF, and Cancel controls. It displays `Input open`, `Delivering input`, `EOF sent`, or the exact failure; it never displays `Program is waiting for input` based on heuristics. EOF requires confirmation only when unsent composer text exists. Cancel remains available after EOF until the process exits.

The input composer is accessible by touch and hardware keyboard, preserves IME composition, has an explicit multiline policy, and does not steal normal editor shortcuts. Secrets are not promised: submitted text may remain visible in the composer/history only if the UI explicitly says so. The initial implementation should retain no submission history and should clear accepted text.

Output presentation may be Docked or Popup under M5.0. Changing or dismissing presentation never changes stdin ownership. An active interactive run must have a direct way to reopen its Output/input controls.

## Failure and lifecycle rules

| Event | Required behavior |
|---|---|
| Immediate-EOF run starts | Close stdin before waiting for exit; continue draining both outputs. |
| User sends EOF | Deliver all accepted earlier bytes, close once, reject later sends, keep the job running. |
| Child closes stdin early | Mark input closed, report any rejected/pending write, continue observing the child. |
| Input queue is full | Reject the whole new submission without blocking UI or truncating it. |
| Project-file read fails | Close input, cancel the child, report transfer failure and partial byte count. |
| Child exits during a write | Join writer/drainers, publish captured output and exit; retain the input error as secondary detail. |
| User cancels | Reject input, close pipe, terminate child, drain/join, publish Cancelled. |
| Activity recreates | Rebind and render the retained service snapshot; do not resend input. |
| App process is killed | Child must not remain; next launch truthfully reports interruption if a tombstone was recorded. |
| Output/export UI closes | No effect on the active child or stdin. |
| A new run starts | Use a new job ID and stdin plan; never reuse a closed pipe or pending queue. |

## Security and privacy

- Preserve shell-free absolute executable paths, typed argument arrays, the cleared deterministic environment, and the canonical project working directory.
- Validate all project-file selections at use time with canonical containment and no-follow rules. Reject directories, links, special files, traversal, and files outside the active project.
- Do not interpret input as shell syntax or interpolate it into arguments/environment variables.
- Bound input submissions, pending queue bytes, selected file size, retained output, combined metadata, completed jobs, and durable failure records.
- Do not log stdin contents, SAF destinations, or project-private output to Logcat. Diagnostics may record job ID, byte counts, mode, state transitions, and sanitized failure classes.
- Never persist an open `OutputStream`, file descriptor number, randomized native-library path, or provider URI as durable identity.
- Closing or deleting project UI must not follow links or weaken the repository's existing storage protections.

## Implementation sequence

1. Add typed stdin plans, immutable snapshots, bounded output metadata, and fake-process tests in `core-process`.
2. Implement the single serialized writer, explicit EOF, bounded queue, project-byte streaming, broken-pipe handling, and cancellation races.
3. Move build/run ownership fully into `ProjectJobService`; add reconnectable job operations and bounded terminal retention.
4. Add repository-level contained input-file resolution and dirty-revision choices.
5. Add Run input selection and interactive controls, keeping Output presentation independent from stream lifetime.
6. Add separate stdout/stderr export from stable retained revisions.
7. Run host race/backpressure tests, Android instrumentation, and physical-device conformance before enabling the UI by default.

## Validation matrix

Host tests should use controllable fake streams and real small helper processes where portable. Required cases include:

- immediate EOF observed by a reader;
- exact UTF-8 send and send-line bytes, including split multi-byte output reads;
- multiple concurrent UI calls serialized in accepted order;
- queue-full atomic rejection;
- EOF ordered after accepted writes and idempotent on repeat;
- child closes input before, during, and after a write;
- a child that never reads input while stdout/stderr continue and cancellation remains prompt;
- project-file exact bytes, bounded streaming, empty file, maximum file, short read, replacement race, and dirty saved-version choices;
- independent stdout/stderr bounds and export, combined observation order, invalid UTF-8, and truncation disclosure;
- cancellation at launch, during queued input, during a blocking write, during file transfer, after EOF, and at natural exit;
- Activity recreation during open input, pending input, file transfer, and terminal publication;
- repeated runs and stale job IDs rejecting writes to the wrong child; and
- service destruction joining every worker and closing every stream.

Android/device acceptance must additionally cover:

1. the pinned official Lean `cat`-style example with immediate EOF, interactive lines, an empty line, Unicode, and explicit EOF;
2. the same program reading exact bytes from a contained project file while Wi-Fi is disabled;
3. stdout and stderr produced around input, visibly separate and independently exported;
4. rotation, compact/tablet layout changes, Output dismissal/reopen, and Activity recreation while input remains open;
5. cancellation of a child blocked on stdin and of a child not consuming a full input pipe;
6. app-process death under the selected service policy; and
7. exact package-UID process checks proving no Lean, Lake, or program child remains after cancellation, service destruction, process death, and test completion.

Compilation or a successful non-interactive run alone is not acceptance evidence for stdin support.

## Decisions to retain

- Immediate EOF is the safe default.
- Prompt detection is out of scope because ordinary process streams provide no structured request event.
- Interactive input is a bounded pipe, not a PTY or terminal.
- Project-file input comes only from a validated saved app-private revision.
- The service owns streams; Compose operates on typed job IDs and snapshots.
- EOF, cancellation, UI dismissal, and process exit remain separate actions.
- Output is continuously drained even after retention limits are reached.
- App-process death must terminate the child unless a future ADR proves safe stream reattachment.
