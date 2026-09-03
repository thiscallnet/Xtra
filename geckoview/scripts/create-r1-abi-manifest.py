#!/usr/bin/env python3
"""Create the machine-readable identity for one R1 ABI build."""

import hashlib
import json
import os
import sys
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as input_file:
        for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    if len(sys.argv) != 5:
        print("usage: create-r1-abi-manifest.py AAR_MANIFEST APK ABI OUTPUT", file=sys.stderr)
        return 2

    aar_manifest_path = Path(sys.argv[1])
    apk_path = Path(sys.argv[2])
    abi = sys.argv[3]
    output_path = Path(sys.argv[4])
    if not aar_manifest_path.is_file():
        raise SystemExit(f"AAR manifest is missing: {aar_manifest_path}")
    if not apk_path.is_file():
        raise SystemExit(f"integration APK is missing: {apk_path}")

    aar = json.loads(aar_manifest_path.read_text(encoding="utf-8"))
    required = (
        "artifact", "profile", "sourceRevision", "configurationDigest", "size",
        "sha256", "libxulSize", "libxulBuildId", "omniSize",
    )
    missing = [name for name in required if name not in aar]
    if missing:
        raise SystemExit("AAR manifest is missing: " + ", ".join(missing))
    if abi != aar.get("abi"):
        raise SystemExit(f"AAR manifest ABI mismatch: expected {abi}, got {aar.get('abi')}")
    if aar.get("profile") != "twitch-auth-radical-r1":
        raise SystemExit(f"Unexpected R1 profile: {aar.get('profile')}")

    recipe_file = Path(os.environ["GECKO_RECIPE_VERSION_FILE"])
    result = {
        "abi": abi,
        "profile": aar["profile"],
        "sourceRevision": aar["sourceRevision"],
        "sourceGitRevision": os.environ["GECKO_SOURCE_GIT_REVISION"],
        "xtraCommit": os.environ["GITHUB_SHA"],
        "compileRecipeVersion": recipe_file.read_text(encoding="utf-8").strip(),
        "configurationDigest": aar["configurationDigest"],
        "aar": {
            "file": aar["artifact"],
            "sha256": aar["sha256"],
            "size": aar["size"],
            "libxulSize": aar["libxulSize"],
            "libxulBuildId": aar["libxulBuildId"],
            "omniSize": aar["omniSize"],
        },
        "integrationApk": {
            "file": apk_path.name,
            "sha256": sha256_file(apk_path),
            "size": apk_path.stat().st_size,
        },
        "status": "build-passed-runtime-not-tested",
    }
    if "mavenArchive" in aar and "mavenArchiveSha256" in aar:
        result["maven"] = {
            "file": aar["mavenArchive"],
            "sha256": aar["mavenArchiveSha256"],
        }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
