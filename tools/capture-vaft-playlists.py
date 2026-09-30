"""Export private debug playlist records without printing playback URLs."""

import argparse
import io
import json
import os
import pathlib
import subprocess
import tarfile
from collections import Counter


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--adb", default=str(pathlib.Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb")
                        if os.environ.get("ANDROID_HOME") else "adb")
    args = parser.parse_args()
    result = subprocess.run([args.adb, "-s", args.serial, "exec-out", "run-as",
                             "com.github.andreyasadchy.xtra.debug", "tar", "-cf", "-",
                             "-C", "cache", "vaft-playlists"], check=True, stdout=subprocess.PIPE)
    args.output.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(result.stdout)) as archive:
        archive.extractall(args.output, filter="data")
    records = [json.loads(path.read_text(encoding="utf-8"))
               for path in (args.output / "vaft-playlists").glob("*.json")]
    print(json.dumps({"records": len(records), "stages": dict(Counter(record["stage"] for record in records))}))


if __name__ == "__main__":
    main()
