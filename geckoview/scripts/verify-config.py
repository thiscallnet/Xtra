#!/usr/bin/env python3
"""Assert the configure substitutions required by an Xtra Gecko profile."""

import json
import sys
from pathlib import Path


def as_bool(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        return value.strip().lower() not in {"", "0", "false", "no", "off"}
    return bool(value)


def main():
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} CONFIG.STATUS.JSON PROFILE", file=sys.stderr)
        return 2

    config_path = Path(sys.argv[1])
    profile = sys.argv[2]
    if profile not in {"safe", "nowebrtc"}:
        print(f"unsupported profile: {profile}", file=sys.stderr)
        return 2
    if not config_path.is_file():
        print(f"configure output is missing: {config_path}", file=sys.stderr)
        return 1

    with config_path.open(encoding="utf-8") as config_file:
        config = json.load(config_file)
    substitutions = config.get("substs")
    if not isinstance(substitutions, dict):
        print("configure output has no substs object", file=sys.stderr)
        return 1

    expected_booleans = {
        "MOZ_ANDROID_GECKOVIEW_LITE": True,
        "MOZ_ANDROID_HLS_SUPPORT": False,
        "MOZ_WEBRTC": profile == "safe",
    }
    for name, expected in expected_booleans.items():
        if name not in substitutions:
            if not expected:
                continue
            print(f"configure output is missing {name}", file=sys.stderr)
            return 1
        actual = as_bool(substitutions[name])
        if actual != expected:
            print(
                f"unexpected {name}: expected {expected}, got {substitutions[name]!r}",
                file=sys.stderr,
            )
            return 1

    count = substitutions.get("MOZ_ANDROID_CONTENT_SERVICE_COUNT")
    if str(count) != "1":
        print(
            "unexpected MOZ_ANDROID_CONTENT_SERVICE_COUNT: "
            f"expected '1', got {count!r}",
            file=sys.stderr,
        )
        return 1

    print(
        f"verified profile={profile} lite=true hls=false "
        f"webrtc={str(expected_booleans['MOZ_WEBRTC']).lower()} content_services=1"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
