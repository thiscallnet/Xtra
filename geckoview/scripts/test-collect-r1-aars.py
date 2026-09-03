#!/usr/bin/env python3
"""Regression test for nested downloaded-artifact R1 AAR discovery."""

import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path


ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
PROFILE = "twitch-auth-radical-r1"

with tempfile.TemporaryDirectory() as name:
    root = Path(name) / "r1-inputs"
    expected = []
    for abi in ABIS:
        integration = root / f"downloaded/{abi}/xtra-geckoview-{abi}-{PROFILE}-integration"
        aar = integration / "preserved/source/geckoview" / f"r1-{abi}.aar"
        aar.parent.mkdir(parents=True)
        aar.write_bytes(f"synthetic-{abi}".encode())
        expected.append(str(aar))
        manifest = {
            "abi": abi,
            "profile": PROFILE,
            "aar": {
                "file": aar.name,
                "sha256": hashlib.sha256(aar.read_bytes()).hexdigest(),
            },
        }
        manifest_path = integration / "metadata/r1-abi-manifest.json"
        manifest_path.parent.mkdir(parents=True)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    collector = Path(__file__).with_name("collect-r1-aars.py")
    result = subprocess.run(
        [sys.executable, str(collector), str(root)],
        check=True,
        capture_output=True,
        text=True,
    )
    assert result.stdout.splitlines() == expected
    assert all(f"{abi} -> " in result.stderr for abi in ABIS)

print("nested R1 AAR collector test passed")
