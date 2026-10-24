#!/usr/bin/env bash
set -euo pipefail

source_run_id="${1:?original R1 integration run ID is required}"
artifact_run_id="${2:-$source_run_id}"
[[ "$source_run_id" =~ ^[0-9]+$ && "$artifact_run_id" =~ ^[0-9]+$ ]] || exit 2
: "${GITHUB_REPOSITORY:?}" "${GITHUB_SHA:?}" "${GH_TOKEN:?}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$root/scripts/r1-promotion-layout.sh"
inputs=r1-requalification-inputs
mkdir "$inputs"

for run_id in "$source_run_id" "$artifact_run_id"; do
  gh api "repos/$GITHUB_REPOSITORY/actions/runs/$run_id" > "$inputs/run-$run_id.json"
  jq -e '.status == "completed" and .conclusion == "success" and
         .event == "workflow_dispatch" and
         (.path | endswith(".github/workflows/custom-geckoview.yml"))' \
    "$inputs/run-$run_id.json" >/dev/null || { echo 'Invalid R1 source run' >&2; exit 1; }
done
source_sha="$(jq -er '.head_sha' "$inputs/run-$source_run_id.json")"
artifact_sha="$(jq -er '.head_sha' "$inputs/run-$artifact_run_id.json")"
preserved=false
[[ "$artifact_run_id" == "$source_run_id" ]] || preserved=true
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  directory="$inputs/$(r1_integration_directory_name "$abi")"
  gh run download "$artifact_run_id" --repo "$GITHUB_REPOSITORY" \
    --name "$(r1_integration_artifact_name "$abi" "$preserved")" --dir "$directory"
done

python3 - "$inputs" "$source_run_id" "$source_sha" "$artifact_run_id" "$artifact_sha" <<'PY'
import hashlib, json, sys
from pathlib import Path
root, source_run, source_sha, artifact_run, artifact_sha = sys.argv[1:]
for manifest in Path(root).rglob('r1-abi-manifest.json'):
    data = json.loads(manifest.read_text())
    if data['xtraCommit'] != source_sha:
        raise SystemExit('R1 source commit mismatch')
    if artifact_run != source_run:
        marker = json.loads((manifest.parent / 'r1-preservation-manifest.json').read_text())
        expected = dict(qualifiedRunId=int(source_run), qualifiedCommit=source_sha,
                        preservationRunId=int(artifact_run), preservationCommit=artifact_sha,
                        sourceGitRevision=data['sourceGitRevision'],
                        artifactName=f"xtra-geckoview-r1-preserved-{data['abi']}-integration")
        if marker != expected:
            raise SystemExit('R1 preservation provenance mismatch')
    name = data['maven']['file']
    if Path(name).name != name:
        raise SystemExit('Invalid R1 Maven filename')
    matches = list(manifest.parent.rglob(name))
    if len(matches) != 1:
        raise SystemExit('Expected one R1 Maven archive')
    with matches[0].open('rb') as stream:
        sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    if sha != data['maven']['sha256']:
        raise SystemExit('R1 Maven SHA mismatch')
PY

python3 "$root/scripts/collect-r1-aars.py" "$inputs" > "$inputs/aars.txt"
mapfile -t aars < "$inputs/aars.txt"
[[ "${#aars[@]}" -eq 4 ]] || exit 1
mkdir universal
aar=universal/xtra-geckoview-twitch-auth-r1-universal.aar
manifest=universal/xtra-geckoview-twitch-auth-r1-universal.json
python3 "$root/scripts/combine-r1-aar.py" "$aar" "${aars[@]}"
python3 "$root/scripts/r1-universal.py" create "$inputs" "$aar" "$manifest" --expected-commit "$source_sha"
./gradlew --no-daemon :app:assembleDebug --max-workers=1 "-PxtraGeckoViewAar=$PWD/$aar"
apk=app/build/outputs/apk/debug/app-debug.apk
python3 "$root/scripts/r1-universal.py" verify "$aar" "$manifest" --apk "$apk" --expected-commit "$source_sha"

# These are new app integrations of unchanged, verified native builds. Keep
# their original native provenance alongside the current app commit.
python3 - "$inputs" "$apk" "$GITHUB_SHA" "$source_run_id" <<'PY'
import hashlib, json, sys
from pathlib import Path
root, apk_name, commit, source_run = sys.argv[1:]
apk = Path(apk_name)
with apk.open('rb') as stream:
    identity = dict(file=apk.name, sha256=hashlib.file_digest(stream, 'sha256').hexdigest(), size=apk.stat().st_size)
for path in Path(root).rglob('r1-abi-manifest.json'):
    data = json.loads(path.read_text())
    data.setdefault('nativeBuildXtraCommit', data['xtraCommit'])
    data.setdefault('nativeBuildRunId', int(source_run))
    data.update(xtraCommit=commit, integrationApk=identity, status='build-passed-runtime-not-tested')
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')
PY
python3 "$root/scripts/r1-universal.py" create "$inputs" "$aar" "$manifest" --expected-commit "$GITHUB_SHA"
python3 "$root/scripts/r1-universal.py" verify "$aar" "$manifest" --apk "$apk" --expected-commit "$GITHUB_SHA"
