#!/usr/bin/env bash
set -euo pipefail

source_run_id="${1:?qualified R1 source run ID is required}"
release_tag="${2:?immutable R1 release tag is required}"
artifact_run_id="${3:-$source_run_id}"
geckoview_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$geckoview_root/scripts/r1-promotion-layout.sh"

[[ "$source_run_id" =~ ^[0-9]+$ ]] || {
  echo "qualified R1 source run ID must be numeric: $source_run_id" >&2
  exit 2
}
[[ "$artifact_run_id" =~ ^[0-9]+$ ]] || {
  echo "R1 artifact run ID must be numeric: $artifact_run_id" >&2
  exit 2
}
[[ "$release_tag" =~ ^geckoview-twitch-auth-r1-v[0-9]+(\.[0-9]+)*$ ]] || {
  echo "release tag must match geckoview-twitch-auth-r1-vX[.Y...]" >&2
  exit 2
}
[[ -n "${GH_TOKEN:-}" ]] || { echo "GH_TOKEN is required" >&2; exit 2; }
[[ -n "${GITHUB_REPOSITORY:-}" ]] || { echo "GITHUB_REPOSITORY is required" >&2; exit 2; }
[[ -n "${GITHUB_SHA:-}" ]] || { echo "GITHUB_SHA is required" >&2; exit 2; }
[[ -n "${TWITCH_PUBLIC_CLIENT_ID:-}" ]] || {
  echo "TWITCH_PUBLIC_CLIENT_ID is required for the R1 release APK" >&2
  exit 2
}

if gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "Release already exists and is immutable: $release_tag" >&2
  exit 1
fi
if gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$release_tag" >/dev/null 2>&1; then
  echo "Tag already exists and is immutable: $release_tag" >&2
  exit 1
fi

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
artifact_run="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$artifact_run_id")"
jq -e '
  .status == "completed" and
  .conclusion == "success" and
  .event == "workflow_dispatch" and
  (.path | endswith(".github/workflows/custom-geckoview.yml"))
' <<< "$artifact_run" >/dev/null || {
  echo "artifact run is not a successful Custom GeckoView dispatch: $artifact_run_id" >&2
  exit 1
}
artifact_sha="$(jq -er '.head_sha' <<< "$artifact_run")"

expected_profile="twitch-auth-radical-r1"
expected_revision="$(jq -er '.revision' "$geckoview_root/SOURCE_LOCK.json")"
expected_source_git_revision="$(jq -er '.sourceGitRevision' "$geckoview_root/SOURCE_LOCK.json")"
expected_recipe="$(tr -d '\r\n' < "$geckoview_root/COMPILE_RECIPE_VERSION")"
input_root="$RUNNER_TEMP/r1-promotion-inputs"
release_assets="$RUNNER_TEMP/r1-release-assets"
rm -rf "$input_root" "$release_assets"
mkdir -p "$input_root" "$release_assets"
trap 'rm -rf "$input_root" "$release_assets"' EXIT

preserved_artifacts=false
if [[ "$artifact_run_id" != "$source_run_id" ]]; then
  preserved_artifacts=true
fi

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

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  artifact_name="$(r1_integration_artifact_name "$abi" "$preserved_artifacts")"
  artifact_dir="$input_root/$(r1_integration_directory_name "$abi")"
  mkdir -p "$artifact_dir"
  gh run download "$artifact_run_id" --repo "$GITHUB_REPOSITORY" \
    --name "$artifact_name" --dir "$artifact_dir"
  if [[ "$preserved_artifacts" == true ]]; then
    preservation_manifest="$(find_one "$artifact_dir" r1-preservation-manifest.json "$abi preservation manifest")"
    jq -e \
      --argjson qualified_run_id "$source_run_id" \
      --arg qualified_commit "$source_sha" \
      --argjson preservation_run_id "$artifact_run_id" \
      --arg preservation_commit "$artifact_sha" \
      --arg source_git_revision "$expected_source_git_revision" \
      --arg artifact_name "$artifact_name" \
      '.qualifiedRunId == $qualified_run_id and
       .qualifiedCommit == $qualified_commit and
       .preservationRunId == $preservation_run_id and
       .preservationCommit == $preservation_commit and
       .sourceGitRevision == $source_git_revision and
       .artifactName == $artifact_name' \
      "$preservation_manifest" >/dev/null || {
      echo "preservation provenance mismatch for $abi" >&2
      exit 1
    }
  fi
done
universal_dir="$input_root/xtra-geckoview-twitch-auth-r1-universal"
mkdir -p "$universal_dir"
universal_artifact_name="$(r1_universal_artifact_name "$preserved_artifacts")"
gh run download "$artifact_run_id" --repo "$GITHUB_REPOSITORY" \
  --name "$universal_artifact_name" --dir "$universal_dir"
if [[ "$preserved_artifacts" == true ]]; then
  preservation_manifest="$(find_one "$universal_dir" r1-preservation-manifest.json 'universal preservation manifest')"
  jq -e \
    --argjson qualified_run_id "$source_run_id" \
    --arg qualified_commit "$source_sha" \
    --argjson preservation_run_id "$artifact_run_id" \
    --arg preservation_commit "$artifact_sha" \
    --arg source_git_revision "$expected_source_git_revision" \
    --arg artifact_name "$universal_artifact_name" \
    '.qualifiedRunId == $qualified_run_id and
     .qualifiedCommit == $qualified_commit and
     .preservationRunId == $preservation_run_id and
     .preservationCommit == $preservation_commit and
     .sourceGitRevision == $source_git_revision and
     .artifactName == $artifact_name' \
    "$preservation_manifest" >/dev/null || {
    echo 'universal preservation provenance mismatch' >&2
    exit 1
  }
fi

if [[ "$preserved_artifacts" == false ]]; then
  python3 "$geckoview_root/scripts/collect-r1-aars.py" "$input_root" > "$RUNNER_TEMP/r1-aars.txt"
  mapfile -t resolved_aars < "$RUNNER_TEMP/r1-aars.txt"
  [[ "${#resolved_aars[@]}" -eq 4 ]] || {
    echo "source run did not provide four verified R1 AARs" >&2
    exit 1
  }
fi

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  artifact_name="$(r1_integration_directory_name "$abi")"
  artifact_dir="$input_root/$artifact_name"
  manifest="$(find_one "$artifact_dir" r1-abi-manifest.json "$abi R1 ABI manifest")"
  manifest_abi="$(jq -er '.abi' "$manifest")"
  profile="$(jq -er '.profile' "$manifest")"
  source_revision="$(jq -er '.sourceRevision' "$manifest")"
  source_git_revision="$(jq -er '.sourceGitRevision' "$manifest")"
  xtra_commit="$(jq -er '.xtraCommit' "$manifest")"
  recipe="$(jq -er '.compileRecipeVersion' "$manifest")"
  configuration_digest="$(jq -er '.configurationDigest' "$manifest")"
  aar_name="$(jq -er '.aar.file' "$manifest")"
  aar_sha="$(jq -er '.aar.sha256' "$manifest")"
  maven_name="$(jq -er '.maven.file' "$manifest")"
  maven_sha="$(jq -er '.maven.sha256' "$manifest")"
  aar="$(find_one "$artifact_dir" "$aar_name" "$abi AAR")"
  maven="$(find_one "$artifact_dir" "$maven_name" "$abi Maven archive")"
  actual_aar_sha="$(sha256sum "$aar" | awk '{print $1}')"
  actual_maven_sha="$(sha256sum "$maven" | awk '{print $1}')"
  [[ "$actual_aar_sha" == "$aar_sha" ]] || { echo "AAR SHA mismatch for $abi" >&2; exit 1; }
  [[ "$actual_maven_sha" == "$maven_sha" ]] || { echo "Maven SHA mismatch for $abi" >&2; exit 1; }
  [[ "$manifest_abi" == "$abi" && "$profile" == "$expected_profile" ]] || {
    echo "R1 identity mismatch for $abi" >&2
    exit 1
  }
  [[ "$source_revision" == "$expected_revision" &&
     "$source_git_revision" == "$expected_source_git_revision" &&
     "$xtra_commit" == "$source_sha" &&
     "$recipe" == "$expected_recipe" ]] || {
    echo "R1 provenance mismatch for $abi" >&2
    exit 1
  }
  expected_manifest="$RUNNER_TEMP/expected-aar-$abi.json"
  observed_manifest="$RUNNER_TEMP/observed-aar-$abi.json"
  jq -n \
    --arg artifact "$aar_name" --arg abi "$abi" --arg profile "$expected_profile" \
    --arg source_revision "$source_revision" --arg config_digest "$configuration_digest" \
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
    "$source_revision" "$configuration_digest" "$maven" "$expected_manifest" "$observed_manifest"
  cp "$aar" "$release_assets/xtra-geckoview-twitch-auth-r1-$abi.aar"
  cp "$maven" "$release_assets/xtra-geckoview-twitch-auth-r1-$abi.target.maven.zip"
  cp "$manifest" "$release_assets/xtra-geckoview-twitch-auth-r1-$abi.json"
done

universal_dir="$input_root/xtra-geckoview-twitch-auth-r1-universal"
universal_aar="$(find_one "$universal_dir" xtra-geckoview-twitch-auth-r1-universal.aar 'universal R1 AAR')"
universal_manifest="$(find_one "$universal_dir" xtra-geckoview-twitch-auth-r1-universal.json 'universal R1 manifest')"
qualified_debug_apk="$(find_one "$universal_dir" app-debug.apk 'qualified universal debug APK')"
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
[[ "$(basename "$universal_aar")" == "$universal_name" ]] || { echo "universal AAR filename mismatch" >&2; exit 1; }
[[ "$(sha256sum "$universal_aar" | awk '{print $1}')" == "$universal_sha" ]] || {
  echo "universal AAR SHA mismatch" >&2
  exit 1
}
"$geckoview_root/scripts/verify-fat-aar.sh" "$universal_aar" "$expected_profile" unknown unknown \
  arm64-v8a armeabi-v7a x86 x86_64
cp "$universal_aar" "$release_assets/xtra-geckoview-twitch-auth-r1-universal.aar"
cp "$universal_manifest" "$release_assets/xtra-geckoview-twitch-auth-r1-universal.json"
cp "$qualified_debug_apk" "$release_assets/xtra-geckoview-twitch-auth-r1-universal-debug.apk"

./gradlew --no-daemon assembleRelease --max-workers=1 \
  "-PxtraGeckoViewAar=$universal_aar" -PxtraReleaseAbiSplits=true \
  "-PtwitchPublicClientId=$TWITCH_PUBLIC_CLIENT_ID"
release_apk="$(find app/build/outputs/apk/release -type f -name 'app-arm64-v8a-release.apk' -print -quit)"
[[ -f "$release_apk" ]] || { echo "R1 release APK was not produced" >&2; exit 1; }
unzip -Z1 "$release_apk" | grep -qx 'lib/arm64-v8a/libxul.so' || {
  echo "R1 release APK does not contain the qualified custom GeckoView" >&2
  exit 1
}
universal_libxul_sha="$(unzip -p "$universal_aar" jni/arm64-v8a/libxul.so | sha256sum | awk '{print $1}')"
release_libxul_sha="$(unzip -p "$release_apk" lib/arm64-v8a/libxul.so | sha256sum | awk '{print $1}')"
[[ "$release_libxul_sha" == "$universal_libxul_sha" ]] || {
  echo "R1 release APK libxul does not match the qualified universal AAR" >&2
  exit 1
}
cp "$release_apk" "$release_assets/xtra-twitch-auth-r1-arm64-v8a-release.apk"

python3 - "$release_assets" "$source_run_id" "$source_sha" "$GITHUB_SHA" "$expected_profile" "$expected_revision" "$expected_source_git_revision" "$expected_recipe" <<'PY'
import json
import sys
from pathlib import Path

assets, source_run_id, source_sha, publication_sha, profile, revision, source_git, recipe = sys.argv[1:]
root = Path(assets)
abi_manifests = [json.loads((root / f"xtra-geckoview-twitch-auth-r1-{abi}.json").read_text())
                 for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")]
universal = json.loads((root / "xtra-geckoview-twitch-auth-r1-universal.json").read_text())
aggregate = {
    "profile": profile,
    "qualifiedRunId": int(source_run_id),
    "qualifiedCommit": source_sha,
    "publicationCommit": publication_sha,
    "sourceRevision": revision,
    "sourceGitRevision": source_git,
    "compileRecipeVersion": recipe,
    "abis": [item["abi"] for item in abi_manifests],
    "artifacts": abi_manifests,
    "universal": universal,
    "releaseApk": "xtra-twitch-auth-r1-arm64-v8a-release.apk",
}
(root / "xtra-geckoview-twitch-auth-r1-manifest.json").write_text(
    json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8"
)
PY

if gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1 || \
   gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$release_tag" >/dev/null 2>&1; then
  echo "Release or tag appeared during validation and is immutable: $release_tag" >&2
  exit 1
fi
gh release create "$release_tag" --repo "$GITHUB_REPOSITORY" \
  --target "$GITHUB_SHA" --latest=false --title "$release_tag" \
  --notes "Immutable GeckoView R1 ABI set for Twitch authentication. Qualified run: $source_run_id." \
  "$release_assets"/*
