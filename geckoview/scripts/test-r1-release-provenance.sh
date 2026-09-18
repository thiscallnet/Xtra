#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/r1-release-provenance.sh"

temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
promoted_manifest="$temporary_dir/promoted.json"
legacy_manifest="$temporary_dir/legacy.json"

jq -n \
  --arg qualified "qualified-commit" \
  --arg publication "publication-commit" \
  '{qualifiedCommit:$qualified,publicationCommit:$publication,
    profile:"twitch-auth-radical-r1",
    artifacts: [
      {abi:"arm64-v8a",xtraCommit:$qualified},
      {abi:"armeabi-v7a",xtraCommit:$qualified},
      {abi:"x86",xtraCommit:$qualified},
      {abi:"x86_64",xtraCommit:$qualified}
    ],
    universal:{xtraCommit:$qualified}}' > "$promoted_manifest"
jq -n --arg commit "same-commit" '{xtraCommit:$commit}' > "$legacy_manifest"

[[ "$(r1_release_qualified_commit "$promoted_manifest")" == qualified-commit ]]
[[ "$(r1_release_publication_commit "$promoted_manifest")" == publication-commit ]]
jq -e --arg qualified qualified-commit \
  '.profile == "twitch-auth-radical-r1" and
   (if has("qualifiedCommit") then .qualifiedCommit == $qualified else .xtraCommit == $qualified end) and
   (.artifacts | length == 4) and all(.artifacts[]; .xtraCommit == $qualified) and
   (if has("universal") then .universal.xtraCommit == $qualified else true end)' \
  "$promoted_manifest" >/dev/null
[[ "$(r1_release_qualified_commit "$legacy_manifest")" == same-commit ]]
[[ "$(r1_release_publication_commit "$legacy_manifest")" == same-commit ]]
echo "promoted R1 release provenance test passed"
