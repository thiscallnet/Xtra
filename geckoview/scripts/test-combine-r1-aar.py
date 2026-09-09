#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
with tempfile.TemporaryDirectory() as name:
    root = Path(name)
    inputs = []
    def make_aar(abi, extra=False, missing=False, bad_class=False):
        path = root / f"{abi}.aar"
        with tempfile.TemporaryFile() as omni_stream, tempfile.TemporaryFile() as classes_stream:
            with zipfile.ZipFile(omni_stream, "w", zipfile.ZIP_STORED) as omni:
                omni.writestr("common.js", b"same")
                if not missing:
                    omni.writestr(f"{abi}/greprefs.js", abi.encode())
                    omni.writestr(f"defaults/pref/{abi}/geckoview-prefs.js", abi.encode())
                if extra:
                    omni.writestr("unexpected.js", b"bad")
            with zipfile.ZipFile(classes_stream, "w", zipfile.ZIP_STORED) as classes:
                classes.writestr("common.class", b"bad" if bad_class else b"same")
            omni_stream.seek(0); classes_stream.seek(0)
            with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as aar:
                aar.writestr("AndroidManifest.xml", b"manifest")
                aar.writestr("classes.jar", classes_stream.read())
                aar.writestr("assets/omni.ja", omni_stream.read())
                aar.writestr(f"jni/{abi}/libxul.so", abi.encode())
        return path

    for abi in ABIS:
        path = make_aar(abi)
        inputs.append(path)
    output = root / "universal.aar"
    subprocess.run([sys.executable, str(Path(__file__).with_name("combine-r1-aar.py")), str(output), *map(str, inputs)], check=True)
    with zipfile.ZipFile(output) as aar:
        assert {n for n in aar.namelist() if n.startswith("jni/")} == {f"jni/{abi}/libxul.so" for abi in ABIS}
        with zipfile.ZipFile(aar.open("assets/omni.ja")) as omni:
            for abi in ABIS:
                assert f"{abi}/greprefs.js" in omni.namelist()
                assert f"defaults/pref/{abi}/geckoview-prefs.js" in omni.namelist()
    def assert_rejected(variant):
        bad_inputs = [make_aar(abi, **variant) if abi == "x86" else make_aar(abi) for abi in ABIS]
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("combine-r1-aar.py")), str(root / "bad.aar"), *map(str, bad_inputs)])
        assert result.returncode != 0
    assert_rejected({"extra": True})
    assert_rejected({"missing": True})
    assert_rejected({"bad_class": True})
print("synthetic R1 universal AAR test passed")
