#!/usr/bin/env bash
set -euo pipefail

artifact="${1:?AAR path is required}"
expected_abi="${2:?ABI is required}"
profile="${3:?profile is required}"
source_revision="${4:?source revision is required}"
config_digest="${5:?configuration digest is required}"
maven_archive="${6:-}"

case "$profile" in
  safe|nowebrtc) ;;
  *) echo "Unsupported profile: $profile" >&2; exit 2 ;;
esac

[[ -f "$artifact" ]] || { echo "Missing AAR: $artifact" >&2; exit 1; }
if [[ -n "$maven_archive" && ! -f "$maven_archive" ]]; then
  echo "Missing Maven archive: $maven_archive" >&2
  exit 1
fi
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python 3 is required" >&2; exit 1; }

mapfile -t native_abis < <(
  unzip -Z1 "$artifact" |
    sed -n 's#^jni/\([^/]*\)/.*#\1#p' |
    sort -u
)
if [[ "${#native_abis[@]}" -ne 1 || "${native_abis[0]}" != "$expected_abi" ]]; then
  printf 'AAR contains unexpected native ABIs (expected %s):\n' "$expected_abi" >&2
  printf '  %s\n' "${native_abis[@]}" >&2
  exit 1
fi

entries="$(unzip -Z1 "$artifact")"
if ! grep -qx 'assets/omni.ja' <<< "$entries"; then
  echo "AAR is missing Gecko's omni.ja" >&2
  exit 1
fi
if ! grep -qx "jni/$expected_abi/libxul.so" <<< "$entries"; then
  echo "AAR is missing libxul.so for $expected_abi" >&2
  exit 1
fi

size_bytes="$(stat -c '%s' "$artifact")"
digest="$(sha256sum "$artifact" | awk '{print $1}')"
if [[ -n "$maven_archive" ]]; then
  maven_digest="$(sha256sum "$maven_archive" | awk '{print $1}')"
fi
echo "AAR=$artifact"
echo "profile=$profile abi=$expected_abi size=$size_bytes sha256=$digest"
if [[ -n "$maven_archive" ]]; then
  echo "Maven archive=$maven_archive sha256=$maven_digest"
fi
echo "Largest AAR members (uncompressed / compressed bytes):"
unzip -lv "$artifact" |
  awk 'NR > 3 && $1 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ { print $1 " / " $3 "  " $8 }' |
  sort -nr | sed -n '1,20p'

manifest="${artifact%.aar}.json"
python3 - "$manifest" "$artifact" "$expected_abi" "$profile" "$source_revision" "$config_digest" "$size_bytes" "$digest" "${maven_archive:-}" "${maven_digest:-}" <<'PY'
import json
import os
import sys

path, artifact, abi, profile, revision, config, size, digest, maven_archive, maven_digest = sys.argv[1:]
manifest = {
    "artifact": os.path.basename(artifact),
    "abi": abi,
    "profile": profile,
    "sourceRevision": revision,
    "configurationDigest": config,
    "size": int(size),
    "sha256": digest,
}
if maven_archive:
    manifest["mavenArchive"] = os.path.basename(maven_archive)
    manifest["mavenArchiveSha256"] = maven_digest
with open(path, "w", encoding="utf-8") as output:
    json.dump(manifest, output, indent=2, sort_keys=True)
    output.write("\n")
PY
