#!/usr/bin/env bash
set -Eeuo pipefail

if (( $# < 2 )); then
  echo "Usage: $0 <label> <timeout-minutes> [Gradle arguments...]" >&2
  exit 2
fi

label="$1"
timeout_minutes="$2"
shift 2

if [[ ! "$timeout_minutes" =~ ^[1-9][0-9]*$ ]]; then
  echo "timeout-minutes must be a positive integer: $timeout_minutes" >&2
  exit 2
fi

safe_label="${label//[^a-zA-Z0-9_.-]/_}"
log_dir="${CI_GRADLE_LOG_DIR:-ci-logs}"
mkdir -p "$log_dir"
log_file="$log_dir/$safe_label.log"
workspace="${GITHUB_WORKSPACE:-$PWD}"
jvm_args="${CI_GRADLE_JVM_ARGS:--Xmx6g -Dfile.encoding=UTF-8}"
started_at="$(date +%s)"
heartbeat_pid=""
gradle_pid=""

system_snapshot() {
  echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $label system snapshot"
  echo "Working directory: $workspace"
  free -h 2>&1 || true
  df -h "$workspace" 2>&1 || true
  ps -eo pid,ppid,rss,%mem,etime,comm --sort=-rss 2>&1 | head -n 12 || true
}

cleanup() {
  if [[ -n "$heartbeat_pid" ]] && kill -0 "$heartbeat_pid" 2>/dev/null; then
    kill "$heartbeat_pid" 2>/dev/null || true
  fi
  if [[ -n "$heartbeat_pid" ]]; then
    wait "$heartbeat_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

gradle_command=(
  ./gradlew
  --no-daemon
  --no-configuration-cache
  --profile
  --console=plain
  --stacktrace
  "-Dorg.gradle.jvmargs=$jvm_args"
  "$@"
)

echo "Starting $label build with a ${timeout_minutes} minute timeout"
echo "Gradle JVM arguments: $jvm_args"
system_snapshot | tee "$log_file.system"

heartbeat() {
  while kill -0 "$gradle_pid" 2>/dev/null; do
    sleep 60
    if kill -0 "$gradle_pid" 2>/dev/null; then
      {
        echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $label is still running"
        system_snapshot
      } | tee -a "$log_file"
    fi
  done
}

set +e
# Keep timeout's process-group handling enabled so a timed-out Gradle build
# does not leave Kotlin, R8, or AGP child processes behind.
timeout --signal=TERM --kill-after=30s "${timeout_minutes}m" \
  "${gradle_command[@]}" > >(tee "$log_file") 2>&1 &
gradle_pid=$!
set -e

heartbeat &
heartbeat_pid=$!

set +e
wait "$gradle_pid"
status=$?
set -e

system_snapshot | tee -a "$log_file.system"
elapsed=$(( $(date +%s) - started_at ))
if (( status == 124 )); then
  echo "::error title=$label build timed out::$label exceeded ${timeout_minutes} minutes after ${elapsed} seconds"
elif (( status == 137 )); then
  echo "::error title=$label build was killed::$label was killed after ${elapsed} seconds"
elif (( status != 0 )); then
  echo "::error title=$label build failed::$label failed with exit code $status after ${elapsed} seconds"
else
  echo "Finished $label build successfully in ${elapsed} seconds"
fi

exit "$status"
