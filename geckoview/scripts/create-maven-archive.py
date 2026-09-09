#!/usr/bin/env python3
"""Create GeckoView's uncompressed target.maven.zip archive."""

import argparse
import os
import sys
import zipfile
from pathlib import Path


def maven_archive_paths(maven_dir: Path):
    """Yield Maven files using Gecko 150's archive selection rules."""
    for subdir, dirnames, filenames in os.walk(maven_dir):
        dirnames.sort()
        filenames.sort()
        if "-SNAPSHOT" in subdir:
            continue
        for filename in filenames:
            yield Path(subdir) / filename


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Create an uncompressed GeckoView Maven archive."
    )
    parser.add_argument("maven_dir", type=Path)
    parser.add_argument("output_zip", type=Path)
    args = parser.parse_args()

    if not args.maven_dir.is_dir():
        print(f"Maven directory is missing: {args.maven_dir}", file=sys.stderr)
        return 1

    files = list(maven_archive_paths(args.maven_dir))
    if not files:
        print(f"Maven directory contains no archivable files: {args.maven_dir}", file=sys.stderr)
        return 1

    try:
        with zipfile.ZipFile(
            args.output_zip, "w", compression=zipfile.ZIP_STORED
        ) as target_zip:
            for path in files:
                relative_path = path.relative_to(args.maven_dir).as_posix()
                target_zip.write(path, arcname=f"geckoview/{relative_path}")
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Failed to create Maven archive {args.output_zip}: {error}", file=sys.stderr)
        return 1

    if not args.output_zip.is_file():
        print(f"Maven archive was not created: {args.output_zip}", file=sys.stderr)
        return 1

    print(f"Created Maven archive: {args.output_zip}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
