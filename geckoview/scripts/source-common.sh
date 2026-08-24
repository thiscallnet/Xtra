#!/usr/bin/env bash

# Shared source acquisition and patch-state helpers. The caller must define:
# repo_root, source_dir, source_cache_dir, source_mode, source_url,
# source_revision, source_archive_url, source_archive_sha256,
# source_git_revision, and patch_file.

apply_gecko_patch() {
  (
    cd "$source_dir"
    patch --batch "$@" -p1 < "$patch_file"
  )
}

source_revision_of() {
  hg --cwd "$1" log -r . -T '{node}\n' | tr -d '[:space:]'
}

validate_mercurial_source() {
  local checkout="$1"
  local actual
  actual="$(source_revision_of "$checkout")"
  if [[ "$actual" != "$source_revision" ]]; then
    echo "Gecko source revision mismatch: expected $source_revision, got $actual" >&2
    exit 1
  fi
  if [[ -n "$(hg --cwd "$checkout" status --modified --added --removed --deleted)" ]]; then
    echo "Gecko source seed has unexpected tracked local changes: $checkout" >&2
    exit 1
  fi
}

write_archive_marker() {
  python3 - "$source_cache_dir/.xtra-source-snapshot.json" "$source_revision" \
    "$source_git_revision" "$source_archive_sha256" <<'PY'
import json
import sys

path, hg_revision, git_revision, archive_sha256 = sys.argv[1:]
with open(path, "w", encoding="utf-8") as output:
    json.dump(
        {
            "archiveSha256": archive_sha256,
            "gitRevision": git_revision,
            "mercurialRevision": hg_revision,
        },
        output,
        sort_keys=True,
    )
    output.write("\n")
PY
}

source_archive_path() {
  printf '%s\n' "$source_cache_dir/xtra-gecko-${source_git_revision}.tar.gz"
}

validate_archive_sha() {
  local archive_path
  archive_path="$(source_archive_path)"
  [[ -f "$archive_path" ]] || {
    echo "cached Gecko source archive is missing: $archive_path" >&2
    exit 1
  }
  printf '%s  %s\n' "$source_archive_sha256" "$archive_path" | sha256sum --check --status || {
    echo "cached Gecko source archive SHA-256 does not match SOURCE_LOCK.json" >&2
    exit 1
  }
}

validate_archive_marker() {
  python3 - "$source_cache_dir/.xtra-source-snapshot.json" "$source_revision" \
    "$source_git_revision" "$source_archive_sha256" <<'PY'
import json
import sys

path, hg_revision, git_revision, archive_sha256 = sys.argv[1:]
with open(path, encoding="utf-8") as source:
    marker = json.load(source)
expected = {
    "archiveSha256": archive_sha256,
    "gitRevision": git_revision,
    "mercurialRevision": hg_revision,
}
if marker != expected:
    raise SystemExit("cached Gecko source snapshot provenance does not match SOURCE_LOCK.json")
PY
  validate_archive_sha
}

prepare_archive_source() {
  local started="$SECONDS"
  local archive_path
  local partial_archive
  archive_path="$(source_archive_path)"
  if [[ ! -f "$source_cache_dir/.xtra-source-snapshot.json" ]]; then
    mkdir -p "$source_cache_dir"
    if [[ -f "$archive_path" ]]; then
      echo "source_snapshot_cache=archive-without-marker"
      validate_archive_sha
    else
      echo "source_snapshot_cache=miss; downloading pinned archive"
      partial_archive="${archive_path}.partial"
      rm -f "$partial_archive"
      curl --fail --location --retry 5 --retry-all-errors --connect-timeout 30 \
        --max-time 1800 --silent --show-error --output "$partial_archive" "$source_archive_url"
      printf '%s  %s\n' "$source_archive_sha256" "$partial_archive" | sha256sum --check --status
      mv "$partial_archive" "$archive_path"
    fi
    write_archive_marker
  else
    echo "source_snapshot_cache=hit"
  fi
  validate_archive_marker
  if [[ -e "$source_dir" ]]; then
    echo "Gecko source checkout path already exists: $source_dir" >&2
    exit 1
  fi
  mkdir -p "$source_dir"
  tar --extract --gzip --file "$archive_path" --strip-components=1 --directory "$source_dir"
  echo "timing phase=source-fetch seconds=$((SECONDS - started))"
}

prepare_mercurial_seed_source() {
  local started="$SECONDS"
  command -v hg >/dev/null || { echo "Mercurial (hg) is required" >&2; exit 1; }
  if [[ ! -d "$source_cache_dir/.hg" ]]; then
    if [[ -e "$source_cache_dir" ]]; then
      echo "Gecko source seed exists but is not a Mercurial checkout: $source_cache_dir" >&2
      exit 1
    fi
    mkdir -p "$(dirname "$source_cache_dir")"
    echo "source_seed_cache=miss; cloning pinned revision once"
    hg clone --rev "$source_revision" "$source_url" "$source_cache_dir"
  else
    echo "source_seed_cache=hit"
  fi
  validate_mercurial_source "$source_cache_dir"
  touch "$source_cache_dir/.xtra-source-seed-ready"
  if [[ -e "$source_dir" ]]; then
    echo "Gecko source checkout path already exists: $source_dir" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$source_dir")"
  hg clone --rev "$source_revision" "$source_cache_dir" "$source_dir"
  validate_mercurial_source "$source_dir"
  echo "timing phase=source-fetch seconds=$((SECONDS - started))"
}

prepare_gecko_source() {
  case "$source_mode" in
    github-commit-archive) prepare_archive_source ;;
    mercurial-seed-cache) prepare_mercurial_seed_source ;;
    *) echo "Unsupported source acquisition mode: $source_mode" >&2; exit 1 ;;
  esac
}

ensure_clean_source_before_patch() {
  local dirty
  if [[ "$source_mode" == mercurial-seed-cache ]]; then
    if apply_gecko_patch --forward --dry-run >/dev/null 2>&1; then
      apply_gecko_patch --forward
    elif apply_gecko_patch --reverse --dry-run >/dev/null 2>&1; then
      apply_gecko_patch --reverse
      dirty="$(hg --cwd "$source_dir" status --modified --added --removed --deleted)"
      apply_gecko_patch --forward
      if [[ -n "$dirty" ]]; then
        echo "Gecko source checkout has unexpected tracked local changes" >&2
        printf '%s\n' "$dirty" >&2
        exit 1
      fi
    else
      echo "Pinned Gecko source does not accept the HLS patch" >&2
      exit 1
    fi
  elif apply_gecko_patch --forward --dry-run >/dev/null 2>&1; then
    apply_gecko_patch --forward
  else
    echo "Pinned Gecko source snapshot does not accept the HLS patch" >&2
    exit 1
  fi
}

assert_hls_patch_state() {
  python3 - "$source_dir/mobile/android/moz.configure" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
if not re.search(
    r'project_flag\(\s*"MOZ_ANDROID_HLS_SUPPORT".*?default=False,',
    text,
    re.DOTALL,
):
    raise SystemExit("HLS source patch was not applied as expected")
PY
}
