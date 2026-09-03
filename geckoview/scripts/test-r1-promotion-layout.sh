#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/r1-promotion-layout.sh"

root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  download_name="$(r1_integration_artifact_name "$abi" true)"
  directory_name="$(r1_integration_directory_name "$abi")"
  [[ "$download_name" == "xtra-geckoview-r1-preserved-${abi}-integration" ]]
  [[ "$directory_name" == "xtra-geckoview-${abi}-twitch-auth-radical-r1-integration" ]]
  mkdir -p "$root/$directory_name"
  touch "$root/$directory_name/r1-preservation-manifest.json"
  [[ -f "$root/$directory_name/r1-preservation-manifest.json" ]]
done

[[ "$(r1_universal_artifact_name true)" == xtra-geckoview-r1-preserved-universal ]]
[[ "$(r1_universal_artifact_name false)" == xtra-geckoview-twitch-auth-r1-universal ]]
echo "preserved R1 promotion layout test passed"
