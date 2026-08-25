#!/usr/bin/env bash
set -euo pipefail

artifact="${1:?fat AAR path is required}"
profile="${2:?profile is required}"
source_revision="${3:?source revision is required}"
config_digest="${4:?configuration digest is required}"
shift 4
expected_abis=("$@")

[[ -f "$artifact" ]] || { echo "Missing AAR: $artifact" >&2; exit 1; }
(( ${#expected_abis[@]} > 0 )) || { echo "At least one ABI is required" >&2; exit 2; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python 3 is required" >&2; exit 1; }

mapfile -t native_abis < <(
  unzip -Z1 "$artifact" |
    sed -n 's#^jni/\([^/]*\)/.*#\1#p' |
    sort -u
)
mapfile -t sorted_expected < <(printf '%s\n' "${expected_abis[@]}" | sort -u)
if [[ "${native_abis[*]}" != "${sorted_expected[*]}" ]]; then
  echo "AAR contains unexpected native ABIs" >&2
  printf 'Expected: %s\n' "${sorted_expected[*]}" >&2
  printf 'Actual:   %s\n' "${native_abis[*]}" >&2
  exit 1
fi

entries="$(unzip -Z1 "$artifact")"
grep -qx 'assets/omni.ja' <<< "$entries" || {
  echo "AAR is missing Gecko's omni.ja" >&2
  exit 1
}
for abi in "${expected_abis[@]}"; do
  grep -qx "jni/$abi/libxul.so" <<< "$entries" || {
    echo "AAR is missing libxul.so for $abi" >&2
    exit 1
  }
done

size_bytes="$(stat -c '%s' "$artifact")"
digest="$(sha256sum "$artifact" | awk '{print $1}')"
echo "AAR=$artifact"
echo "profile=$profile abis=${sorted_expected[*]} size=$size_bytes sha256=$digest"
echo "Largest AAR members (uncompressed / compressed bytes):"
unzip -lv "$artifact" |
  awk 'NR > 3 && $1 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ { print $1 " / " $3 "  " $8 }' |
  sort -nr | sed -n '1,20p'

manifest="${artifact%.aar}.json"
python3 - "$manifest" "$artifact" "$profile" "$source_revision" "$config_digest" "$size_bytes" "$digest" "${sorted_expected[*]}" <<'PY'
import json
import os
import sys

path, artifact, profile, revision, config, size, digest, abis = sys.argv[1:]
with open(path, "w", encoding="utf-8") as output:
    json.dump(
        {
            "artifact": os.path.basename(artifact),
            "abis": abis.split(),
            "profile": profile,
            "sourceRevision": revision,
            "configurationDigest": config,
            "size": int(size),
            "sha256": digest,
        },
        output,
        indent=2,
        sort_keys=True,
    )
    output.write("\n")
PY
