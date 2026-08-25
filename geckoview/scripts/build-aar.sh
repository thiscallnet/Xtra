#!/usr/bin/env bash
set -euo pipefail

abi="${1:-arm64-v8a}"
profile="${2:-safe}"
case "$abi" in
  arm64-v8a|armeabi-v7a) ;;
  *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
esac
case "$profile" in
  safe|nowebrtc) ;;
  *) echo "Unsupported profile: $profile" >&2; exit 2 ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="${GECKO_SOURCE_DIR:-$repo_root/source}"
source_url="$(sed -n '1p' "$repo_root/SOURCE_REVISION")"
source_revision="$(sed -n '2p' "$repo_root/SOURCE_REVISION")"
artifact_dir="$repo_root/artifacts"
mozconfig="$source_dir/.mozconfig"
patch_file="$repo_root/patches/0001-disable-android-hls.patch"
source_lock="$repo_root/SOURCE_LOCK.json"
source_cache_dir="${GECKO_SOURCE_CACHE_DIR:-${GECKO_SOURCE_SEED_DIR:-$repo_root/source-snapshot}}"
compile_recipe_version="$(tr -d '[:space:]' < "$repo_root/COMPILE_RECIPE_VERSION")"
config_files=(
  "$repo_root/SOURCE_REVISION"
  "$source_lock"
  "$repo_root/COMPILE_RECIPE_VERSION"
  "$repo_root/mozconfigs/common.mozconfig"
  "$repo_root/mozconfigs/${abi}-${profile}.mozconfig"
  "$patch_file"
)

if [[ -z "$source_url" || -z "$source_revision" ]]; then
  echo "SOURCE_REVISION must contain a source URL and revision" >&2
  exit 1
fi
if [[ ! "$source_revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "SOURCE_REVISION must contain a 40-character Mercurial revision" >&2
  exit 1
fi
if [[ -z "$compile_recipe_version" ]]; then
  echo "COMPILE_RECIPE_VERSION must not be empty" >&2
  exit 1
fi

command -v python3 >/dev/null || { echo "Python 3 is required" >&2; exit 1; }
command -v sccache >/dev/null || { echo "sccache is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v patch >/dev/null || { echo "patch is required" >&2; exit 1; }

mapfile -t source_lock_values < <(python3 - "$source_lock" <<'PY'
import json
import sys

lock = json.loads(open(sys.argv[1], encoding="utf-8").read())
for key in ("acquisition", "archiveUrl", "archiveSha256", "sourceGitRevision"):
    print(lock.get(key) or "")
PY
)
source_mode="${source_lock_values[0]}"
source_archive_url="${source_lock_values[1]}"
source_archive_sha256="${source_lock_values[2]}"
source_git_revision="${source_lock_values[3]}"
source "$repo_root/scripts/source-common.sh"

case "$source_mode" in
  github-commit-archive)
    command -v curl >/dev/null || { echo "curl is required for archive acquisition" >&2; exit 1; }
    command -v tar >/dev/null || { echo "tar is required for archive acquisition" >&2; exit 1; }
    ;;
  mercurial-seed-cache)
    command -v hg >/dev/null || { echo "Mercurial (hg) is required" >&2; exit 1; }
    ;;
  *) echo "Unsupported source acquisition mode: $source_mode" >&2; exit 1 ;;
esac

python3 "$repo_root/scripts/validate-recipe.py" >/dev/null
export MOZBUILD_STATE_PATH="${MOZBUILD_STATE_PATH:-${HOME:?}/.mozbuild}"
export SCCACHE_DIR="${SCCACHE_DIR:-$repo_root/.sccache}"
mkdir -p "$MOZBUILD_STATE_PATH" "$SCCACHE_DIR" "$artifact_dir"

config_digest="$({
  for config_file in "${config_files[@]}"; do
    sha256sum "$config_file" | awk '{print $1}'
  done
} | sha256sum | awk '{print $1}')"
artifact_name="xtra-geckoview-${abi}-${profile}-${source_revision:0:12}-${config_digest:0:12}"
artifact="$artifact_dir/$artifact_name.aar"
maven_archive="$artifact_dir/$artifact_name.target.maven.zip"
cache_dir="${GECKO_COMPILED_CACHE_DIR:-}"
cache_artifact="${cache_dir:+$cache_dir/$artifact_name.aar}"
cache_maven="${cache_dir:+$cache_dir/$artifact_name.target.maven.zip}"

if [[ "${GECKO_BOOTSTRAP_ONLY:-0}" == "1" ]]; then
  prepare_gecko_source
  ensure_clean_source_before_patch
  assert_hls_patch_state

  started="$SECONDS"
  (
    cd "$source_dir"
    ./mach --no-interactive bootstrap --application-choice="GeckoView/Firefox for Android"
  )
  echo "timing phase=bootstrap seconds=$((SECONDS - started))"
  exit 0
fi

if [[ -n "$cache_dir" && -f "$cache_artifact" && -f "$cache_maven" ]]; then
  echo "compiled_gecko_cache=hit abi=$abi profile=$profile compile_digest=$config_digest"
  "$repo_root/scripts/verify-aar.sh" "$cache_artifact" "$abi" "$profile" \
    "$source_revision" "$config_digest" "$cache_maven"
  cp "$cache_artifact" "$artifact"
  cp "$cache_maven" "$maven_archive"
  cp "${cache_artifact%.aar}.json" "${artifact%.aar}.json"
  sccache --show-stats
  exit 0
fi
echo "compiled_gecko_cache=miss abi=$abi profile=$profile compile_digest=$config_digest"

prepare_gecko_source
ensure_clean_source_before_patch
assert_hls_patch_state

if [[ "${GECKO_SKIP_BOOTSTRAP:-0}" == "1" ]]; then
  echo "timing phase=bootstrap skipped"
else
  started="$SECONDS"
  (
    cd "$source_dir"
    ./mach --no-interactive bootstrap --application-choice="GeckoView/Firefox for Android"
  )
  echo "timing phase=bootstrap seconds=$((SECONDS - started))"
fi

cat "$repo_root/mozconfigs/common.mozconfig" \
  "$repo_root/mozconfigs/${abi}-${profile}.mozconfig" > "$mozconfig"
export MOZCONFIG="$mozconfig"

objdir="$(sed -n 's/^mk_add_options MOZ_OBJDIR=//p' "$mozconfig" | sed 's#@TOPSRCDIR@#'"$source_dir"'#')"
if [[ -z "$objdir" ]]; then
  echo "Unable to determine Gecko object directory" >&2
  exit 1
fi

started="$SECONDS"
(
  cd "$source_dir"
  ./mach configure
)
echo "timing phase=configure seconds=$((SECONDS - started))"
python3 "$repo_root/scripts/verify-config.py" "$objdir/config.status.json" "$profile"

started="$SECONDS"
(
  cd "$source_dir"
  ./mach build
)
echo "timing phase=native-build seconds=$((SECONDS - started))"

started="$SECONDS"
(
  cd "$source_dir"
  ./mach package
  MOZ_AUTOMATION=1 ./mach android archive-geckoview
)
echo "timing phase=package-archive seconds=$((SECONDS - started))"

mapfile -t aar_files < <(find "$objdir" -type f -path '*/mobile/android/geckoview/outputs/aar/*.aar' -print)
if [[ "${#aar_files[@]}" -ne 1 ]]; then
  printf 'Expected exactly one GeckoView AAR, found %s:\n' "${#aar_files[@]}" >&2
  printf '  %s\n' "${aar_files[@]}" >&2
  exit 1
fi

target_maven_zip="$objdir/gradle/target.maven.zip"
if [[ ! -f "$target_maven_zip" ]]; then
  echo "GeckoView archive did not produce $target_maven_zip" >&2
  exit 1
fi
cp "${aar_files[0]}" "$artifact"
cp "$target_maven_zip" "$maven_archive"
"$repo_root/scripts/verify-aar.sh" "$artifact" "$abi" "$profile" \
  "$source_revision" "$config_digest" "$maven_archive"

if [[ -n "$cache_dir" ]]; then
  mkdir -p "$cache_dir"
  cp "$artifact" "$cache_artifact"
  cp "$maven_archive" "$cache_maven"
  cp "${artifact%.aar}.json" "$cache_dir/$artifact_name.json"
fi
sccache --show-stats
