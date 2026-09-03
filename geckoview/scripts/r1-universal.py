#!/usr/bin/env python3
"""Create and verify universal R1 provenance without modifying source manifests."""

import argparse
import hashlib
import io
import json
import re
import struct
import zipfile
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
ELF_TARGETS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}
IDENTITY = ("profile", "sourceRevision", "sourceGitRevision", "xtraCommit", "compileRecipeVersion")
ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(stream):
    value = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        value.update(chunk)
    return value.hexdigest()


def file_identity(path):
    with path.open("rb") as stream:
        return {"file": path.name, "sha256": digest(stream), "size": path.stat().st_size}


def check_identity(data, expected_commit=None):
    lock = json.loads((ROOT / "SOURCE_LOCK.json").read_text())
    expected = {"profile": "twitch-auth-radical-r1", "sourceRevision": lock["revision"],
                "sourceGitRevision": lock["sourceGitRevision"],
                "compileRecipeVersion": (ROOT / "COMPILE_RECIPE_VERSION").read_text().strip()}
    for key, value in expected.items():
        require(data.get(key) == value, f"R1 {key} mismatch")
    require(re.fullmatch(r"[0-9a-f]{40}", data.get("xtraCommit", "")), "Invalid R1 Xtra commit")
    if expected_commit:
        require(data["xtraCommit"] == expected_commit, "R1 expected commit mismatch")


def members(archive, prefix):
    names = archive.namelist()
    require(len(names) == len(set(names)), "Duplicate ZIP members")
    libraries = {abi: {} for abi in ABIS}
    for name in names:
        if not name.startswith(prefix + "/") or name.endswith("/"):
            continue
        parts = name.split("/")
        require(len(parts) == 3 and parts[1] in ABIS and parts[2].endswith(".so"),
                f"Unexpected native member: {name}")
        abi, library = parts[1:]
        with archive.open(name) as stream:
            header = stream.read(20)
        require(len(header) == 20 and header[:4] == b"\x7fELF" and header[5] == 1,
                f"Invalid ELF: {name}")
        require((header[4], struct.unpack_from("<H", header, 18)[0]) == ELF_TARGETS[abi],
                f"ELF architecture mismatch: {name}")
        with archive.open(name) as stream:
            libraries[abi][library] = digest(stream)
    return libraries


def create(input_root, aar, output, expected_commit):
    inputs = {}
    for path in input_root.rglob("r1-abi-manifest.json"):
        data = json.loads(path.read_text())
        abi = data.get("abi")
        require(abi in ABIS and abi not in inputs, "Expected exactly one integration manifest per ABI")
        check_identity(data, expected_commit)
        name = data["aar"]["file"]
        require(Path(name).name == name, "Invalid input AAR filename")
        matches = list(path.parent.rglob(name))
        require(len(matches) == 1, f"Expected one {abi} AAR")
        require(file_identity(matches[0]) == {key: data["aar"][key] for key in ("file", "sha256", "size")},
                f"Input AAR identity mismatch: {abi}")
        inputs[abi] = (data, matches[0])
    require(set(inputs) == set(ABIS), "All four R1 integrations are required")
    baseline = inputs[ABIS[0]][0]
    require(all(all(data[key] == baseline[key] for key in IDENTITY) for data, _ in inputs.values()),
            "R1 integrations do not share one build identity")
    with zipfile.ZipFile(aar) as universal:
        native = members(universal, "jni")
        with zipfile.ZipFile(io.BytesIO(universal.read("assets/omni.ja"))) as omni:
            for abi, (_, path) in inputs.items():
                with zipfile.ZipFile(path) as skinny:
                    skinny_native = members(skinny, "jni")
                    require(not any(skinny_native[other] for other in ABIS if other != abi),
                            f"Skinny AAR has another ABI: {abi}")
                    require(native[abi] == skinny_native[abi] and "libxul.so" in native[abi],
                            f"Universal native libraries differ from verified {abi} input")
                    with zipfile.ZipFile(io.BytesIO(skinny.read("assets/omni.ja"))) as source_omni:
                        for name in (f"{abi}/greprefs.js", f"defaults/pref/{abi}/geckoview-prefs.js"):
                            require(omni.read(name) == source_omni.read(name), f"Universal ABI preferences differ: {name}")
        with universal.open("assets/omni.ja") as stream:
            omni_sha = digest(stream)
    result = {key: baseline[key] for key in IDENTITY}
    result.update(abis=list(ABIS), universal=file_identity(aar), nativeLibraries=native,
                  omniSha256=omni_sha, artifacts=[inputs[abi][0] for abi in ABIS])
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def verify(aar, manifest, aggregate=None, apk=None, expected_commit=None):
    data = json.loads(manifest.read_text())
    check_identity(data, expected_commit)
    require(sorted(data.get("abis", [])) == sorted(ABIS), "Universal manifest ABI set mismatch")
    require(data.get("universal") == file_identity(aar), "Universal AAR identity mismatch")
    artifacts = data.get("artifacts", [])
    require(len(artifacts) == 4 and sorted(item.get("abi", "") for item in artifacts) == sorted(ABIS),
            "Universal manifest requires four ABI identities")
    for item in artifacts:
        require(all(item.get(key) == data[key] for key in IDENTITY), "Universal ABI provenance mismatch")
    with zipfile.ZipFile(aar) as archive:
        native = members(archive, "jni")
        require(native == data.get("nativeLibraries") and all("libxul.so" in native[abi] for abi in ABIS),
                "Universal native library identity mismatch")
        with archive.open("assets/omni.ja") as stream:
            require(digest(stream) == data.get("omniSha256"), "Universal omni identity mismatch")
        with zipfile.ZipFile(io.BytesIO(archive.read("assets/omni.ja"))) as omni:
            require(all(name in omni.namelist() for abi in ABIS for name in
                        (f"{abi}/greprefs.js", f"defaults/pref/{abi}/geckoview-prefs.js")),
                    "Universal omni is missing ABI preferences")
    if aggregate:
        release = json.loads(aggregate.read_text())
        qualified = release.get("qualifiedCommit", release.get("xtraCommit"))
        require(qualified == data["xtraCommit"], "Release qualified commit mismatch")
        require(release.get("artifacts") == artifacts, "Release ABI identities mismatch")
        require(all(release.get(key) == data[key] for key in IDENTITY if key != "xtraCommit"),
                "Release source identity mismatch")
        require(sorted(release.get("abis", [])) == sorted(ABIS), "Release ABI set mismatch")
        require(release.get("universal") == data, "Release universal identity mismatch")
    if apk:
        with zipfile.ZipFile(apk) as archive:
            packaged = members(archive, "lib")
            for abi in ABIS:
                require(all(packaged[abi].get(name) == sha for name, sha in native[abi].items()),
                        f"APK does not contain the exact Gecko native libraries for {abi}")
            with archive.open("assets/omni.ja") as stream:
                require(digest(stream) == data["omniSha256"], "APK Gecko omni differs from the AAR")
    print(f"verified_r1_universal={aar} sha256={data['universal']['sha256']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    creator = commands.add_parser("create")
    creator.add_argument("inputs", type=Path)
    creator.add_argument("aar", type=Path)
    creator.add_argument("manifest", type=Path)
    verifier = commands.add_parser("verify")
    verifier.add_argument("aar", type=Path)
    verifier.add_argument("manifest", type=Path)
    verifier.add_argument("--aggregate", type=Path)
    verifier.add_argument("--apk", type=Path)
    for command in (creator, verifier):
        command.add_argument("--expected-commit")
    args = parser.parse_args()
    try:
        if args.command == "create":
            create(args.inputs, args.aar, args.manifest, args.expected_commit)
        else:
            verify(args.aar, args.manifest, args.aggregate, args.apk, args.expected_commit)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"R1 verification failed: {error}\n")


if __name__ == "__main__":
    main()
