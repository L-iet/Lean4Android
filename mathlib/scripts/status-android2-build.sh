#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly PRODUCER_ROOT="${LEAN4ANDROID_MATHLIB_PRODUCER_ROOT:-$REPOSITORY_ROOT/toolchain/work/mathlib-android2-producer}"
readonly MATHLIB_ROOT="$PRODUCER_ROOT/mathlib"
readonly BUILD_STATE="$PRODUCER_ROOT/build-state.txt"
readonly BUILD_LOCK="$PRODUCER_ROOT/build.lock"
readonly LOG_FILE="${LEAN4ANDROID_LOG_FILE:-$REPOSITORY_ROOT/toolchain/output/mathlib-android2-basic-build.log}"

state="missing"
[[ -f "$BUILD_STATE" ]] && state="$(sed -n '1p' "$BUILD_STATE")"

lock_status="free"
if [[ -e "$BUILD_LOCK" ]]; then
  exec 9>"$BUILD_LOCK"
  if ! flock -n 9; then
    lock_status="held"
  fi
fi

recorded_pid="$(sed -n 's/.*\(^\| \)\(pid\|prior_pid\)=\([0-9][0-9]*\).*/\3/p' <<<"$state")"
pid_status="not-recorded"
if [[ -n "$recorded_pid" ]]; then
  if kill -0 "$recorded_pid" 2>/dev/null; then
    pid_status="alive (identity not trusted; lock is authoritative)"
  else
    pid_status="not alive"
  fi
fi

oleans=0
if [[ -d "$MATHLIB_ROOT/.lake" ]]; then
  oleans="$(find "$MATHLIB_ROOT/.lake" -path '*/build/lib/lean/*.olean' -type f 2>/dev/null | wc -l)"
fi

echo "Producer root: $PRODUCER_ROOT"
echo "Recorded state: $state"
echo "Producer lock: $lock_status"
echo "Recorded PID: ${recorded_pid:-none} ($pid_status)"
echo "Finalized oleans present: $oleans"
if [[ -f "$LOG_FILE" ]]; then
  echo "Log: $LOG_FILE"
  echo "Log last modified: $(stat -c '%y' "$LOG_FILE")"
  echo "Last progress/build line:"
  awk '/Progress Android2 Mathlib|Built |Completed Android2 Mathlib|failed with exit/ { line=$0 } END { if (line != "") print line; else print "(none)" }' "$LOG_FILE"
else
  echo "Log: missing ($LOG_FILE)"
fi

if [[ "$lock_status" == "held" ]]; then
  echo "Assessment: a producer holds the lock; monitor it and do not start another."
elif [[ "$state" == running* || "$state" == interrupted* ]]; then
  echo "Assessment: interrupted/stale run; the documented build command will audit and resume incrementally."
elif [[ "$state" == complete* ]]; then
  echo "Assessment: the recorded target completed; perform its artifact/device gate before a broader target."
elif [[ "$state" == failed* ]]; then
  echo "Assessment: the prior run failed; inspect the log, then use the same command for conservative recovery."
else
  echo "Assessment: no active producer is evident; use a documented targeted/full command to start one."
fi
