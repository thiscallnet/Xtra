#!/usr/bin/env bash

r1_integration_directory_name() {
  printf 'xtra-geckoview-%s-twitch-auth-radical-r1-integration\n' "$1"
}

r1_integration_artifact_name() {
  local abi="$1"
  local preserved="${2:-false}"
  if [[ "$preserved" == true ]]; then
    printf 'xtra-geckoview-r1-preserved-%s-integration\n' "$abi"
  else
    r1_integration_directory_name "$abi"
  fi
}

r1_universal_artifact_name() {
  if [[ "${1:-false}" == true ]]; then
    printf 'xtra-geckoview-r1-preserved-universal\n'
  else
    printf 'xtra-geckoview-twitch-auth-r1-universal\n'
  fi
}
