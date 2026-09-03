#!/usr/bin/env python3
"""Fast, network-free validation for the custom GeckoView recipe."""

import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ATTRIBUTES = ROOT.parent / ".gitattributes"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_unified_patch(path: Path) -> None:
    hunk_re = re.compile(
        r"^@@ -\d+(?:,(?P<old>\d+))? \+\d+(?:,(?P<new>\d+))? @@"
    )
    active = None

    def finish_hunk() -> None:
        nonlocal active
        if active is None:
            return
        if (active["old_seen"], active["new_seen"]) != (
            active["old_expected"],
            active["new_expected"],
        ):
            raise SystemExit(
                f"{path.name}: malformed hunk at line {active['line']}: "
                f"expected {active['old_expected']}/{active['new_expected']} "
                f"old/new lines, saw {active['old_seen']}/{active['new_seen']}"
            )
        active = None

    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = hunk_re.match(line)
        if match:
            finish_hunk()
            active = {
                "line": line_number,
                "old_expected": int(match.group("old") or 1),
                "new_expected": int(match.group("new") or 1),
                "old_seen": 0,
                "new_seen": 0,
            }
            continue

        if line.startswith("diff --git "):
            finish_hunk()
            continue
        if active is None:
            continue
        if line.startswith("\\"):
            continue
        if not line or line[0] not in " +-":
            raise SystemExit(
                f"{path.name}: malformed hunk at line {active['line']}: "
                f"unexpected line {line_number}"
            )
        active["old_seen"] += line[0] in " -"
        active["new_seen"] += line[0] in " +"
        if active["old_seen"] > active["old_expected"] or active["new_seen"] > active["new_expected"]:
            raise SystemExit(
                f"{path.name}: hunk at line {active['line']} exceeds its line counts"
            )

    finish_hunk()


def main() -> None:
    if not ATTRIBUTES.is_file() or "geckoview/** text eol=lf" not in ATTRIBUTES.read_text(
        encoding="utf-8"
    ).splitlines():
        raise SystemExit(".gitattributes must enforce LF for the Gecko recipe")

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
    no_webrtc_test_patch = ROOT / "patches/0002-exclude-webrtc-dependent-android-test.patch"
    if lock.get("noWebrtcTestPatchSha256") != sha256(no_webrtc_test_patch):
        raise SystemExit(
            "SOURCE_LOCK.json noWebrtcTestPatchSha256 does not match the no-WebRTC test patch"
        )
    twitch_auth_lite_patch = ROOT / "patches/0003-twitch-auth-lite-build-cuts.patch"
    if lock.get("twitchAuthLitePatchSha256") != sha256(twitch_auth_lite_patch):
        raise SystemExit(
            "SOURCE_LOCK.json twitchAuthLitePatchSha256 does not match the Twitch auth-lite patch"
        )
    for recipe_patch in (patch, no_webrtc_test_patch, twitch_auth_lite_patch):
        validate_unified_patch(recipe_patch)
    no_webrtc_test_text = no_webrtc_test_patch.read_text(encoding="utf-8")
    for fragment in (
        "deleted file mode 100644",
        "mobile/android/geckoview/src/androidTest/java/org/mozilla/geckoview/test/VideoCaptureTest.kt",
    ):
        if fragment not in no_webrtc_test_text:
            raise SystemExit(
                "no-WebRTC test patch is missing its deterministic test deletion: "
                + fragment
            )

    required = [
        "COMPILE_RECIPE_VERSION",
        "RUST_TOOLCHAIN_VERSION",
        "mozconfigs/common.mozconfig",
        "mozconfigs/fat.mozconfig",
        "mozconfigs/arm64-v8a-safe.mozconfig",
        "mozconfigs/armeabi-v7a-safe.mozconfig",
        "mozconfigs/arm64-v8a-nowebrtc.mozconfig",
        "mozconfigs/x86_64-auth.mozconfig",
        "mozconfigs/x86_64-nowebrtc.mozconfig",
        "mozconfigs/x86_64-nowebspeech.mozconfig",
        "mozconfigs/x86_64-minimal.mozconfig",
        "mozconfigs/x86_64-twitch-auth-radical-r1.mozconfig",
        "mozconfigs/twitch-auth-radical-r1.mozconfig",
        "mozconfigs/arm64-v8a-twitch-auth-radical-r1.mozconfig",
        "mozconfigs/armeabi-v7a-twitch-auth-radical-r1.mozconfig",
        "mozconfigs/x86-twitch-auth-radical-r1.mozconfig",
        "patches/0001-disable-android-hls.patch",
        "patches/0002-exclude-webrtc-dependent-android-test.patch",
        "patches/0003-twitch-auth-lite-build-cuts.patch",
        "scripts/build-aar.sh",
        "scripts/build-fat-aar.sh",
        "scripts/create-maven-archive.py",
        "scripts/source-common.sh",
        "scripts/test-toolchain-cache.sh",
        "scripts/test-x86-android-toolchain.sh",
        "scripts/verify-x86-config.py",
        "scripts/toolchain-cache.sh",
        "scripts/validate-recipe.py",
        "scripts/verify-aar.sh",
        "scripts/verify-config.py",
        "scripts/verify-fat-aar.sh",
        "scripts/measure-artifacts.sh",
        "scripts/symbolicate-android-crash.sh",
        "scripts/verify-auth-content-services.sh",
        "scripts/create-r1-abi-manifest.py",
        "scripts/collect-r1-aars.py",
        "scripts/promote-r1-release.sh",
        "scripts/r1-promotion-layout.sh",
        "scripts/test-r1-promotion-layout.sh",
        "scripts/r1-release-provenance.sh",
        "scripts/test-r1-release-provenance.sh",
        "scripts/preserve-r1-release.sh",
        "scripts/download-r1-release-aar.sh",
        "scripts/combine-r1-aar.sh",
        "scripts/combine-r1-aar.py",
        "scripts/test-combine-r1-aar.py",
        "scripts/test-collect-r1-aars.py",
        "diagnostics/x86_64-auth-crash-pcs.txt",
    ]
    missing = [path for path in required if not (ROOT / path).is_file()]
    if missing:
        raise SystemExit("missing recipe files: " + ", ".join(missing))

    print(f"validated recipe revision={revision} acquisition={lock['acquisition']}")


if __name__ == "__main__":
    main()
