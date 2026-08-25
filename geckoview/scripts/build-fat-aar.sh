#!/usr/bin/env bash
set -euo pipefail

arm64_maven="${1:?ARM64 target.maven.zip is required}"
armv7_maven="${2:?ARMv7 target.maven.zip is required}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="${GECKO_SOURCE_DIR:-$repo_root/source-fat}"
source_url="$(sed -n '1p' "$repo_root/SOURCE_REVISION")"
source_revision="$(sed -n '2p' "$repo_root/SOURCE_REVISION")"
artifact_dir="$repo_root/artifacts"
patch_file="$repo_root/patches/0001-disable-android-hls.patch"
source_lock="$repo_root/SOURCE_LOCK.json"
source_cache_dir="${GECKO_SOURCE_CACHE_DIR:-${GECKO_SOURCE_SEED_DIR:-$repo_root/source-snapshot}}"
mozconfig="$source_dir/.mozconfig"
compile_recipe_version="$(tr -d '[:space:]' < "$repo_root/COMPILE_RECIPE_VERSION")"

[[ -f "$arm64_maven" ]] || { echo "Missing ARM64 Maven archive: $arm64_maven" >&2; exit 1; }
[[ -f "$armv7_maven" ]] || { echo "Missing ARMv7 Maven archive: $armv7_maven" >&2; exit 1; }
[[ "$source_revision" =~ ^[0-9a-f]{40}$ ]] || {
  echo "SOURCE_REVISION must contain a 40-character Mercurial revision" >&2
  exit 1
}
[[ -n "$compile_recipe_version" ]] || {
  echo "COMPILE_RECIPE_VERSION must not be empty" >&2
  exit 1
}

command -v python3 >/dev/null || { echo "Python 3 is required" >&2; exit 1; }
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

config_digest="$({
  sha256sum "$repo_root/SOURCE_REVISION" "$source_lock" \
    "$repo_root/COMPILE_RECIPE_VERSION" \
    "$repo_root/mozconfigs/common.mozconfig" \
    "$repo_root/mozconfigs/fat.mozconfig" "$patch_file" \
    "$arm64_maven" "$armv7_maven"
} | sha256sum | awk '{print $1}')"
artifact_name="xtra-geckoview-universal-safe-${source_revision:0:12}-${config_digest:0:12}"
artifact="$artifact_dir/$artifact_name.aar"
cache_dir="${GECKO_FAT_CACHE_DIR:-}"
cache_artifact="${cache_dir:+$cache_dir/$artifact_name.aar}"

mkdir -p "$artifact_dir"
if [[ -n "$cache_dir" && -f "$cache_artifact" ]]; then
  echo "fat_gecko_cache=hit compile_digest=$config_digest"
  "$repo_root/scripts/verify-fat-aar.sh" "$cache_artifact" safe "$source_revision" \
    "$config_digest" armeabi-v7a arm64-v8a
  cp "$cache_artifact" "$artifact"
  cp "${cache_artifact%.aar}.json" "${artifact%.aar}.json"
  exit 0
fi
echo "fat_gecko_cache=miss compile_digest=$config_digest"

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

cat "$repo_root/mozconfigs/common.mozconfig" "$repo_root/mozconfigs/fat.mozconfig" > "$mozconfig"
export MOZCONFIG="$mozconfig"
export MOZ_FETCHES_DIR="${MOZ_FETCHES_DIR:-$repo_root/fat-inputs}"
mkdir -p "$MOZ_FETCHES_DIR"
export MOZ_ANDROID_FAT_AAR_ARCHITECTURES="armeabi-v7a,arm64-v8a"
export MOZ_ANDROID_FAT_AAR_ARMEABI_V7A="$armv7_maven"
export MOZ_ANDROID_FAT_AAR_ARM64_V8A="$arm64_maven"

objdir="$(sed -n 's/^mk_add_options MOZ_OBJDIR=//p' "$mozconfig" | sed 's#@TOPSRCDIR@#'"$source_dir"'#')"
[[ -n "$objdir" ]] || { echo "Unable to determine Gecko object directory" >&2; exit 1; }

started="$SECONDS"
(
  cd "$source_dir"
  ./mach configure --disable-compile-environment
)
echo "timing phase=configure seconds=$((SECONDS - started))"
python3 "$repo_root/scripts/verify-config.py" "$objdir/config.status.json" safe

started="$SECONDS"
(
  cd "$source_dir"
  ./mach build
)
echo "timing phase=fat-build seconds=$((SECONDS - started))"

started="$SECONDS"
(
  cd "$source_dir"
  MOZ_AUTOMATION=1 ./mach android archive-geckoview
)
echo "timing phase=fat-package-archive seconds=$((SECONDS - started))"

mapfile -t aar_files < <(find "$objdir" -type f -path '*/mobile/android/geckoview/outputs/aar/*.aar' -print)
if [[ "${#aar_files[@]}" -ne 1 ]]; then
  printf 'Expected exactly one fat GeckoView AAR, found %s:\n' "${#aar_files[@]}" >&2
  printf '  %s\n' "${aar_files[@]}" >&2
  exit 1
fi

cp "${aar_files[0]}" "$artifact"
"$repo_root/scripts/verify-fat-aar.sh" "$artifact" safe "$source_revision" \
  "$config_digest" armeabi-v7a arm64-v8a

if [[ -n "$cache_dir" ]]; then
  mkdir -p "$cache_dir"
  cp "$artifact" "$cache_artifact"
  cp "${artifact%.aar}.json" "$cache_dir/$artifact_name.json"
fi
