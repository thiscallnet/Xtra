#!/usr/bin/env bash
set -euo pipefail
tag="${1:?immutable R1 release tag is required}"
output_dir="${2:?output directory is required}"
repo="${3:-${GITHUB_REPOSITORY:-thiscallnet/Xtra}}"
[[ "$tag" =~ ^geckoview-twitch-auth-r1-v[0-9]+(\.[0-9]+)*$ ]] || exit 2
command -v gh >/dev/null; command -v jq >/dev/null; command -v sha256sum >/dev/null
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$root/scripts/r1-release-provenance.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
name=xtra-geckoview-twitch-auth-r1-universal
aggregate_name=xtra-geckoview-twitch-auth-r1-manifest.json
gh release download "$tag" --repo "$repo" --pattern "$name.aar" --pattern "$name.json" --pattern "$aggregate_name" --dir "$tmp"
manifest="$tmp/$name.json"; aar="$tmp/$name.aar"
aggregate="$tmp/$aggregate_name"
[[ -f "$aar" && -f "$manifest" && -f "$aggregate" ]] || { echo 'R1 universal assets are incomplete' >&2; exit 1; }
expected="$(jq -er '.universal.sha256' "$manifest")"
actual="$(sha256sum "$aar" | awk '{print $1}')"
[[ "$actual" == "$expected" ]] || { echo 'R1 universal AAR SHA-256 mismatch' >&2; exit 1; }
expected_recipe="$(tr -d '[:space:]' < "$root/COMPILE_RECIPE_VERSION")"
[[ "$(jq -er '.compileRecipeVersion' "$manifest")" == "$expected_recipe" ]] || exit 1
[[ "$(jq -er '.profile' "$manifest")" == twitch-auth-radical-r1 ]] || exit 1
expected_source_revision="$(jq -er '.revision' "$root/SOURCE_LOCK.json")"
expected_source_git_revision="$(jq -er '.sourceGitRevision' "$root/SOURCE_LOCK.json")"
[[ "$(jq -er '.sourceRevision' "$manifest")" == "$expected_source_revision" ]] || {
  echo 'R1 universal source revision mismatch' >&2
  exit 1
}
[[ "$(jq -er '.sourceGitRevision' "$manifest")" == "$expected_source_git_revision" ]] || {
  echo 'R1 universal source Git revision mismatch' >&2
  exit 1
}
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  jq -e --arg abi "$abi" '.abis | index($abi)' "$manifest" >/dev/null || { echo "R1 release missing $abi" >&2; exit 1; }
done
qualified_commit="$(r1_release_qualified_commit "$aggregate")"
publication_commit="$(r1_release_publication_commit "$aggregate")"
universal_qualified_commit="$(jq -er '.xtraCommit' "$manifest")"
[[ "$universal_qualified_commit" == "$qualified_commit" ]] || {
  echo 'R1 universal qualified commit mismatch' >&2
  exit 1
}
jq -e --arg qualified "$qualified_commit" --arg profile twitch-auth-radical-r1 \
   '.profile == $profile and
   (if has("qualifiedCommit") then .qualifiedCommit == $qualified else .xtraCommit == $qualified end) and
   (.artifacts | length == 4) and all(.artifacts[]; .xtraCommit == $qualified) and
   (if has("universal") then .universal.xtraCommit == $qualified else true end)' "$aggregate" >/dev/null || {
  echo 'R1 aggregate qualified provenance mismatch' >&2
  exit 1
}
tag_commit="$(gh api "repos/$repo/commits/$tag" --jq .sha)"
[[ "$tag_commit" == "$publication_commit" ]] || {
  echo "R1 tag resolves to $tag_commit, expected publication commit $publication_commit" >&2
  exit 1
}
mkdir -p "$output_dir"; cp "$aar" "$output_dir/"; cp "$manifest" "$output_dir/"
echo "verified_r1_universal=$output_dir/$name.aar sha256=$actual"
