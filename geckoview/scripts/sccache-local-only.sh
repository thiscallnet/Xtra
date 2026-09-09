#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${SCCACHE_REAL_BINARY:-}" ]]; then
  echo 'SCCACHE_REAL_BINARY must point to Mozilla sccache' >&2
  exit 2
fi

# GitHub-hosted jobs expose cache variables even when the workflow does not
# configure ghac.  Strip every ghac selector at the invocation boundary so a
# stacked-branch experiment uses only sccache's local disk cache.
exec env \
  -u SCCACHE \
  -u SCCACHE_GHA_ENABLED \
  -u SCCACHE_GHA_RW_MODE \
  -u SCCACHE_GHA_VERSION \
  -u SCCACHE_GHA_CACHE_URL \
  -u SCCACHE_GHA_RUNTIME_TOKEN \
  -u SCCACHE_GHA_CACHE_TO \
  -u SCCACHE_GHA_CACHE_FROM \
  -u ACTIONS_RESULTS_URL \
  -u ACTIONS_RUNTIME_TOKEN \
  "$SCCACHE_REAL_BINARY" "$@"
