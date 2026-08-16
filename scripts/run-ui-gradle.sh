#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly GRADLE_STATE="${LEAN4ANDROID_GRADLE_USER_HOME:-$REPOSITORY_ROOT/.gradle-user-home}"
readonly LOG_FILE="${LEAN4ANDROID_UI_GRADLE_LOG:-$REPOSITORY_ROOT/toolchain/output/ui-gradle-build.log}"
readonly PROGRESS_INTERVAL_SECONDS="${LEAN4ANDROID_PROGRESS_INTERVAL_SECONDS:-30}"

[[ "$PROGRESS_INTERVAL_SECONDS" =~ ^[1-9][0-9]*$ ]] || {
  echo "Invalid progress interval: $PROGRESS_INTERVAL_SECONDS" >&2
  exit 1
}

if (($# == 0)); then
  set -- testDebugUnitTest :app:assembleDebug
fi

mkdir -p "$(dirname "$LOG_FILE")" "$GRADLE_STATE"
touch "$LOG_FILE"

started_at=$SECONDS
echo "[$(date -Is)] Starting UI/Gradle validation" | tee -a "$LOG_FILE"
echo "Repository: $REPOSITORY_ROOT" | tee -a "$LOG_FILE"
echo "Gradle state: $GRADLE_STATE" | tee -a "$LOG_FILE"
echo "Tasks: $*" | tee -a "$LOG_FILE"
echo "Gradle policy: --no-build-cache --no-daemon --console=plain" | tee -a "$LOG_FILE"

(
  cd "$REPOSITORY_ROOT"
  GRADLE_USER_HOME="$GRADLE_STATE" \
    ./gradlew --no-build-cache --no-daemon --console=plain "$@"
) 2>&1 | tee -a "$LOG_FILE" &
gradle_pipeline_pid=$!

heartbeat() {
  while kill -0 "$gradle_pipeline_pid" 2>/dev/null; do
    sleep "$PROGRESS_INTERVAL_SECONDS"
    kill -0 "$gradle_pipeline_pid" 2>/dev/null || break
    last_output="$(awk 'NF && $0 !~ /UI\/Gradle build still running/ { line=$0 } END { print line }' "$LOG_FILE")"
    echo "[$(date -Is)] UI/Gradle build still running ($((SECONDS - started_at))s elapsed); last output: ${last_output:-none}" | tee -a "$LOG_FILE"
  done
}

heartbeat &
heartbeat_pid=$!

stop_children() {
  kill "$heartbeat_pid" 2>/dev/null || true
  kill "$gradle_pipeline_pid" 2>/dev/null || true
}
trap stop_children HUP INT TERM

if wait "$gradle_pipeline_pid"; then
  status=0
else
  status=$?
fi
kill "$heartbeat_pid" 2>/dev/null || true
wait "$heartbeat_pid" 2>/dev/null || true
trap - HUP INT TERM

if ((status == 0)); then
  echo "[$(date -Is)] UI/Gradle validation completed in $((SECONDS - started_at))s" | tee -a "$LOG_FILE"
else
  echo "[$(date -Is)] UI/Gradle validation failed with exit $status after $((SECONDS - started_at))s" | tee -a "$LOG_FILE" >&2
fi
exit "$status"
