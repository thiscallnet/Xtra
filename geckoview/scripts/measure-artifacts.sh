#!/usr/bin/env bash
set -euo pipefail

artifact_id="${1:?artifact identity is required}"
aar="${2:?AAR path is required}"
apk="${3:?Xtra APK path or - is required}"
twitch_login="${4:?Twitch login result is required}"
output="${5:?measurement TSV path is required}"

[[ -f "$aar" ]] || { echo "Missing AAR: $aar" >&2; exit 1; }
if [[ "$apk" != "-" && ! -f "$apk" ]]; then
  echo "Missing Xtra APK: $apk" >&2
  exit 1
fi

command -v python3 >/dev/null || { echo "Python 3 is required" >&2; exit 1; }

mkdir -p "$(dirname "$output")"
python3 - "$artifact_id" "$aar" "$apk" "$twitch_login" "$output" <<'PY'
import os
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Optional

artifact_id, aar_path, apk_path, login, output_path = sys.argv[1:]


def clean_cell(value: str) -> str:
    return value.replace("\t", " ").replace("\r", " ").replace("\n", " ")


def zip_member_size(path: str, predicate) -> Optional[int]:
    with zipfile.ZipFile(path) as archive:
        sizes = [entry.file_size for entry in archive.infolist() if predicate(entry.filename)]
    if not sizes:
        return None
    return sum(sizes)


def size_or_dash(value: Optional[int]) -> str:
    return "-" if value is None else str(value)


def largest_members(path: str):
    with zipfile.ZipFile(path) as archive:
        members = [
            (entry.file_size, entry.compress_size, entry.filename)
            for entry in archive.infolist()
            if not entry.is_dir()
        ]
    return sorted(members, reverse=True)[:20]


aar_size = os.path.getsize(aar_path)
libxul_size = zip_member_size(aar_path, lambda name: name.endswith("/libxul.so"))
omni_size = zip_member_size(aar_path, lambda name: name == "assets/omni.ja")
apk_size = None if apk_path == "-" else os.path.getsize(apk_path)

artifact_id = clean_cell(artifact_id)
header = ["Build", "AAR", "Xtra APK", "libxul.so", "omni.ja", "Twitch login"]
row = [
    artifact_id,
    str(aar_size),
    size_or_dash(apk_size),
    size_or_dash(libxul_size),
    size_or_dash(omni_size),
    clean_cell(login),
]

path = Path(output_path)
rows = {}
if path.is_file():
    for line in path.read_text(encoding="utf-8").splitlines():
        cells = line.split("\t")
        if cells and cells[0] not in {"", "Build"}:
            rows[cells[0]] = cells
existing = rows.get(artifact_id)
if apk_path == "-" and existing and len(existing) >= 3 and existing[2] != "-":
    row[2] = existing[2]
if row[5] == "not-tested" and existing and len(existing) >= 6:
    previous_login = existing[5]
    if previous_login != "not-tested":
        row[5] = previous_login
        print(
            f"Preserved existing Twitch login result for {artifact_id}: {previous_login}",
            file=sys.stderr,
        )
rows[artifact_id] = row

content = "\t".join(header) + "\n"
content += "\n".join("\t".join(row) for row in rows.values()) + "\n"
path.parent.mkdir(parents=True, exist_ok=True)
with tempfile.NamedTemporaryFile(
    "w", encoding="utf-8", dir=path.parent, delete=False, prefix=f".{path.name}."
) as temporary:
    temporary.write(content)
    temporary_path = temporary.name
os.replace(temporary_path, path)

print("\t".join(header))
print("\t".join(row))
for label, path in [("AAR", aar_path), ("Xtra APK", apk_path)]:
    if path == "-":
        continue
    print(f"Largest {label} members (uncompressed / compressed bytes):")
    for uncompressed, compressed, name in largest_members(path):
        print(f"{uncompressed} / {compressed}  {name}")
PY
