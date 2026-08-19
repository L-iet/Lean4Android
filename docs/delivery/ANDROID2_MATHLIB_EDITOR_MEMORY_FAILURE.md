# Android2 Mathlib editor low-memory failure

Status: reproduced and diagnosed; product mitigation not yet implemented

Date: 2026-08-19

Device: Samsung SM-T870, Android/API 33, 5,784,248 KiB physical RAM plus
4,194,300 KiB compressed swap

Toolchain/package: `lean-4.32.1-android2` /
`org.lean4android.app.android2candidate`

## Summary

Opening the original `M5MathlibProbe` workspace can make the UI stutter and
then disappear. Android is killing the foreground application for system-wide
memory pressure. This is not a Java exception, native crash, ANR, malformed
proof, incompatible olean, or thermal shutdown.

The direct trigger is document fan-out. The recovered workspace opens
`Main.lean`, `Narrow.lean`, and `PrimeProof.lean` together. Lean Server creates
one native worker for each open document, and all three load substantial
Mathlib state independently. The Lake server also has a large initialization
peak. A one-file comparison containing only the prime proof still uses a large
amount of memory, but it reaches `Lean server: Ready` and remains alive. Three
concurrent workers push this 6 GB-class tablet past its low-memory threshold.

## Proof under test

The saved 93-byte source has SHA-256
`431980d8748a88d9eb7d31fce2476f7000780d8cb5dfa4ff165946edefcec7d1`:

```lean
import Mathlib.Data.Nat.Prime.Basic

theorem thirteen_is_prime : Nat.Prime 13 := by
  decide
```

Packaged Android2 Lean checks this file directly and offline in 10.89 seconds
with exit 0. The same bytes also reach Ready with no goals in the one-file LSP
comparison. The theorem and targeted Android2 artifacts are therefore valid.

## Android termination evidence

Before the monitored reproduction, `dumpsys activity exit-info` contained six
consecutive foreground Android2 exits between 00:16:32 and 00:23:43 with:

- reason `LOW_MEMORY`;
- importance 100 (foreground);
- app-process PSS between 109 and 195 MB; and
- app-process RSS between 112 and 249 MB.

The first recorded victim was PID 28451, the exact process previously left
open on `PrimeProof.lean`. The fresh reproduction added PID 15963 at 00:32:10:

```text
reason=3 (LOW_MEMORY), importance=100, pss=196MB, rss=244MB
```

Logcat contains the unambiguous LMKD decision:

```text
lmkd: Reclaim 'org.lean4android.app.android2candidate' (15963),
      uid 10509, oom_score_adj 0, state 2 to free 96124kB rss;
      reason: min2x watermark is breached even after kill
WindowManager: WIN DEATH ... MainActivity
```

LMKD first killed multiple unrelated cached/service processes, reported swap
pressure and thrashing above 120%, and finally killed the foreground app. No
`AndroidRuntime` fatal exception, native tombstone, or ANR explains the exit.

## Three-document reproduction

The run began with about 2,301,500 KiB `MemAvailable` and 3,316,272 KiB
`SwapFree`. The process topology became:

```text
liblake_exe.so serve
lean --server
lean --worker .../Main.lean
lean --worker .../Narrow.lean
lean --worker .../PrimeProof.lean
```

Representative one-second RSS samples (RSS double-counts shared pages and is
reported here as process pressure, not aggregate PSS) were:

| Phase | Lake RSS | Main worker | Narrow worker | Prime worker | MemAvailable |
|---|---:|---:|---:|---:|---:|
| Workers appear | 965 MB | 104 MB | 104 MB | 104 MB | 1,240 MB |
| Imports grow | 947 MB | 410 MB | 433 MB | 432 MB | 658 MB |
| Immediately before kill | 2 MB reclaimed/swapped | 343 MB | 1,228 MB | 1,228 MB | 373 MB |

At the last pre-kill sample, `SwapFree` was 2,048,252 KiB and memory PSI was:

```text
some avg10=15.98
full avg10=8.42
```

The sudden return to about 3.4 GB available memory in the next sample coincided
with LMKD removing the complete Android2 process group. A second attempt to
switch workspaces after launch followed the same three-worker trajectory and
was killed before the UI selection could take effect.

## One-file comparison

`M5MathlibPrimeOnly` contains the same metadata, offline Mathlib path, and exact
proof, but has only `PrimeProof.lean` as a library root and no recovery state.
It was selected as the persisted active project while the app was stopped, so
the old three-worker workspace never started.

The one-file app reached `Lean server: Ready`, displayed the exact theorem with
`No goals`, and remained alive beyond the 60-second sampling window. Its stable
topology was one Lake server, one Lean coordinator, and one document worker.
Peak/stable evidence includes:

- document worker: about 1.90 GB peak RSS, later 1,851,360 KiB RSS and
  1,850,160 KiB reported total PSS;
- Lake: about 997 MB peak RSS, later 399,420 KiB RSS, 924,583 KiB reported
  total PSS, and 527,468 KiB swap PSS;
- Lean coordinator: 53,480 KiB RSS / 51,104 KiB PSS;
- Compose app: 128,960 KiB RSS / 105,104 KiB total PSS, of which graphics
  accounted for about 64 MB;
- system `MemAvailable`: stabilized near 0.74 GB; and
- `SwapFree`: fell to about 2.18 GB.

Android's `dumpsys meminfo` total-PSS presentation can include swapped
proportional pages, while RSS sums double-count shared mappings. These figures
must not be summed as an exact physical-RAM total. The independent system
signals—falling `MemAvailable`, falling `SwapFree`, PSI stalls, LMKD logs, and
ApplicationExitInfo—establish the pressure and termination without relying on
such a sum.

Thermal status remained 0. AP, battery, and skin readings were approximately
31.3 C, 28.7 C, and 31.6 C. Temperature was not the cause.

## Root cause

The current editor/session contract restores multiple tabs and synchronizes
each open Lean document to one Lake/Lean server. Lean Server implements those
documents with separate native workers. Mathlib's per-worker state is large on
this target, so ordinary multi-tab recovery multiplies memory faster than the
tablet can reclaim or swap it. Android eventually kills the foreground UI
process even though the Compose/Java process is not the largest consumer.

The imported module itself is therefore memory-expensive but not, by itself,
the complete explanation for the observed 5–10 second closure. The decisive
difference is one worker versus three concurrent workers.

## Required product work

Mathlib promotion must remain blocked on a memory-aware document lifecycle.
At minimum:

1. Do not automatically `didOpen` every recovered Mathlib tab. Restore tab
   presentation separately, but synchronize only the active document until an
   inactive tab is selected.
2. Send `didClose` for inactive heavy documents, or otherwise serialize their
   workers, before opening the next Mathlib document. One server per project is
   insufficient if it retains one large worker per open document.
3. Add an aggregate process budget covering the app, Lake, coordinator, and
   workers. App-process PSS alone missed the cause.
4. Treat rapid `LOW_MEMORY` ApplicationExitInfo after Mathlib activation as a
   recoverable safe-mode signal: reopen one document, disclose why other tabs
   are deferred, and avoid an automatic crash loop.
5. Validate one-, two-, and three-document Mathlib cases on the 6 GB reference
   tablet, including cold/warm PSS, swap, PSI, goal latency, tab switching,
   dirty-buffer preservation, service recreation, and exact orphan cleanup.
6. Re-measure after the full pack is built. A successful basic import cannot
   establish the memory boundary for broader modules or tactics.

The current one-file project is a diagnostic workaround, not the final product
design. Users should not need to split projects to keep Mathlib alive.

## Evidence files

Ignored durable captures are retained under `toolchain/output/`:

- `android2-mathlib-ui-repro-memory.log`;
- `android2-mathlib-ui-repro-logcat.log`;
- `android2-mathlib-single-file-memory.log` and matching logcat (the attempted
  UI switch, contaminated by the original project's startup); and
- `android2-mathlib-prime-only-memory.log` and matching logcat (the clean
  one-file comparison).

