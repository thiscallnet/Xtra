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
    if profile not in {
        "safe",
        "nowebrtc",
        "nowebspeech",
        "auth",
        "minimal",
        "twitch-auth-radical-r1",
    }:
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
        "MOZ_WEBRTC": profile not in {
            "nowebrtc",
            "nowebspeech",
            "minimal",
            "twitch-auth-radical-r1",
        },
    }
    if profile in {"nowebspeech", "minimal"}:
        expected_booleans["MOZ_WEBSPEECH"] = False
    if profile == "twitch-auth-radical-r1":
        expected_booleans.update(
            {
                "MOZ_TWITCH_AUTH_LITE": True,
                # Android widget sources unconditionally use this exported API.
                "ACCESSIBILITY": True,
                "ENABLE_WEBDRIVER": False,
                "MOZ_DISABLE_PARENTAL_CONTROLS": True,
                "MOZ_PROFILING": False,
                "MOZ_EXECUTION_TRACING": False,
                "ENABLE_SPIDERMONKEY_TELEMETRY": False,
                "MOZ_UNIVERSALCHARDET": False,
                "MOZ_ZIPWRITER": False,
            }
        )
    if profile == "minimal":
        expected_booleans.update(
            {
                "ENABLE_WEBDRIVER": False,
                "MOZ_DISABLE_PARENTAL_CONTROLS": True,
                "MOZ_ZIPWRITER": False,
            }
        )
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
    if str(count) != "40":
        print(
            "unexpected MOZ_ANDROID_CONTENT_SERVICE_COUNT: "
            f"expected Gecko's default '40', got {count!r}",
            file=sys.stderr,
        )
        return 1

    print(
        f"verified profile={profile} lite=true hls=false "
        f"webrtc={str(expected_booleans['MOZ_WEBRTC']).lower()} content_services=40"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
