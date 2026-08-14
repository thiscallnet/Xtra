#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/release-lookup.sh"

GITHUB_REPOSITORY=thiscallnet/Xtra
tag=v2.58.5-build.173
release_json=""
GH_FIXTURE=""

release_fixture() {
  printf '[{"tag_name":"%s","draft":%s,"assets":[{"name":"app-release.apk","digest":"sha256:apk"},{"name":"xtra-release-metadata.json"}]}]\n' \
    "$tag" "$1"
}

gh() {
  if [[ "${1:-}" != api || "${2:-}" != --paginate ]]; then
    echo "unexpected gh invocation: $*" >&2
    return 1
  fi
  case "$GH_FIXTURE" in
    fresh-draft)
      printf '[]\n'
      release_fixture true
      ;;
    existing-draft)
      release_fixture true
      ;;
    published)
      release_fixture false
      ;;
    missing)
      printf '[]\n'
      ;;
    duplicate)
      release_fixture true
      release_fixture false
      ;;
    *)
      echo "unknown fixture: $GH_FIXTURE" >&2
      return 1
      ;;
  esac
}

assert_refresh() {
  local fixture="$1"
  local expected_draft="$2"
  GH_FIXTURE="$fixture"
  release_json=""
  refresh_release
  [[ "$(jq -r '.tag_name' <<< "$release_json")" == "$tag" ]]
  [[ "$(jq -r '.draft' <<< "$release_json")" == "$expected_draft" ]]
  [[ "$(jq -r '.assets | length' <<< "$release_json")" == 2 ]]
}

assert_refresh fresh-draft true
assert_refresh existing-draft true
assert_refresh published false

GH_FIXTURE=published
release_json=""
release_exists
[[ "$(jq -r '.draft' <<< "$release_json")" == false ]]

GH_FIXTURE=missing
if (refresh_release); then
  echo "refresh_release unexpectedly accepted a missing release" >&2
  exit 1
fi

GH_FIXTURE=duplicate
if (refresh_release); then
  echo "refresh_release unexpectedly accepted duplicate releases" >&2
  exit 1
fi

echo "release lookup fixtures passed"
