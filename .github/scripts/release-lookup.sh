# Shared by the release workflow and its deterministic shell test. The caller
# provides GITHUB_REPOSITORY, tag, and the release_json variable.

release_lookup_matches() {
  gh api --paginate "repos/${GITHUB_REPOSITORY}/releases?per_page=100" |
    jq -s --arg tag "$tag" '[.[] | .[] | select(.tag_name == $tag)]'
}

refresh_release() {
  local matches
  local count
  if ! matches="$(release_lookup_matches)"; then
    echo "Unable to list GitHub releases while looking up ${tag}" >&2
    exit 1
  fi
  count="$(jq -r 'length' <<< "$matches")"
  if [[ "$count" != 1 ]]; then
    echo "Expected exactly one GitHub release tagged ${tag}, found ${count}" >&2
    exit 1
  fi
  release_json="$(jq -c '.[0]' <<< "$matches")"
}

release_exists() {
  local matches
  local count
  if ! matches="$(release_lookup_matches)"; then
    echo "Unable to list GitHub releases while looking up ${tag}" >&2
    exit 1
  fi
  count="$(jq -r 'length' <<< "$matches")"
  if [[ "$count" != 0 && "$count" != 1 ]]; then
    echo "Expected at most one GitHub release tagged ${tag}, found ${count}" >&2
    exit 1
  fi
  if [[ "$count" == 0 ]]; then
    return 1
  fi
  release_json="$(jq -c '.[0]' <<< "$matches")"
  return 0
}
