#!/usr/bin/env bash
set -euo pipefail

source_run_id="${1:?qualified R1 source run ID is required}"
output_root="${2:-r1-preserved}"
geckoview_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

[[ "$source_run_id" =~ ^[0-9]+$ ]] || {
  echo "qualified R1 source run ID must be numeric: $source_run_id" >&2
  exit 2
}
[[ -n "${GH_TOKEN:-}" ]] || { echo "GH_TOKEN is required" >&2; exit 2; }
[[ -n "${GITHUB_REPOSITORY:-}" ]] || { echo "GITHUB_REPOSITORY is required" >&2; exit 2; }
[[ -n "${GITHUB_SHA:-}" ]] || { echo "GITHUB_SHA is required" >&2; exit 2; }
[[ -n "${GITHUB_RUN_ID:-}" ]] || { echo "GITHUB_RUN_ID is required" >&2; exit 2; }

source_run="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$source_run_id")"
jq -e '
  .status == "completed" and
  .conclusion == "success" and
  .event == "workflow_dispatch" and
  (.path | endswith(".github/workflows/custom-geckoview.yml"))
' <<< "$source_run" >/dev/null || {
  echo "source run is not a successful Custom GeckoView dispatch: $source_run_id" >&2
  exit 1
}
source_sha="$(jq -er '.head_sha' <<< "$source_run")"
expected_profile="twitch-auth-radical-r1"
expected_revision="$(jq -er '.revision' "$geckoview_root/SOURCE_LOCK.json")"
expected_source_git_revision="$(jq -er '.sourceGitRevision' "$geckoview_root/SOURCE_LOCK.json")"
expected_recipe="$(tr -d '\r\n' < "$geckoview_root/COMPILE_RECIPE_VERSION")"

rm -rf -- "$output_root"
mkdir -p -- "$output_root"

find_one() {
  local root="$1" name="$2" description="$3"
  mapfile -t matches < <(find "$root" -type f -name "$name" -print | sort)
  [[ "${#matches[@]}" -eq 1 ]] || {
    echo "expected exactly one $description; found ${#matches[@]}" >&2
    printf '  %s\n' "${matches[@]}" >&2
    exit 1
  }
  printf '%s\n' "${matches[0]}"
}

write_marker() {
  local directory="$1" artifact_name="$2"
  jq -n \
    --argjson qualified_run_id "$source_run_id" \
    --arg qualified_commit "$source_sha" \
    --argjson preservation_run_id "$GITHUB_RUN_ID" \
    --arg preservation_commit "$GITHUB_SHA" \
    --arg source_git_revision "$expected_source_git_revision" \
    --arg artifact_name "$artifact_name" \
    '{qualifiedRunId:$qualified_run_id,qualifiedCommit:$qualified_commit,
      preservationRunId:$preservation_run_id,preservationCommit:$preservation_commit,
      sourceGitRevision:$source_git_revision,artifactName:$artifact_name}' \
    > "$directory/r1-preservation-manifest.json"
}

preserve_abi() {
  local abi="$1"
  local original_name="xtra-geckoview-${abi}-${expected_profile}-integration"
  local preserved_name="xtra-geckoview-r1-preserved-${abi}-integration"
  local directory="$output_root/$preserved_name"
  mkdir -p -- "$directory"
  gh run download "$source_run_id" --repo "$GITHUB_REPOSITORY" \
    --name "$original_name" --dir "$directory"

  manifest="$(find_one "$directory" r1-abi-manifest.json "$abi R1 ABI manifest")"
  aar_name="$(jq -er '.aar.file' "$manifest")"
  aar_sha="$(jq -er '.aar.sha256' "$manifest")"
  maven_name="$(jq -er '.maven.file' "$manifest")"
  maven_sha="$(jq -er '.maven.sha256' "$manifest")"
  [[ "$(jq -er '.abi' "$manifest")" == "$abi" &&
      "$(jq -er '.profile' "$manifest")" == "$expected_profile" &&
      "$(jq -er '.sourceRevision' "$manifest")" == "$expected_revision" &&
      "$(jq -er '.sourceGitRevision' "$manifest")" == "$expected_source_git_revision" &&
      "$(jq -er '.xtraCommit' "$manifest")" == "$source_sha" &&
      "$(jq -er '.compileRecipeVersion' "$manifest")" == "$expected_recipe" ]] || {
    echo "qualified provenance mismatch for $abi" >&2
    exit 1
  }
  aar="$(find_one "$directory" "$aar_name" "$abi AAR")"
  maven="$(find_one "$directory" "$maven_name" "$abi Maven archive")"
  [[ "$(sha256sum "$aar" | awk '{print $1}')" == "$aar_sha" ]] || {
    echo "AAR SHA mismatch for $abi" >&2
    exit 1
  }
  [[ "$(sha256sum "$maven" | awk '{print $1}')" == "$maven_sha" ]] || {
    echo "Maven SHA mismatch for $abi" >&2
    exit 1
  }
  expected_manifest="$RUNNER_TEMP/preserve-expected-$abi.json"
  observed_manifest="$RUNNER_TEMP/preserve-observed-$abi.json"
  jq -n \
    --arg artifact "$aar_name" --arg abi "$abi" --arg profile "$expected_profile" \
    --arg source_revision "$(jq -er '.sourceRevision' "$manifest")" \
    --arg config_digest "$(jq -er '.configurationDigest' "$manifest")" \
    --arg sha256 "$aar_sha" --arg libxul_build_id "$(jq -er '.aar.libxulBuildId' "$manifest")" \
    --arg maven "$maven_name" --arg maven_sha256 "$maven_sha" \
    --argjson size "$(jq -er '.aar.size' "$manifest")" \
    --argjson libxul_size "$(jq -er '.aar.libxulSize' "$manifest")" \
    --argjson omni_size "$(jq -er '.aar.omniSize' "$manifest")" \
    '{artifact:$artifact,abi:$abi,profile:$profile,sourceRevision:$source_revision,
      configurationDigest:$config_digest,size:$size,sha256:$sha256,
      libxulSize:$libxul_size,libxulBuildId:$libxul_build_id,omniSize:$omni_size,
      mavenArchive:$maven,mavenArchiveSha256:$maven_sha256}' > "$expected_manifest"
  "$geckoview_root/scripts/verify-aar.sh" "$aar" "$abi" "$expected_profile" \
    "$expected_revision" "$(jq -er '.configurationDigest' "$manifest")" \
    "$maven" "$expected_manifest" "$observed_manifest" >/dev/null
  write_marker "$directory" "$preserved_name"
}

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  preserve_abi "$abi"
done

preserved_name="xtra-geckoview-r1-preserved-universal"
universal_dir="$output_root/$preserved_name"
mkdir -p -- "$universal_dir"
gh run download "$source_run_id" --repo "$GITHUB_REPOSITORY" \
  --name xtra-geckoview-twitch-auth-r1-universal --dir "$universal_dir"
universal_manifest="$(find_one "$universal_dir" xtra-geckoview-twitch-auth-r1-universal.json 'universal R1 manifest')"
universal_aar="$(find_one "$universal_dir" xtra-geckoview-twitch-auth-r1-universal.aar 'universal R1 AAR')"
find_one "$universal_dir" app-debug.apk 'qualified universal debug APK' >/dev/null
jq -e \
  --arg profile "$expected_profile" --arg revision "$expected_revision" \
  --arg source_git_revision "$expected_source_git_revision" --arg source_sha "$source_sha" \
  --arg recipe "$expected_recipe" \
  '.profile == $profile and .sourceRevision == $revision and
   .sourceGitRevision == $source_git_revision and .xtraCommit == $source_sha and
   .compileRecipeVersion == $recipe and
   (.abis | sort) == ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]' \
  "$universal_manifest" >/dev/null || {
  echo "qualified universal R1 provenance mismatch" >&2
  exit 1
}
universal_name="$(jq -er '.universal.file' "$universal_manifest")"
universal_sha="$(jq -er '.universal.sha256' "$universal_manifest")"
[[ "$(basename "$universal_aar")" == "$universal_name" ]] || {
  echo "universal AAR filename mismatch" >&2
  exit 1
}
[[ "$(sha256sum "$universal_aar" | awk '{print $1}')" == "$universal_sha" ]] || {
  echo "universal AAR SHA mismatch" >&2
  exit 1
}
"$geckoview_root/scripts/verify-fat-aar.sh" "$universal_aar" "$expected_profile" unknown unknown \
  arm64-v8a armeabi-v7a x86 x86_64 >/dev/null
write_marker "$universal_dir" "$preserved_name"

echo "preserved_qualified_run=$source_run_id"
echo "preserved_qualified_commit=$source_sha"
echo "preservation_run=$GITHUB_RUN_ID"
echo "preservation_root=$output_root"
