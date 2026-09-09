#!/usr/bin/env python3
"""Resolve and verify the four R1 AARs from downloaded integration artifacts."""

import hashlib
import json
import sys
from pathlib import Path


ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
PROFILE = "twitch-auth-radical-r1"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as input_file:
        for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def one(paths, description: str) -> Path:
    paths = sorted(paths)
    if len(paths) != 1:
        found = ", ".join(str(path) for path in paths) or "none"
        raise SystemExit(f"expected exactly one {description}; found: {found}")
    return paths[0]


def collect(root: Path) -> list[Path]:
    if not root.is_dir():
        raise SystemExit(f"R1 integration root is missing: {root}")

    result = []
    for abi in ABIS:
        integration_name = f"xtra-geckoview-{abi}-{PROFILE}-integration"
        integration_dir = one(
            (path for path in root.rglob(integration_name) if path.is_dir()),
            f"{abi} integration directory",
        )
        manifest_path = one(
            integration_dir.rglob("r1-abi-manifest.json"),
            f"{abi} R1 ABI manifest",
        )
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest_abi = manifest["abi"]
            manifest_profile = manifest["profile"]
            aar = manifest["aar"]
            aar_name = aar["file"]
            expected_sha = aar["sha256"]
        except (KeyError, json.JSONDecodeError) as error:
            raise SystemExit(f"invalid {abi} R1 ABI manifest {manifest_path}: {error}") from error

        if manifest_abi != abi:
            raise SystemExit(f"R1 manifest ABI mismatch: expected {abi}, got {manifest_abi}")
        if manifest_profile != PROFILE:
            raise SystemExit(f"Unexpected R1 profile for {abi}: {manifest_profile}")
        if not isinstance(aar_name, str) or not aar_name or Path(aar_name).name != aar_name:
            raise SystemExit(f"Invalid AAR filename for {abi}: {aar_name!r}")
        if not isinstance(expected_sha, str) or len(expected_sha) != 64:
            raise SystemExit(f"Invalid AAR SHA-256 for {abi}: {expected_sha!r}")

        aar_path = one(
            (path for path in integration_dir.rglob(aar_name) if path.is_file()),
            f"{abi} AAR named {aar_name}",
        )
        actual_sha = sha256_file(aar_path)
        if actual_sha != expected_sha:
            raise SystemExit(
                f"AAR SHA mismatch for {abi}: expected {expected_sha}, got {actual_sha} ({aar_path})"
            )
        print(f"{abi} -> {aar_path}", file=sys.stderr)
        result.append(aar_path)

    return result


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} R1_INPUT_ROOT", file=sys.stderr)
        return 2
    for path in collect(Path(sys.argv[1])):
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
