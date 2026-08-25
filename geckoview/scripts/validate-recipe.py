#!/usr/bin/env python3
"""Fast, network-free validation for the custom GeckoView recipe."""

import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    revision_lines = [line.strip() for line in (ROOT / "SOURCE_REVISION").read_text().splitlines()]
    if len(revision_lines) != 2 or not re.fullmatch(r"https://.+", revision_lines[0]):
        raise SystemExit("SOURCE_REVISION must contain a repository URL and revision")
    repository, revision = revision_lines
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise SystemExit("SOURCE_REVISION must contain a 40-character lowercase revision")

    lock = json.loads((ROOT / "SOURCE_LOCK.json").read_text(encoding="utf-8"))
    if lock.get("repository") != repository or lock.get("revision") != revision:
        raise SystemExit("SOURCE_LOCK.json does not match SOURCE_REVISION")
    if lock.get("acquisition") != "github-commit-archive":
        raise SystemExit("SOURCE_LOCK.json has an unsupported acquisition mode")
    archive_url = lock.get("archiveUrl")
    archive_sha256 = lock.get("archiveSha256")
    git_revision = lock.get("sourceGitRevision")
    if not isinstance(archive_url, str) or not archive_url.startswith(
        "https://github.com/mozilla-firefox/firefox/archive/"
    ):
        raise SystemExit("SOURCE_LOCK.json archiveUrl is not the official Firefox mirror")
    if not isinstance(archive_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", archive_sha256):
        raise SystemExit("SOURCE_LOCK.json archiveSha256 must be a 64-character lowercase SHA-256")
    if not isinstance(git_revision, str) or not re.fullmatch(r"[0-9a-f]{40}", git_revision):
        raise SystemExit("SOURCE_LOCK.json sourceGitRevision must be a 40-character lowercase revision")
    expected_archive_url = (
        "https://github.com/mozilla-firefox/firefox/archive/"
        f"{git_revision}.tar.gz"
    )
    if archive_url != expected_archive_url:
        raise SystemExit("SOURCE_LOCK.json archiveUrl does not match sourceGitRevision")

    patch = ROOT / "patches/0001-disable-android-hls.patch"
    if lock.get("patchSha256") != sha256(patch):
        raise SystemExit("SOURCE_LOCK.json patchSha256 does not match the HLS patch")

    required = [
        "COMPILE_RECIPE_VERSION",
        "mozconfigs/common.mozconfig",
        "mozconfigs/fat.mozconfig",
        "mozconfigs/arm64-v8a-safe.mozconfig",
        "mozconfigs/armeabi-v7a-safe.mozconfig",
        "mozconfigs/arm64-v8a-nowebrtc.mozconfig",
        "patches/0001-disable-android-hls.patch",
        "scripts/build-aar.sh",
        "scripts/build-fat-aar.sh",
        "scripts/source-common.sh",
        "scripts/validate-recipe.py",
        "scripts/verify-aar.sh",
        "scripts/verify-config.py",
        "scripts/verify-fat-aar.sh",
    ]
    missing = [path for path in required if not (ROOT / path).is_file()]
    if missing:
        raise SystemExit("missing recipe files: " + ", ".join(missing))

    print(f"validated recipe revision={revision} acquisition={lock['acquisition']}")


if __name__ == "__main__":
    main()
