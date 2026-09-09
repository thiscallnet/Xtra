#!/usr/bin/env python3
"""Combine four GeckoView AARs while preserving ABI-specific omni data."""
import io, shutil, sys, tempfile, zipfile
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
OMNI_ALLOWED = {"chrome/toolkit/content/global/buildconfig.html", "modules/AppConstants.sys.mjs"}
CLASS_ALLOWED = {"org/mozilla/geckoview/BuildConfig.class", "org/mozilla/geckoview/CrashHandler$1.class", "org/mozilla/geckoview/HardwareUtils.class"}
OUTER_ALLOWED = {"AndroidManifest.xml", "R.txt", "public.txt", "META-INF/com/android/build/gradle/aar-metadata.properties"}

def read_zip(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        return {n: z.read(n) for n in z.namelist()}

def compare(left, right, allowed, label):
    if set(left) != set(right):
        raise SystemExit(f"{label} entry set differs")
    for name in left:
        if left[name] != right[name] and name not in allowed:
            raise SystemExit(f"unexpected {label} difference: {name}")

def omni_abi_file(name):
    for abi in ABIS:
        if name in {f"{abi}/greprefs.js", f"defaults/pref/{abi}/geckoview-prefs.js"}:
            return abi
    return None

def main():
    if len(sys.argv) != 6:
        raise SystemExit("usage: combine-r1-aar.py OUTPUT ARM64 ARMV7 X86 X86_64")
    output = Path(sys.argv[1]).resolve()
    inputs = [Path(v).resolve() for v in sys.argv[2:]]
    if any(not p.is_file() for p in inputs):
        raise SystemExit("all four input AARs are required")
    with tempfile.TemporaryDirectory() as name:
        root = Path(name); dirs = []
        for path in inputs:
            target = root / path.stem; target.mkdir()
            with zipfile.ZipFile(path) as z: z.extractall(target)
            native = [p.name for p in (target / "jni").iterdir() if p.is_dir()]
            if len(native) != 1: raise SystemExit(f"AAR must contain one ABI: {path}")
            dirs.append((target, native[0]))
        if tuple(abi for _, abi in dirs) != ABIS:
            raise SystemExit(f"expected ABI order {ABIS}, got {tuple(abi for _, abi in dirs)}")
        outer = [{n: d for n, d in read_zip(p.read_bytes()).items() if not n.startswith("jni/") and n not in {"assets/omni.ja", "classes.jar"}} for p in inputs]
        for other in outer[1:]: compare(outer[0], other, OUTER_ALLOWED, "AAR")
        omnis = [read_zip((d / "assets/omni.ja").read_bytes()) for d, _ in dirs]
        all_omni_names = set().union(*(set(m) for m in omnis))
        for name in all_omni_names:
            values = [m.get(name) for m in omnis]
            if len({value for value in values if value is not None}) > 1 or any(value is None for value in values):
                if name not in OMNI_ALLOWED and omni_abi_file(name) is None:
                    raise SystemExit(f"unexpected omni.ja difference: {name}")
            expected_abi = omni_abi_file(name)
            if expected_abi is not None:
                expected_index = ABIS.index(expected_abi)
                if any(index != expected_index and value is not None for index, value in enumerate(values)):
                    raise SystemExit(f"ABI-specific omni file is in the wrong input: {name}")
        for abi in ABIS:
            if f"{abi}/greprefs.js" not in omnis[ABIS.index(abi)]:
                raise SystemExit(f"missing required omni file: {abi}/greprefs.js")
            if f"defaults/pref/{abi}/geckoview-prefs.js" not in omnis[ABIS.index(abi)]:
                raise SystemExit(f"missing required omni file: defaults/pref/{abi}/geckoview-prefs.js")
        merged = dict(omnis[0])
        for m in omnis[1:]:
            for n, d in m.items():
                if n not in merged or n.startswith("defaults/pref/") or n.endswith("/greprefs.js"): merged[n] = d
        classes = [read_zip((d / "classes.jar").read_bytes()) for d, _ in dirs]
        for m in classes[1:]: compare(classes[0], m, CLASS_ALLOWED, "classes.jar")
        (dirs[0][0] / "assets/omni.ja").unlink()
        with zipfile.ZipFile(dirs[0][0] / "assets/omni.ja", "w", zipfile.ZIP_STORED) as z:
            for n, d in sorted(merged.items()): z.writestr(n, d)
        merged_names = set(merged)
        required = {f"{abi}/greprefs.js" for abi in ABIS} | {f"defaults/pref/{abi}/geckoview-prefs.js" for abi in ABIS}
        if not required <= merged_names:
            raise SystemExit("merged omni.ja is missing required ABI files")
        for d, abi in dirs[1:]: shutil.copytree(d / "jni" / abi, dirs[0][0] / "jni" / abi)
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as z:
            for p in sorted(dirs[0][0].rglob("*")):
                if p.is_file(): z.write(p, p.relative_to(dirs[0][0]).as_posix())
    print(f"combined_r1_aar={output} size={output.stat().st_size}")

if __name__ == "__main__": main()
