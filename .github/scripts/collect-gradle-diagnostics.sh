#!/usr/bin/env bash
set -u

output_dir="${CI_DIAGNOSTICS_DIR:-ci-diagnostics}"
gradle_user_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$output_dir"

{
  echo "# Gradle diagnostics"
  echo
  echo "- UTC time: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Job: ${GITHUB_JOB:-unknown}"
  echo "- Run: ${GITHUB_RUN_ID:-unknown}"
  echo "- Gradle JVM arguments: ${CI_GRADLE_JVM_ARGS:-not set}"
  echo
  echo '```text'
  free -h 2>&1 || true
  echo
  df -h 2>&1 || true
  echo
  nproc 2>&1 || true
  echo '```'
} > "$output_dir/summary.md"

{
  echo "Gradle version"
  ./gradlew --version 2>&1 || true
  echo
  echo "Gradle daemons"
  ./gradlew --status 2>&1 || true
} > "$output_dir/gradle-status.txt"

copy_file() {
  local source="$1"
  local relative="$2"
  local destination="$output_dir/$relative"
  mkdir -p "$(dirname "$destination")"
  cp "$source" "$destination" 2>/dev/null || true
}

for report_root in build/reports app/build/reports app/build/outputs/logs; do
  if [[ -d "$report_root" ]]; then
    while IFS= read -r -d '' report; do
      copy_file "$report" "$report"
    done < <(find "$report_root" -type f -size -25M -print0 2>/dev/null)
  fi
done

daemon_root="$gradle_user_home/daemon"
if [[ -d "$daemon_root" ]]; then
  while IFS= read -r -d '' daemon_log; do
    relative="${daemon_log#"$gradle_user_home/"}"
    copy_file "$daemon_log" "gradle/$relative"
  done < <(
    find "$daemon_root" -type f \
      \( -name '*.log' -o -name '*.out.log' \) \
      -size -25M -print0 2>/dev/null
  )
fi

if [[ -d ci-logs ]]; then
  find ci-logs -maxdepth 1 -type f -size -25M -print 2>/dev/null || true
fi
