#!/usr/bin/env bash
set -euo pipefail

tag="${1:?immutable R1 release tag is required}"
abi="${2:?ABI is required}"
output_dir="${3:?output directory is required}"
repository="${4:-${GITHUB_REPOSITORY:-thiscallnet/Xtra}}"

case "$abi" in
  arm64-v8a|armeabi-v7a|x86|x86_64) ;;
  *) echo "Unsupported R1 ABI: $abi" >&2; exit 2 ;;
esac
[[ "$tag" =~ ^geckoview-twitch-auth-r1-v[0-9]+(\.[0-9]+)*$ ]] || {
  echo "R1 release tag is not immutable/versioned: $tag" >&2
  exit 2
}
command -v gh >/dev/null || { echo "gh is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/r1-release-provenance.sh"
source_lock="$repo_root/SOURCE_LOCK.json"
release_prefix="xtra-geckoview-twitch-auth-r1-${abi}"
aar_name="${release_prefix}.aar"
maven_name="${release_prefix}.target.maven.zip"
manifest_name=xtra-geckoview-twitch-auth-r1-manifest.json
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

gh release download "$tag" --repo "$repository" \
  --pattern "$aar_name" \
  --pattern "$maven_name" \
  --pattern "$manifest_name" \
  --dir "$temporary_dir"

aar="$temporary_dir/$aar_name"
maven="$temporary_dir/$maven_name"
manifest="$temporary_dir/$manifest_name"
[[ -f "$aar" && -f "$maven" && -f "$manifest" ]] || {
  echo "R1 release is missing one or more required assets for $abi" >&2
  exit 1
}

jq -e --arg abi "$abi" \
  '.profile == "twitch-auth-radical-r1" and (.abis | index($abi)) and
   ([.artifacts[] | select(.abi == $abi)] | length == 1)' \
  "$manifest" >/dev/null || {
  echo "R1 release manifest does not contain exactly one $abi artifact" >&2
  exit 1
}

artifact_json="$(jq -er --arg abi "$abi" '.artifacts[] | select(.abi == $abi)' "$manifest")"
source_revision="$(jq -er '.sourceRevision' <<< "$artifact_json")"
source_git_revision="$(jq -er '.sourceGitRevision' <<< "$artifact_json")"
config_digest="$(jq -er '.configurationDigest' <<< "$artifact_json")"
expected_source_revision="$(jq -er '.revision' "$source_lock")"
expected_source_git_revision="$(jq -er '.sourceGitRevision' "$source_lock")"
expected_recipe="$(sed -n '1p' "$repo_root/COMPILE_RECIPE_VERSION")"
release_recipe="$(jq -er '.compileRecipeVersion' "$manifest")"
expected_qualified_commit="$(r1_release_qualified_commit "$manifest")"
expected_publication_commit="$(r1_release_publication_commit "$manifest")"
artifact_xtra_commit="$(jq -er '.xtraCommit' <<< "$artifact_json")"
jq -e --arg qualified "$expected_qualified_commit" \
  '(if has("qualifiedCommit") then .qualifiedCommit == $qualified else .xtraCommit == $qualified end) and
   (.artifacts | length == 4) and all(.artifacts[]; .xtraCommit == $qualified)' \
  "$manifest" >/dev/null || {
  echo "R1 aggregate qualified provenance mismatch" >&2
  exit 1
}
[[ "$release_recipe" == "$expected_recipe" ]] || {
  echo "R1 compile recipe version does not match the checked-in recipe" >&2
  exit 1
}
[[ "$source_revision" == "$expected_source_revision" ]] || {
  echo "R1 Mercurial revision does not match the pinned recipe" >&2
  exit 1
}
[[ "$source_git_revision" == "$expected_source_git_revision" ]] || {
  echo "R1 source Git revision does not match the pinned recipe" >&2
  exit 1
}
tag_commit="$(gh api "repos/$repository/commits/$tag" --jq '.sha')"
[[ "$tag_commit" == "$expected_publication_commit" ]] || {
  echo "R1 release tag $tag resolves to $tag_commit, expected publication commit $expected_publication_commit" >&2
  exit 1
}
[[ "$artifact_xtra_commit" == "$expected_qualified_commit" ]] || {
  echo "R1 qualified ABI artifact commit $artifact_xtra_commit does not match qualified commit $expected_qualified_commit" >&2
  exit 1
}

expected_sha256="$(jq -er '.aar.sha256' <<< "$artifact_json")"
actual_sha256="$(sha256sum "$aar" | awk '{print $1}')"
[[ "$actual_sha256" == "$expected_sha256" ]] || {
  echo "R1 AAR SHA-256 mismatch for $abi" >&2
  exit 1
}
expected_size="$(jq -er '.aar.size' <<< "$artifact_json")"
actual_size="$(stat -c '%s' "$aar")"
[[ "$actual_size" == "$expected_size" ]] || {
  echo "R1 AAR size mismatch for $abi" >&2
  exit 1
}
expected_maven_sha256="$(jq -er '.maven.sha256' <<< "$artifact_json")"
actual_maven_sha256="$(sha256sum "$maven" | awk '{print $1}')"
[[ "$actual_maven_sha256" == "$expected_maven_sha256" ]] || {
  echo "R1 Maven archive SHA-256 mismatch for $abi" >&2
  exit 1
}

expected_manifest="$temporary_dir/expected-aar.json"
observed_manifest="$temporary_dir/observed-aar.json"
jq -n --arg artifact "$aar_name" \
  --arg abi "$abi" \
  --arg profile "twitch-auth-radical-r1" \
  --arg source_revision "$source_revision" \
  --arg config_digest "$config_digest" \
  --arg sha256 "$expected_sha256" \
  --arg libxul_build_id "$(jq -er '.aar.libxulBuildId' <<< "$artifact_json")" \
  --arg maven "$maven_name" \
  --arg maven_sha256 "$expected_maven_sha256" \
  --argjson size "$expected_size" \
  --argjson libxul_size "$(jq -er '.aar.libxulSize' <<< "$artifact_json")" \
  --argjson omni_size "$(jq -er '.aar.omniSize' <<< "$artifact_json")" \
  '{artifact:$artifact,abi:$abi,profile:$profile,sourceRevision:$source_revision,
    configurationDigest:$config_digest,size:$size,sha256:$sha256,
    libxulSize:$libxul_size,libxulBuildId:$libxul_build_id,omniSize:$omni_size,
    mavenArchive:$maven,mavenArchiveSha256:$maven_sha256}' > "$expected_manifest"

mkdir -p "$output_dir"
"$repo_root/scripts/verify-aar.sh" "$aar" "$abi" twitch-auth-radical-r1 \
  "$source_revision" "$config_digest" "$maven" "$expected_manifest" "$observed_manifest"
cp "$aar" "$output_dir/$aar_name"
cp "$maven" "$output_dir/$maven_name"
cp "$manifest" "$output_dir/$manifest_name"
echo "Downloaded and verified immutable R1 GeckoView: $output_dir/$aar_name"
